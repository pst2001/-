package com.example.ttsapp.tts

/**
 * 长文本分段工具（纯函数，无 Android 依赖，便于单元测试）。
 *
 * 为什么必须分段：
 * [android.speech.tts.TextToSpeech.speak] 单次入队的文本长度上限约 4000 字符
 * （不同引擎实现略有差异），超长文本会被截断或直接返回 ERROR，
 * 所以按句子边界切成 ≤3500 字符的小段，再用 QUEUE_ADD 顺序入队。
 */
object TextChunker {

    /** 单段最大字符数，留出 500 字符余量给各引擎的实现差异 */
    const val DEFAULT_MAX_LENGTH = 3500

    /** 句子结束符：中英文标点 + 换行 */
    private val SENTENCE_ENDINGS = setOf(
        '。', '！', '？', '；', '…',
        '!', '?', '.', ';',
        '\n', '\r'
    )

    /**
     * 一段文本。[start] 是该段在原文本中的起始下标（相对传入 [chunk] 的字符串），
     * 用于把 onRangeStart 回调里的「段内偏移」换算成「全文偏移」，实现伪暂停续读。
     */
    data class Chunk(val text: String, val start: Int)

    /**
     * 按句子边界切分，保证每段长度 ≤ [maxLength]，且各段首尾相接、不丢字符。
     */
    fun chunk(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val limit = maxLength.coerceAtLeast(1)
        val result = ArrayList<Chunk>(text.length / limit + 1)
        var index = 0
        while (index < text.length) {
            val remaining = text.length - index
            if (remaining <= limit) {
                result += Chunk(text.substring(index), index)
                break
            }
            val windowEnd = index + limit
            val boundary = findSentenceBoundary(text, index, windowEnd)
            val cut = if (boundary > index) boundary else hardCut(text, index, windowEnd)
            // 兜底：极端情况下 cut 无法前进时，强制按窗口切，避免死循环
            val safeCut = if (cut > index) cut else windowEnd
            result += Chunk(text.substring(index, safeCut), index)
            index = safeCut
        }
        return result
    }

    /**
     * 从窗口末尾向前找最近的句子结束符，返回切点（标点之后）。
     * 只接受位于窗口后半段的切点，避免切出过短的段。
     */
    private fun findSentenceBoundary(text: String, start: Int, windowEnd: Int): Int {
        val minCut = start + (windowEnd - start) / 2
        var i = windowEnd - 1
        while (i >= minCut) {
            if (text[i] in SENTENCE_ENDINGS) return i + 1
            i--
        }
        return -1
    }

    /**
     * 没有句子边界时的硬切：
     * 1) 不把 emoji 等代理对（surrogate pair）从中间切开，否则会读出乱码；
     * 2) 尽量退到空白处，让语音停顿更自然。
     */
    private fun hardCut(text: String, start: Int, windowEnd: Int): Int {
        var cut = windowEnd
        if (cut in (start + 1) until text.length) {
            if (Character.isLowSurrogate(text[cut]) && Character.isHighSurrogate(text[cut - 1])) {
                cut -= 1
            }
        }
        val floor = start + (cut - start) / 2
        var i = cut - 1
        while (i > floor) {
            if (text[i].isWhitespace()) return i + 1
            i--
        }
        return cut
    }
}
