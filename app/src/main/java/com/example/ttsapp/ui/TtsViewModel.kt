package com.example.ttsapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ttsapp.R
import com.example.ttsapp.model.PlaybackStatus
import com.example.ttsapp.model.TtsLanguage
import com.example.ttsapp.model.TtsUiState
import com.example.ttsapp.tts.TtsManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.round

/**
 * 一次性事件（Snackbar 提示）。用 SharedFlow 而不是放在 UiState 里，
 * 是为了避免旋转屏幕后提示被重复弹出。
 */
sealed interface TtsEvent {
    data class Message(val text: String) : TtsEvent
}

/**
 * 状态与业务逻辑：持有 TtsManager，对外只暴露 StateFlow。
 * 不依赖 Hilt，用 [Factory] 手工创建即可。
 */
class TtsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val ttsManager = TtsManager(application)

    private val _uiState = MutableStateFlow(TtsUiState())
    val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TtsEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    /** 语言 Locale 缓存，和 UiState 里的 tag 一一对应 */
    private var locales: List<Locale> = emptyList()

    // ------------------------------------------------------------ 引擎回调

    private val managerListener = object : TtsManager.Listener {

        override fun onReady(engineName: String?) {
            _uiState.update { it.copy(isReady = true, engineLabel = engineName) }
        }

        override fun onInitFailed() {
            _uiState.update { it.copy(isReady = false) }
            emitMessage(app.getString(R.string.error_tts_init_failed))
        }

        override fun onLanguages(languages: List<Locale>) {
            locales = languages
            val mapped = languages.map { locale ->
                TtsLanguage(tag = locale.toLanguageTag(), label = localeLabel(locale))
            }
            val current = ttsManager.currentLanguage()?.toLanguageTag()
            _uiState.update { state ->
                state.copy(
                    languages = mapped,
                    selectedLanguageTag = current
                        ?: state.selectedLanguageTag
                        ?: mapped.firstOrNull()?.tag
                )
            }
        }

        override fun onLanguageUnavailable(locale: Locale) {
            _uiState.update { it.copy(needInstallVoice = true) }
            emitMessage(app.getString(R.string.error_language_missing, localeLabel(locale)))
        }

        override fun onStatusChanged(status: PlaybackStatus) {
            _uiState.update { it.copy(status = status) }
        }

        override fun onProgress(spokenChars: Int, totalChars: Int) {
            _uiState.update {
                it.copy(spokenChars = spokenChars, totalChars = totalChars)
            }
        }

        override fun onError(message: String) {
            emitMessage(message)
        }

        override fun onInterrupted() {
            emitMessage(app.getString(R.string.hint_audio_focus_lost))
        }
    }

    // init 必须写在 managerListener 之后：Kotlin 的初始化按声明顺序执行，
    // 写在前面会拿到尚未赋值的 null 回调对象。
    init {
        ttsManager.initialize(managerListener)
    }

    // ------------------------------------------------------------ 文本与参数

    fun onTextChange(newText: String) {
        val current = _uiState.value
        if (newText == current.text) return

        val limited = newText.take(TtsUiState.MAX_INPUT_CHARS)
        if (limited.length != newText.length) {
            emitMessage(app.getString(R.string.hint_text_too_long, TtsUiState.MAX_INPUT_CHARS))
        }

        val wasActive =
            current.status == PlaybackStatus.PLAYING || current.status == PlaybackStatus.PAUSED
        if (wasActive) {
            // 已朗读的字符偏移对应的是旧文本，必须先停表并清零，否则续读会串位
            ttsManager.stop()
            emitMessage(app.getString(R.string.hint_text_changed_reset))
        }

        _uiState.update { state ->
            state.copy(text = limited, spokenChars = 0, totalChars = limited.length)
        }
    }

    fun onSpeechRateChange(value: Float) {
        _uiState.update { it.copy(speechRate = roundToTenth(value, TtsUiState.MIN_SPEECH_RATE, TtsUiState.MAX_SPEECH_RATE)) }
    }

    fun onPitchChange(value: Float) {
        _uiState.update { it.copy(pitch = roundToTenth(value, TtsUiState.MIN_PITCH, TtsUiState.MAX_PITCH)) }
    }

    /** 滑块松手后：正在朗读则从当前位置按新参数重读，立刻能听出差别 */
    fun onParameterChangeFinished() {
        val state = _uiState.value
        if (state.status != PlaybackStatus.PLAYING) return
        ttsManager.speak(state.text, state.speechRate, state.pitch, ttsManager.playbackPosition())
        emitMessage(app.getString(R.string.hint_param_changed))
    }

    // ------------------------------------------------------------ 播放控制

    fun play() {
        val state = _uiState.value
        if (!state.isReady) {
            emitMessage(app.getString(R.string.error_tts_not_ready))
            return
        }
        if (state.text.isBlank()) {
            emitMessage(app.getString(R.string.error_empty_text))
            return
        }
        ttsManager.speak(state.text, state.speechRate, state.pitch, 0)
    }

    fun pause() {
        ttsManager.pause()
    }

    fun resume() {
        ttsManager.resume()
    }

    fun stop() {
        ttsManager.stop()
    }

    fun onLanguageSelected(tag: String) {
        val locale = locales.firstOrNull { it.toLanguageTag() == tag } ?: return
        val accepted = ttsManager.selectLanguage(locale)
        _uiState.update { it.copy(selectedLanguageTag = tag, needInstallVoice = !accepted) }
        if (accepted && _uiState.value.status == PlaybackStatus.PLAYING) {
            val state = _uiState.value
            ttsManager.speak(state.text, state.speechRate, state.pitch, ttsManager.playbackPosition())
        }
    }

    // ------------------------------------------------------------ 内部工具

    private fun emitMessage(text: String) {
        _events.tryEmit(TtsEvent.Message(text))
    }

    private fun localeLabel(locale: Locale): String {
        // 用中文显示名，避免系统语言是英文时列表全是英文
        val name = locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
        return name.ifBlank { locale.toLanguageTag() }
    }

    private fun roundToTenth(value: Float, min: Float, max: Float): Float =
        (round(value * 10f) / 10f).coerceIn(min, max)

    override fun onCleared() {
        // 必须释放：否则退出界面后引擎仍在朗读，且 TextToSpeech 会泄漏 Context
        ttsManager.release()
        super.onCleared()
    }

    companion object {
        /** 简单的 ViewModel Factory，替代 Hilt */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                TtsViewModel(application)
            }
        }
    }
}
