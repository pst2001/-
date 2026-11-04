package com.example.ttsapp.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段逻辑是纯函数，用本地单元测试（./gradlew test）即可验证，不需要真机。
 */
class TextChunkerTest {

    @Test
    fun `空文本不产生分段`() {
        assertTrue(TextChunker.chunk("").isEmpty())
    }

    @Test
    fun `短文本保持一段`() {
        val text = "你好，欢迎使用文字转语音应用。"
        val chunks = TextChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].start)
        assertEquals(text, chunks[0].text)
    }

    @Test
    fun `长文本按句切分且不丢字符`() {
        val sentence = "这是一句用来测试分段的中文句子。"
        val text = sentence.repeat(600) // 16 * 600 = 9600 字符
        val chunks = TextChunker.chunk(text)

        assertTrue("应该切成多段", chunks.size >= 3)
        chunks.forEach {
            assertTrue("单段不能超过上限", it.text.length <= TextChunker.DEFAULT_MAX_LENGTH)
        }
        // 拼回去必须和原文完全一致
        assertEquals(text, chunks.joinToString(separator = "") { it.text })

        // start 必须严格首尾相接，否则伪暂停会串位
        var expected = 0
        chunks.forEach { chunk ->
            assertEquals(expected, chunk.start)
            expected += chunk.text.length
        }
        assertEquals(text.length, expected)
    }

    @Test
    fun `没有标点时按长度硬切`() {
        val text = "a".repeat(9000)
        val chunks = TextChunker.chunk(text)
        assertEquals(3, chunks.size)
        assertEquals(3500, chunks[0].text.length)
        assertEquals(3500, chunks[1].text.length)
        assertEquals(2000, chunks[2].text.length)
    }

    @Test
    fun `不会把代理对切开`() {
        // 每个 emoji 占 2 个 char，硬切时不能从中间断开
        val text = "😀".repeat(300)
        val chunks = TextChunker.chunk(text, maxLength = 100)

        chunks.forEach { chunk ->
            assertTrue(
                "分段不能以低位代理开头",
                !Character.isLowSurrogate(chunk.text[0])
            )
            assertTrue(
                "分段不能以高位代理结尾",
                !Character.isHighSurrogate(chunk.text[chunk.text.length - 1])
            )
        }
        assertEquals(text, chunks.joinToString(separator = "") { it.text })
    }

    @Test
    fun `优先在句号处断开`() {
        val text = "第一句话。".repeat(1000)
        val chunks = TextChunker.chunk(text, maxLength = 100)
        chunks.forEach {
            assertTrue("每段应以句号结束", it.text.endsWith("。"))
        }
    }
}
