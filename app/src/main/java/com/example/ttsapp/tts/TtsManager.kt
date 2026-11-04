package com.example.ttsapp.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.ttsapp.R
import com.example.ttsapp.model.PlaybackStatus
import java.util.Locale

/**
 * 对系统 [TextToSpeech] 的唯一封装层。
 *
 * 职责：
 * 1. 处理异步初始化（onInit 之前 speak 必然失败）；
 * 2. 长文本分段 + 顺序入队 + 整体结束判定；
 * 3. 用 stop() + 字符偏移实现「伪暂停 / 继续」；
 * 4. 音频焦点申请与释放，被抢占时自动停止；
 * 5. 所有回调统一切回主线程，ViewModel 不需要自己做线程切换。
 *
 * 安全约束：只与本机 TTS 引擎（Binder）交互，不申请任何权限、不发起网络请求、
 * 不读写文件、不打印用户文本（避免文本进入 logcat 被其它应用读取）。
 */
class TtsManager(context: Context) {

    interface Listener {
        fun onReady(engineName: String?)
        fun onInitFailed()
        fun onLanguages(languages: List<Locale>)
        fun onLanguageUnavailable(locale: Locale)
        fun onStatusChanged(status: PlaybackStatus)
        fun onProgress(spokenChars: Int, totalChars: Int)
        fun onError(message: String)
        /** 音频焦点被其它应用抢走，已自动停止 */
        fun onInterrupted()
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null
    private var listener: Listener? = null
    private var isReady = false

    /** 会话令牌：每次 speak/stop 自增，用于丢弃上一轮的残留回调 */
    private var sessionToken = 0
    private var chunks: List<TextChunker.Chunk> = emptyList()
    private var sessionStartOffset = 0
    private var lastKnownOffset = 0
    private var totalChars = 0
    private var currentText = ""
    private var currentRate = 1.0f
    private var currentPitch = 1.0f
    private var status = PlaybackStatus.IDLE
    private var focusRequest: AudioFocusRequest? = null

    // ---------------------------------------------------------------- 初始化

    fun initialize(listener: Listener) {
        this.listener = listener
        if (tts != null) return
        val engine = TextToSpeech(appContext) { initStatus ->
            // onInit 可能在 Binder 线程回调，统一切主线程再改状态
            mainHandler.post { handleInitResult(initStatus) }
        }
        engine.setOnUtteranceProgressListener(progressListener)
        tts = engine
    }

    private fun handleInitResult(initStatus: Int) {
        val engine = tts
        if (initStatus != TextToSpeech.SUCCESS || engine == null) {
            isReady = false
            listener?.onInitFailed()
            return
        }
        isReady = true

        // 初始化成功后立刻设一个可用语言，避免首次 speak 时语言状态不确定
        val defaultLocale = pickDefaultLocale(engine)
        if (defaultLocale != null) {
            val result = safeSetLanguage(engine, defaultLocale)
            if (isLanguageBroken(result)) {
                listener?.onLanguageUnavailable(defaultLocale)
            }
        }
        listener?.onReady(engineName(engine))
        listener?.onLanguages(queryLanguages(engine))
    }

    /**
     * 优先跟随系统语言，其次简体中文，最后英文；都没有就用引擎列表里的第一个。
     */
    private fun pickDefaultLocale(engine: TextToSpeech): Locale? {
        val available = queryLanguages(engine)
        if (available.isEmpty()) return null
        val preferred = listOf(Locale.getDefault(), Locale.SIMPLIFIED_CHINESE, Locale.US)
        for (locale in preferred) {
            val exact = available.firstOrNull {
                it.language == locale.language && locale.country.isNotEmpty() && it.country == locale.country
            }
            val sameLanguage = available.firstOrNull { it.language == locale.language }
            val hit = exact ?: sameLanguage
            if (hit != null) return hit
        }
        return available.first()
    }

