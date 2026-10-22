//! 静态译词表（.qj）的只读访问：精确查与子串搜索都不建内存索引。
//!
//! 格式见 crates/qingjian-format：头 32 字节 + 分节表 + 分节正文（8 字节对齐、数值小端）。
//! 释义表四节：TEXT（字符串 arena）、ENTR（词条表）、SENS（释义表）、HASH（哈希索引，本端不用）。
//! ENTR 按词的码点升序排列，比较时直接比 UTF-8 字节，跟文件里的顺序完全一致。
//!
//! 读法记两条，都是踩过的坑：
//! 一、ByteBuffer 的绝对索引是相对 buffer 起点算的，所以各分节只记基址，取数时统一加上
//!    基址——不要用 position 去切分节，那会把绝对索引带偏。
//! 二、duplicate() 会把字节序重置回大端，所以每次 duplicate 之后都要重新 order(LITTLE_ENDIAN)。
//!
//! 文件可能被截断或写坏：分节范围在 open() 时校验，单条词条 / 释义越界在访问期降级成
//! 「查不到」，任何情况下都不抛异常（protocol.md §2.2：查不到不报错、不猜）。
package org.qingjian.speech.learning.glossary

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

/** 一条释义：`[词性.]译词[|读音]`。词性与读音都可能没有。 */
class Sense(val partOfSpeech: String?, val text: String, val reading: String?)

/**
 * 随包静态译词表。文件是 mmap 打开的，词条与释义都按需切片，不整表读进内存。
 *
 * 表里只有「词 → 译词」，没有例句、搭配、用法——那些要 AI 补。
 */
