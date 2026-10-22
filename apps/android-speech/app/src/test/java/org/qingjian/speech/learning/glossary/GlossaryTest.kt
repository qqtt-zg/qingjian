//! 静态译词表的读取与查询：不依赖模拟器，秒级反馈。
//!
//! 合成一个迷你 .qj 来验解析——格式按 crates/qingjian-format 的布局写：
//! 头 32 字节（魔数、版本、种类、分节数）+ 分节表每条 24 字节（标签、对齐、偏移、长度）
//! + 分节正文（8 字节对齐、小端）。分节：TEXT 字符串 arena、ENTR 词条表、SENS 释义表。
//!
//! 除正常读取外，还验两件协议要求：ASCII 小写回退（§2.2），以及截断 / 越界的表只降级、
//! 不抛异常。
package org.qingjian.speech.learning.glossary

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {
    /** 按码点升序排的词条，与真实 .qj 的顺序一致（候选 U+5019 < 扭矩 U+626D < 翻译 U+8BD1 < 部署 U+90E8）。 */
    private val words = listOf("候选", "扭矩", "翻译", "部署")

    /** 词 → 释义（词性、译词、读音）。读器只给第一条释义，所以读音挂在第一条上。 */
    private val senses = mapOf(
        "候选" to listOf(Triple("n.", "candidate", null), Triple("adj.", "prospective", null)),
        "部署" to listOf(Triple("v.", "deploy", "@")),
        "扭矩" to listOf(Triple("n.", "torque", null), Triple("n.", "moment", null)),
        "翻译" to listOf(Triple("v.", "translate", null)))

    /** 英→中方向的键是小写，用来验 ASCII 小写回退。 */
    private val englishWords = listOf("candidate", "deploy", "kelvin", "torque")

    private val englishSenses = mapOf(
        "candidate" to listOf(Triple("n.", "候选人", null)),
        "deploy" to listOf(Triple("v.", "部署", null)),
        "kelvin" to listOf(Triple("n.", "开尔文", null)),
        "torque" to listOf(Triple("n.", "扭矩", null)))

    /** 造一个迷你 .qj：词条按码点升序，释义紧随其后。 */
    private fun buildGlossary(
        words: List<String> = this.words,
        senses: Map<String, List<Triple<String, String, String?>>> = this.senses,
    ): ByteArray {
        // arena 必须按字节算偏移：中文一个字三个字节，用字符数会错位。
        val arena = java.io.ByteArrayOutputStream()
        val entries = mutableListOf<ByteArray>()
        val senseRecords = mutableListOf<ByteArray>()
        var senseIndex = 0
        for (word in words) {
            val wordBytes = word.toByteArray(StandardCharsets.UTF_8)
            val wordStart = arena.size()
            arena.write(wordBytes)
            val first = senseIndex
            for ((pos, text, reading) in senses.getValue(word)) {
                val posBytes = pos.toByteArray(StandardCharsets.UTF_8)
                val posStart = arena.size()
                arena.write(posBytes)
                val textBytes = text.toByteArray(StandardCharsets.UTF_8)
                val textStart = arena.size()
                arena.write(textBytes)
                val readingBytes = reading?.toByteArray(StandardCharsets.UTF_8)
                val readingStart = if (readingBytes == null) 0 else {
                    val start = arena.size()
                    arena.write(readingBytes)
                    start
                }
                val record = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                record.putInt(textStart)
                record.putInt(readingStart)
                record.putInt(posStart)
                record.putShort(textBytes.size.toShort())
                record.putShort((readingBytes?.size ?: 0).toShort())
                record.putShort(posBytes.size.toShort())
                record.putShort(0)
                senseRecords.add(record.array())
                senseIndex++
            }
            val entry = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            entry.putInt(wordStart)
            entry.putInt(first)
            entry.putShort(wordBytes.size.toShort())
            entry.putShort(senses.getValue(word).size.toShort())
            entries.add(entry.array())
        }
        val text = arena.toByteArray()
        val entryBytes = entries.fold(ByteArray(0)) { all, part -> all + part }
        val senseBytes = senseRecords.fold(ByteArray(0)) { all, part -> all + part }
        val sections = listOf("TEXT" to text, "ENTR" to entryBytes, "SENS" to senseBytes)
        var offset = 32 + sections.size * 24
        val body = mutableListOf<ByteArray>()
        val placed = mutableListOf<Triple<String, Long, Long>>()
        for ((tag, bytes) in sections) {
            placed.add(Triple(tag, offset.toLong(), bytes.size.toLong()))
            body.add(bytes)
            offset += (bytes.size + 7) / 8 * 8
        }
        val output = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
        output.put("QINGJIAN".toByteArray(StandardCharsets.US_ASCII))
        output.putShort(1)                                     // 版本
        output.putShort(3)                                     // 种类：释义表
        output.putInt(sections.size)
        output.put(ByteArray(16))                              // 保留位
        for ((tag, start, length) in placed) {
            output.put(tag.toByteArray(StandardCharsets.US_ASCII))
            output.putInt(0)
            output.putLong(start)
            output.putLong(length)
        }
        for (bytes in body) {
            output.put(bytes)
            val padding = (bytes.size + 7) / 8 * 8 - bytes.size
            if (padding > 0) output.put(ByteArray(padding))
        }
        return output.array()
    }

    private fun tempFile(bytes: ByteArray): File {
        val file = Files.createTempFile("qingjian-glossary", ".qj").toFile()
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun open(): Glossary {
        val glossary = Glossary.open(tempFile(buildGlossary()))
        assertNotNull("迷你 .qj 应当能打开", glossary)
        return glossary!!
    }

    private fun openEnglish(): Glossary {
        val glossary = Glossary.open(tempFile(buildGlossary(englishWords, englishSenses)))
        assertNotNull("英文键的迷你 .qj 应当能打开", glossary)
        return glossary!!
    }

    /** 分节表里某节正文的文件偏移与长度。 */
    private fun sectionOf(bytes: ByteArray, tag: String): Pair<Int, Int> {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = header.getInt(12)
        for (index in 0 until count) {
            val at = 32 + index * 24
            if (String(bytes, at, 4, StandardCharsets.US_ASCII) == tag) {
                return header.getLong(at + 8).toInt() to header.getLong(at + 16).toInt()
            }
        }
        throw AssertionError("分节表里没有 $tag")
    }

    private fun patchInt(bytes: ByteArray, at: Int, value: Int) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
    }

    private fun patchShort(bytes: ByteArray, at: Int, value: Int) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(at, value.toShort())
    }

    @Test
    fun readsTheWordAndItsSenses() {
        val glossary = open()
        assertEquals(words.size, glossary.size())
        val sense = glossary.find("翻译")
        assertNotNull(sense)
        assertEquals("v.", sense!!.partOfSpeech)
        assertEquals("translate", sense.text)
        assertNull(sense.reading)
    }

    @Test
    fun readsTheReadingWhenThereIsOne() {
        val sense = open().find("部署")
        assertNotNull(sense)
        assertEquals("v.", sense!!.partOfSpeech)
        assertEquals("deploy", sense.text)
        assertEquals("@", sense.reading)
    }

    @Test
    fun reportsMissingWordsAsNull() {
        val glossary = open()
        assertNull(glossary.find("不存在的词"))
        assertNull(glossary.find(""))
    }

    @Test
    fun findsByBinarySearchOnTheSortedEntries() {
        val glossary = open()
        // 词条按码点升序，二分要能在中间命中
        assertEquals("候选", glossary.wordAt(0))
        assertEquals("部署", glossary.wordAt(3))
        assertNotNull(glossary.find("翻译"))
        assertNotNull(glossary.find("候选"))
    }

    @Test
    fun substringSearchFindsWordsThatDoNotStartWithTheQuery() {
        // 与手机现有词库搜索一致：搜「选」要能找出「候选」
        val results = open().search("选", 10)
        assertEquals(1, results.size)
        assertEquals("候选", results[0].first)
    }

    @Test
    fun substringSearchReturnsNothingForAnEmptyQuery() {
        assertEquals(0, open().search("", 10).size)
    }

    @Test
    fun substringSearchRespectsTheLimit() {
        val glossary = open()
        assertTrue(glossary.search("翻", 1).size <= 1)
    }

    @Test
    fun rejectsAFileThatIsNotAGlossary() {
        val file = tempFile("这不是 .qj".toByteArray(StandardCharsets.UTF_8))
        assertNull(Glossary.open(file))
    }

    @Test
    fun readsTheRealProductGlossaryWhenItIsAvailable() {
        // 本机有产品数据时额外验一次真实文件：合成夹具验不到 23 万词的真实布局与性能。
        val path = realGlossary()
        if (path == null) {
            // 仓库里备了产品数据却找不到词表，说明路径变了，要报错而不是跳过
            assertNull("仓库里有 data/generated 却没有 glossary-en.qj", generatedDir())
            return
        }
        val glossary = Glossary.open(path)!!
        assertTrue("真实词表应当有二十万条以上", glossary.size() > 200000)
        val sense = glossary.find("候选")
        assertNotNull("真实词表里应当查得到「候选」", sense)
        assertEquals("candidate", sense!!.text)
        assertEquals("n.", sense.partOfSpeech)
        assertNotNull(glossary.find("部署"))
        val started = System.nanoTime()
        val hits = glossary.search("候选", 20)
        val elapsed = (System.nanoTime() - started) / 1000000
        assertTrue("子串搜索应当有结果", hits.isNotEmpty())
        assertTrue("子串搜索不该超过两秒，实际 $elapsed 毫秒", elapsed < 2000)
    }

    @Test
    fun findsMixedCaseEnglishKeysByAsciiLowercaseFallback() {
        // 协议 §2.2：查 glossary-zh 时键先做 ASCII 小写；桌面端先原样查、再小写查
        val glossary = openEnglish()
        val expected = glossary.find("deploy")
        assertNotNull(expected)
        assertEquals(expected!!.text, glossary.find("Deploy")!!.text)
        assertEquals(expected.text, glossary.find("DEPLOY")!!.text)
        assertEquals("部署", glossary.find("Deploy")!!.text)
        assertNull(glossary.find("DEPLOYED"))
    }

    @Test
    fun substringSearchFallsBackToAsciiLowercase() {
        val glossary = openEnglish()
        assertEquals(listOf("deploy"), glossary.search("Dep", 10).map { it.first })
        assertEquals(listOf("deploy"), glossary.search("DEP", 10).map { it.first })
        assertEquals(listOf("deploy"), glossary.search("dep", 10).map { it.first })
    }

    @Test
    fun asciiLowercaseDoesNotMapNonAsciiLetters() {
        // 开尔文符号 U+212A 的 Unicode 小写是 k；ASCII-only 不转，所以查不到
        val glossary = openEnglish()
        assertNotNull(glossary.find("Kelvin"))
        assertNull(glossary.find("\u212Aelvin"))
    }

    @Test
    fun mixedCaseLookupLeavesChineseKeysAlone() {
        val glossary = open()
        // 中文表的键不含英文，回退一次也是查不到；中文键本身不受影响
        assertNull(glossary.find("Deploy"))
        assertNotNull(glossary.find("部署"))
        assertNotNull(glossary.find("翻译"))
    }

    @Test
    fun truncatedFilesAreRejectedWithoutThrowing() {
        val bytes = buildGlossary()
        val cuts = listOf(0, 8, 31, 32, 32 + 24 * 3 - 1, 32 + 24 * 3, 32 + 24 * 3 + 1,
            bytes.size / 2, bytes.size - 1)
        for (cut in cuts) {
            val glossary = Glossary.open(tempFile(bytes.copyOfRange(0, cut)))
            // 截断到分节正文之前必须打不开；万一是可打开的部分文件，查询也只能降级
            if (cut <= 32 + 24 * 3) assertNull("截断到 $cut 字节不该能打开", glossary)
            if (glossary != null) {
                assertNull(glossary.find("部署"))
                assertNull(glossary.wordAt(0))
                assertTrue(glossary.search("部署", 5).isEmpty())
            }
        }
    }

    @Test
    fun outOfRangeEntryWordRangeDegradesToNull() {
        // 分节表合法、词条越界：表能打开，但查询只能降级成「查不到」
        val bytes = buildGlossary()
        val entriesBase = sectionOf(bytes, "ENTR").first
        patchInt(bytes, entriesBase, 0x7FFF_FFF0)              // 第 0 条词的 arena 偏移
        val glossary = Glossary.open(tempFile(bytes))!!
        assertNull(glossary.wordAt(0))
        assertNull(glossary.find("候选"))
        assertTrue(glossary.search("候选", 5).isEmpty())
    }

    @Test
    fun outOfRangeEntryWordLengthDegradesToNull() {
        val bytes = buildGlossary()
        val entriesBase = sectionOf(bytes, "ENTR").first
        patchShort(bytes, entriesBase + 8, 0xFFFF)             // 第 0 条词的长度
        val glossary = Glossary.open(tempFile(bytes))!!
        assertNull(glossary.wordAt(0))
        assertNull(glossary.find("候选"))
        // 其他词条不受影响
        assertNotNull(glossary.find("翻译"))
    }

    @Test
    fun outOfRangeSenseIndexDegradesToNull() {
        val bytes = buildGlossary()
        val entriesBase = sectionOf(bytes, "ENTR").first
        patchInt(bytes, entriesBase + 4, 0x7FFF_FFF0)          // 第 0 条词的释义下标
        val glossary = Glossary.open(tempFile(bytes))!!
        assertNull(glossary.find("候选"))
        assertEquals("候选", glossary.wordAt(0))
        assertNotNull(glossary.find("翻译"))
    }

    @Test
    fun outOfRangeArenaSliceDegradesToNull() {
        val bytes = buildGlossary()
        val sensesBase = sectionOf(bytes, "SENS").first
        patchInt(bytes, sensesBase, 0x7FFF_FFF0)               // 第一条释义的译文起点
        val glossary = Glossary.open(tempFile(bytes))!!
        assertNull(glossary.find("候选"))
        assertNotNull(glossary.find("翻译"))
    }

    @Test
    fun rejectsTheRealGlossaryWhenItIsTruncated() {
        // 真实表 15.5MB，截到 100 万字节时头部与分节表都还在，只有分节正文不全
        val path = realGlossary() ?: return
        val bytes = path.readBytes()
        val truncated = tempFile(bytes.copyOfRange(0, 1_000_000))
        val glossary = Glossary.open(truncated)
        assertNull("截断到 100 万字节的真实词表不该能打开", glossary)
    }

    /** 从模块目录往上找仓库根的 data/generated。 */
    private fun generatedDir(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "data/generated")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /** 从模块目录往上找仓库根的 data/generated/glossary-en.qj；找不到返回 null。 */
    private fun realGlossary(): File? {
        val dir = generatedDir() ?: return null
        val candidate = File(dir, "glossary-en.qj")
        return if (candidate.isFile) candidate else null
    }

    @Test
    fun rejectsAMissingFile() {
        assertNull(Glossary.open(File("不存在的文件.qj")))
    }
}
