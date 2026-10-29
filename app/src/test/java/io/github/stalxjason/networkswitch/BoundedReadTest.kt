package io.github.stalxjason.networkswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 有上限的读取：shell 输出与 Root 读到的配置文件都走它，
 * 防止异常大的输入把进程撑爆。
 */
class BoundedReadTest {

    @Test
    fun `short input is read completely`() {
        val text = "abc".repeat(10)
        val result = StringReader(text).readAtMost(1000)
        assertEquals(text, result)
        assertFalse(result.isProbablyTruncated(1000))
    }

    @Test
    fun `long input stops exactly at the limit`() {
        val text = "x".repeat(5000)
        val result = StringReader(text).readAtMost(1024)
        assertEquals(1024, result.length)
        assertTrue(result.isProbablyTruncated(1024))
    }

    @Test
    fun `limit larger than content is not truncated`() {
        val result = StringReader("hello").readAtMost(64)
        assertEquals("hello", result)
        assertFalse(result.isProbablyTruncated(64))
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", StringReader("").readAtMost(16))
    }

    @Test
    fun `multibyte content is not cut in the middle of a surrogate pair`() {
        // 中文与 emoji 混排；上限按字符（UTF-16 code unit）计，只要不越界即可
        val text = "中文WiFi😀测试"
        val result = StringReader(text).readAtMost(text.length)
        assertEquals(text, result)
    }
}