class Glossary private constructor(
    private val buffer: ByteBuffer,
    private val textBase: Int,
    private val textLength: Int,
    private val entriesBase: Int,
    private val sensesBase: Int,
    private val sensesCount: Int,
    private val count: Int,
) {
    /** 词条数。 */
    fun size(): Int = count

    /**
     * 精确查一个词，查不到返回 null。
     *
     * 协议 §2.2：查 glossary-zh 时键先做 ASCII 小写（它的键是小写，用户输入可能是 Company /
     * COMPANY）。先原样查、查不到再小写查一次，与桌面端 `engine/commit/mod.rs` 的顺序一致。
     */
    fun find(word: String): Sense? {
        val index = indexOf(word.toByteArray(StandardCharsets.UTF_8))
        if (index != null) return senseAt(index)
        val lowered = asciiLowercase(word)
        if (lowered == word) return null
        return indexOf(lowered.toByteArray(StandardCharsets.UTF_8))?.let { senseAt(it) }
    }

    /**
     * 子串搜索。返回「词 → 释义」，按文件里的顺序；结果上限由调用方给。
     *
     * 与手机现有词库搜索的子串匹配一致（搜「选」能找出「候选」）。词条按码点升序，
     * 扫一遍词条表加 arena，都是顺序读。查的键是合法 UTF-8，在合法 UTF-8 里做字节匹配
     * 必然落在字符边界上，不用另判。查询含 ASCII 大写而原样查不到时，按小写再扫一次。
     */
    fun search(query: String, limit: Int): List<Pair<String, Sense>> {
        if (limit <= 0) return emptyList()
        val exact = searchBytes(query.toByteArray(StandardCharsets.UTF_8), limit)
        if (exact.isNotEmpty()) return exact
        val lowered = asciiLowercase(query)
        if (lowered == query) return emptyList()
        return searchBytes(lowered.toByteArray(StandardCharsets.UTF_8), limit)
    }

    /** 词条的字面（按码点升序的第 index 个）。 */
    fun wordAt(index: Int): String? {
        if (!validEntry(index)) return null
        return arena(wordStart(index), wordLength(index))
    }

    private fun validEntry(index: Int): Boolean = index >= 0 && index < count

    /** 一段 arena 范围是否落在 TEXT 分节内。损坏的表里偏移可能指向分节外。 */
    private fun inArena(start: Int, length: Int): Boolean =
        start >= 0 && length >= 0 && start.toLong() + length <= textLength.toLong()

    private fun wordStart(index: Int): Int = buffer.getInt(entriesBase + index * ENTRY_SIZE)

    private fun wordLength(index: Int): Int =
        buffer.getShort(entriesBase + index * ENTRY_SIZE + 8).toInt() and 0xFFFF

    private fun senseCount(index: Int): Int =
        buffer.getShort(entriesBase + index * ENTRY_SIZE + 10).toInt() and 0xFFFF

    private fun senseStart(index: Int): Int = buffer.getInt(entriesBase + index * ENTRY_SIZE + 4)

    private fun senseAt(index: Int): Sense? {
        if (!validEntry(index)) return null
        val first = senseStart(index)
        val total = senseCount(index)
        if (total <= 0) return null
        if (first < 0 || first.toLong() + total > sensesCount.toLong()) return null
        val base = sensesBase + first * SENSE_SIZE
        val textStart = buffer.getInt(base)
        val textLength = buffer.getShort(base + 12).toInt() and 0xFFFF
        val readingStart = buffer.getInt(base + 4)
        val readingLength = buffer.getShort(base + 14).toInt() and 0xFFFF
        val posStart = buffer.getInt(base + 8)
        val posLength = buffer.getShort(base + 16).toInt() and 0xFFFF
        if (!inArena(textStart, textLength) || !inArena(readingStart, readingLength) ||
            !inArena(posStart, posLength)) return null
        val text = arena(textStart, textLength) ?: return null
        return Sense(
            if (posLength > 0) arena(posStart, posLength) else null,
            text,
            if (readingLength > 0) arena(readingStart, readingLength) else null)
    }

    /** 二分找词的准确位置。 */
    private fun indexOf(needle: ByteArray): Int? {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = wordStart(mid)
            val length = wordLength(mid)
            // 词条指向 arena 外说明表已损坏，二分没有可信的比较基准，按查不到处理
            if (!inArena(start, length)) return null
            val comparison = compare(start, length, needle)
            if (comparison == 0) return mid
            if (comparison < 0) low = mid + 1 else high = mid - 1
        }
        return null
    }

    /** 按 UTF-8 字节比大小：先逐字节，再比长度。跟文件里的码点顺序一致。 */
    private fun compare(start: Int, length: Int, needle: ByteArray): Int {
        val limit = if (length < needle.size) length else needle.size
        for (i in 0 until limit) {
            val left = buffer.get(textBase + start + i).toInt() and 0xFF
            val right = needle[i].toInt() and 0xFF
            if (left != right) return left - right
        }
        return length - needle.size
    }

    /** 词里是否含这段字节。 */
    private fun contains(start: Int, length: Int, needle: ByteArray): Boolean {
        if (needle.size > length) return false
        for (offset in 0..length - needle.size) {
            var matched = true
            for (i in needle.indices) {
                if (buffer.get(textBase + start + offset + i) != needle[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /** 从 arena 切一段 UTF-8；越界返回 null。 */
    private fun arena(start: Int, length: Int): String? {
        if (length <= 0) return ""
        if (!inArena(start, length)) return null
        val bytes = ByteArray(length)
        val slice = buffer.duplicate()
        slice.position(textBase + start)
        slice.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** 扫一遍词条表做子串匹配；损坏的词条跳过，不中断整次搜索。 */
    private fun searchBytes(needle: ByteArray, limit: Int): List<Pair<String, Sense>> {
        if (needle.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, Sense>>()
        for (index in 0 until count) {
            val start = wordStart(index)
            val length = wordLength(index)
            if (!inArena(start, length)) continue
            if (!contains(start, length, needle)) continue
            val word = arena(start, length) ?: continue
            val sense = senseAt(index) ?: continue
            result.add(word to sense)
            if (result.size >= limit) break
        }
        return result
    }

    companion object {
        private const val MAGIC = "QINGJIAN"
        private const val VERSION = 1
        private const val HEADER_SIZE = 32
        private const val SECTION_SIZE = 24
        private const val ENTRY_SIZE = 12
        private const val SENSE_SIZE = 20

        /**
         * 打开释义表。文件不存在、被截断或格式不对时返回 null，不抛异常。
         *
         * 分节范围必须在 open() 就校验：只按头部 entriesLength 算词条数的话，截断的表能打开，
         * 但之后每次查询都会越界抛异常。
         */
        fun open(file: File): Glossary? {
            if (!file.isFile) return null
            return try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                    val size = channel.size()
                    if (size < HEADER_SIZE || size > Int.MAX_VALUE) return null
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                        .duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val header = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    header.position(0)
                    val magic = ByteArray(8)
                    header.get(magic)
                    if (String(magic, StandardCharsets.US_ASCII) != MAGIC) return null
                    if (header.getShort().toInt() and 0xFFFF != VERSION) return null
                    header.getShort()                                   // 种类
                    val sectionCount = header.getInt()
                    if (sectionCount < 0 ||
                        HEADER_SIZE.toLong() + sectionCount.toLong() * SECTION_SIZE > size) {
                        return null
                    }
                    header.position(HEADER_SIZE)                        // 头里的保留位之后才是分节表
                    var textBase = -1
                    var textLength = 0
                    var entriesBase = -1
                    var entriesLength = 0
                    var sensesBase = -1
                    var sensesLength = 0
                    for (index in 0 until sectionCount) {
                        val tag = ByteArray(4)
                        header.get(tag)
                        header.getInt()                               // 对齐用的保留位
                        val offset = header.getLong()
                        val length = header.getLong()
                        // 截断的文件里分节会跑到文件尾之外，整张表按「没有」处理
                        if (offset < 0 || length < 0 || offset > size - length) return null
                        when (String(tag, StandardCharsets.US_ASCII)) {
                            "TEXT" -> {
                                textBase = offset.toInt()
                                textLength = length.toInt()
                            }
                            "ENTR" -> {
                                entriesBase = offset.toInt()
                                entriesLength = length.toInt()
                            }
                            "SENS" -> {
                                sensesBase = offset.toInt()
                                sensesLength = length.toInt()
                            }
                        }
                    }
                    if (textBase < 0 || entriesBase < 0 || sensesBase < 0) return null
                    if (entriesLength <= 0 || entriesLength % ENTRY_SIZE != 0) return null
                    if (sensesLength <= 0 || sensesLength % SENSE_SIZE != 0) return null
                    Glossary(buffer, textBase, textLength, entriesBase, sensesBase,
                        sensesLength / SENSE_SIZE, entriesLength / ENTRY_SIZE)
                }
            } catch (error: Exception) {
                null
            }
        }

        /**
         * ASCII-only 小写：只把 A-Z 转成 a-z。
         *
         * 桌面端用的是 `to_ascii_lowercase`，Kotlin 的 lowercase() 会做 Unicode 映射，
         * 两边结果会对不上（例如土耳其语 İ）。
         */
        private fun asciiLowercase(text: String): String {
            val chars = text.toCharArray()
            var changed = false
            for (index in chars.indices) {
                val char = chars[index]
                if (char in 'A'..'Z') {
                    chars[index] = char + 32
                    changed = true
                }
            }
            return if (changed) String(chars) else text
        }
    }
}