    /** 中文排最前，英文其次，其余按系统显示名排序 */
    private fun queryLanguages(engine: TextToSpeech): List<Locale> {
        val raw: Set<Locale> = try {
            engine.availableLanguages ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
        return raw.asSequence()
            .filter { it.language.isNotBlank() && it.language != "und" }
            .distinctBy { it.toLanguageTag() }
            .sortedWith(
                compareBy<Locale>({ languageRank(it) }, { it.getDisplayName(Locale.SIMPLIFIED_CHINESE) })
            )
            .toList()
    }

    private fun languageRank(locale: Locale): Int = when (locale.language) {
        "zh" -> 0
        "en" -> 1
        else -> 2
    }

    private fun engineName(engine: TextToSpeech): String? = try {
        engine.defaultEngine
    } catch (t: Throwable) {
        null
    }

    private fun isLanguageBroken(result: Int): Boolean =
        result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED

    private fun safeSetLanguage(engine: TextToSpeech, locale: Locale): Int = try {
        engine.setLanguage(locale)
    } catch (t: Throwable) {
        TextToSpeech.ERROR
    }

    // ------------------------------------------------------------ 语言相关 API

    fun availableLanguages(): List<Locale> = tts?.let { queryLanguages(it) } ?: emptyList()

    fun currentLanguage(): Locale? = try {
        // 用当前 Voice 的 locale 取语言；TextToSpeech.getLanguage() 已被标记为废弃
        tts?.voice?.locale
    } catch (t: Throwable) {
        null
    }

    /**
     * 切换语言。返回 false 表示该语言缺数据或不受支持，UI 需要引导用户去系统设置。
     */
    fun selectLanguage(locale: Locale): Boolean {
        val engine = tts ?: return false
        val availability = try {
            engine.isLanguageAvailable(locale)
        } catch (t: Throwable) {
            TextToSpeech.ERROR
        }
        if (isLanguageBroken(availability)) {
            listener?.onLanguageUnavailable(locale)
            return false
        }
        val result = safeSetLanguage(engine, locale)
        if (isLanguageBroken(result)) {
            listener?.onLanguageUnavailable(locale)
            return false
        }
        return result != TextToSpeech.ERROR
    }

    // ---------------------------------------------------------------- 播放控制

    /**
     * 从 [startOffset] 开始朗读 [text]。
     * @param startOffset 绝对字符偏移，用于「继续朗读」时跳过已读部分
     */
    fun speak(text: String, rate: Float, pitch: Float, startOffset: Int = 0) {
        val engine = tts
        if (!isReady || engine == null) {
            listener?.onError(appContext.getString(R.string.error_tts_not_ready))
            return
        }
        if (text.isBlank()) {
            listener?.onError(appContext.getString(R.string.error_empty_text))
            return
        }
        val offset = startOffset.coerceIn(0, text.length)
        if (offset >= text.length) {
            // 已经读到末尾，直接按「完成」处理
            totalChars = text.length
            currentText = text
            finishSession(text.length)
            return
        }
        if (!requestAudioFocus()) {
            listener?.onError(appContext.getString(R.string.tts_error_audio_focus))
            return
        }

        currentText = text
        currentRate = rate
        currentPitch = pitch
        totalChars = text.length
        sessionStartOffset = offset
        lastKnownOffset = offset

        // 只对「剩余部分」分段，段内偏移 + sessionStartOffset 即全文偏移
        val parts = TextChunker.chunk(text.substring(offset))
        if (parts.isEmpty()) {
            abandonAudioFocus()
            return
        }
        chunks = parts
        sessionToken++

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)

        setStatus(PlaybackStatus.PLAYING)
        listener?.onProgress(offset, totalChars)

        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        parts.forEachIndexed { index, part ->
            // 第一段用 QUEUE_FLUSH 清空旧队列（重新朗读/改参数时不会叠音），其余顺序追加
            val queueMode =
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = try {
                engine.speak(part.text, queueMode, params, utteranceId(sessionToken, index))
            } catch (t: Throwable) {
                TextToSpeech.ERROR
            }
            if (result != TextToSpeech.SUCCESS) {
                listener?.onError(appContext.getString(R.string.tts_error_speak_failed))
                abandonAudioFocus()
                setStatus(PlaybackStatus.IDLE)
                return
            }
        }
    }

    /**
     * 伪暂停：Android TTS 没有 pause API，只能 stop() 并记住当前字符位置，
     * 继续时从该位置截取剩余文本重新 speak。
     * 代价：停顿时正在合成的那一句会从头重读（取决于引擎 onRangeStart 的精度）。
     */
    fun pause() {
        val engine = tts ?: return
        if (status != PlaybackStatus.PLAYING) return
        val offset = lastKnownOffset.coerceIn(0, totalChars)
        sessionToken++ // 丢弃残留回调
        try {
            engine.stop()
        } catch (t: Throwable) {
            // 引擎已断开时忽略
        }
        abandonAudioFocus()
        setStatus(PlaybackStatus.PAUSED)
        listener?.onProgress(offset, totalChars)
    }

    fun resume() {
        if (status != PlaybackStatus.PAUSED) return
        speak(currentText, currentRate, currentPitch, lastKnownOffset)
    }

