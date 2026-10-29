package io.github.stalxjason.networkswitch

import java.io.Reader

/**
 * 有上限的读取。
 *
 * shell 命令输出、Root 读到的配置文件都会被整段读进内存，
 * 一旦对方返回异常大的内容（被 root 篡改的文件、被调包的 su），
 * 无上限的 `readText()` 会直接 OOM。这里按硬上限截断，
 * 拿到的内容若被截断，调用方应视为失败而不是当作完整数据使用。
 */
internal fun Reader.readAtMost(maxChars: Int): String {
    require(maxChars > 0) { "maxChars must be positive" }
    val buffer = CharArray(DEFAULT_BUFFER)
    val result = StringBuilder(minOf(maxChars, DEFAULT_BUFFER))
    while (result.length < maxChars) {
        val toRead = minOf(buffer.size, maxChars - result.length)
        val read = read(buffer, 0, toRead)
        if (read < 0) break
        result.appendRange(buffer, 0, read)
    }
    return result.toString()
}

/** 判断上一次读取是否触到上限（内容可能不完整） */
internal fun String.isProbablyTruncated(maxChars: Int): Boolean = length >= maxChars

private const val DEFAULT_BUFFER = 8 * 1024
