package com.example.ttsapp.model

/**
 * 朗读状态机。
 *
 * IDLE ──play──▶ PLAYING ──pause──▶ PAUSED ──resume──▶ PLAYING
 *                  │                  │
 *                  └────stop──────────┴──────▶ IDLE
 *                  └────最后一段读完────▶ FINISHED
 */
enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    FINISHED
}

/**
 * 供下拉列表使用的语言项。
 * [tag] 使用 BCP-47（例如 zh-CN / en-US），跨进程传递比 Locale 更稳定。
 */
data class TtsLanguage(
    val tag: String,
    val label: String
)

/**
 * 单页 UI 的完整状态快照。Compose 只读取这个对象，不再持有任何 TTS 引用。
 */
data class TtsUiState(
    val text: String = "",
    val speechRate: Float = DEFAULT_SPEECH_RATE,
    val pitch: Float = DEFAULT_PITCH,
    /** 语音引擎是否完成异步初始化（onInit 返回 SUCCESS） */
    val isReady: Boolean = false,
    /** 引擎名，仅用于展示，便于用户判断是否装了中文引擎 */
    val engineLabel: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val languages: List<TtsLanguage> = emptyList(),
    val selectedLanguageTag: String? = null,
    /** 已朗读到的字符位置（绝对偏移，来自 onRangeStart） */
    val spokenChars: Int = 0,
    val totalChars: Int = 0,
    /** 当前语言缺少语音数据，需要引导用户去系统设置安装 */
    val needInstallVoice: Boolean = false
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val isPaused: Boolean get() = status == PlaybackStatus.PAUSED

    /** 四个动作互斥：播放中不能再点播放/继续，暂停后才能继续 */
    val canPlay: Boolean
        get() = isReady && text.isNotBlank() && status != PlaybackStatus.PLAYING && status != PlaybackStatus.PAUSED

    val canPause: Boolean get() = isReady && status == PlaybackStatus.PLAYING
    val canResume: Boolean get() = isReady && status == PlaybackStatus.PAUSED
    val canStop: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED

    /** 0f..1f，用于进度条；没有文本时为 0 */
    val progress: Float
        get() = if (totalChars <= 0) 0f else (spokenChars.toFloat() / totalChars).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        /** 单次可输入的字符上限，防止粘贴超大文本导致卡顿/内存压力 */
        const val MAX_INPUT_CHARS = 20_000
    }
}