    fun stop() {
        sessionToken++
        try {
            tts?.stop()
        } catch (t: Throwable) {
            // 引擎已断开时忽略
        }
        abandonAudioFocus()
        chunks = emptyList()
        lastKnownOffset = 0
        setStatus(PlaybackStatus.IDLE)
        listener?.onProgress(0, currentText.length)
    }

    /** 当前朗读到的绝对字符位置，供「改参数后从当前位置重读」使用 */
    fun playbackPosition(): Int = lastKnownOffset

    /** 必须调用：ViewModel.onCleared() 里释放，避免内存泄漏与后台继续发声 */
    fun release() {
        sessionToken++
        isReady = false
        try {
            tts?.stop()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            // ignore
        }
        tts = null
        abandonAudioFocus()
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------------- 内部实现

    private fun finishSession(position: Int) {
        abandonAudioFocus()
        chunks = emptyList()
        lastKnownOffset = position.coerceIn(0, totalChars)
        setStatus(PlaybackStatus.FINISHED)
        listener?.onProgress(lastKnownOffset, totalChars)
    }

    private fun setStatus(newStatus: PlaybackStatus) {
        if (status == newStatus) return
        status = newStatus
        listener?.onStatusChanged(newStatus)
    }

    /** utteranceId 形如 tts-<会话号>-<段号>，用于识别「是不是最后一段」并过滤过期回调 */
    private fun utteranceId(token: Int, index: Int): String = "tts-$token-$index"

    private fun parseUtteranceId(id: String?): Pair<Int, Int>? {
        if (id == null) return null
        val parts = id.split('-')
        if (parts.size != 3 || parts[0] != "tts") return null
        val token = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return token to index
    }

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val parsed = parseUtteranceId(utteranceId) ?: return
            mainHandler.post {
                if (parsed.first != sessionToken) return@post
                val part = chunks.getOrNull(parsed.second) ?: return@post
                // 引擎不支持 onRangeStart 时，退化成「按段」记录位置，暂停后最多重读一段
                lastKnownOffset = (sessionStartOffset + part.start).coerceIn(0, totalChars)
                listener?.onProgress(lastKnownOffset, totalChars)
            }
        }

        override fun onDone(utteranceId: String?) {
            val parsed = parseUtteranceId(utteranceId) ?: return
            mainHandler.post {
                if (parsed.first != sessionToken) return@post
                // 只有最后一段读完才算整体结束
                if (parsed.second >= chunks.lastIndex) {
                    finishSession(totalChars)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                listener?.onError(appContext.getString(R.string.tts_error_engine))
                abandonAudioFocus()
                setStatus(PlaybackStatus.IDLE)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                val token = parseUtteranceId(utteranceId)?.first
                if (token != null && token != sessionToken) return@post
                listener?.onError(errorMessage(errorCode))
                abandonAudioFocus()
                setStatus(PlaybackStatus.IDLE)
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // stop() 触发的回调；状态已由 pause()/stop() 主动设置，这里不做处理
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val parsed = parseUtteranceId(utteranceId) ?: return
            mainHandler.post {
                if (parsed.first != sessionToken) return@post
                val part = chunks.getOrNull(parsed.second) ?: return@post
                // 段内偏移 → 全文偏移，这是伪暂停精度的来源
                val absolute = sessionStartOffset + part.start + start
                lastKnownOffset = absolute.coerceIn(0, totalChars)
                listener?.onProgress(lastKnownOffset, totalChars)
            }
        }
    }

    private fun errorMessage(errorCode: Int): String = when (errorCode) {
        TextToSpeech.ERROR_SYNTHESIS -> appContext.getString(R.string.tts_error_synthesis)
        TextToSpeech.ERROR_SERVICE -> appContext.getString(R.string.tts_error_service)
        TextToSpeech.ERROR_OUTPUT -> appContext.getString(R.string.tts_error_output)
        TextToSpeech.ERROR_INVALID_REQUEST -> appContext.getString(R.string.tts_error_invalid_request)
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            appContext.getString(R.string.tts_error_network)
        else -> appContext.getString(R.string.tts_error_engine)
    }

    // -------------------------------------------------------------- 音频焦点

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mainHandler.post {
                    if (status == PlaybackStatus.PLAYING) {
                        // 来电或其它 App 抢音频：立即停止并通知 UI
                        stop()
                        listener?.onInterrupted()
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true // 极端情况拿不到 AudioManager 时不要阻塞朗读
        val result: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: return
            am.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
    }
}
