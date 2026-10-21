//! 个人释义表覆盖层与查表优先级：不依赖模拟器。
package org.qingjian.speech.learning.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlossaryLookupTest {
    /** 随包表里有「候选」，没有「扭矩」。 */
    private fun table(): Glossary {
        val file = java.nio.file.Files.createTempFile("qingjian-lookup", ".qj").toFile()
        file.deleteOnExit()
        // arena 必须按字节算偏移：中文一个字三个字节，用字符数会错位。
        val arena = java.io.ByteArrayOutputStream()
        val entries = mutableListOf<ByteArray>()
        val records = mutableListOf<ByteArray>()
        var senseIndex = 0
        for (word in listOf("候选", "部署")) {
            val wordBytes = word.toByteArray(Charsets.UTF_8)
            val wordStart = arena.size()
            arena.write(wordBytes)
            val posBytes = "n.".toByteArray(Charsets.UTF_8)
            val posStart = arena.size()
            arena.write(posBytes)
            val textBytes = (if (word == "候选") "candidate" else "deploy").toByteArray(Charsets.UTF_8)
            val textStart = arena.size()
            arena.write(textBytes)
            val record = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            record.putInt(textStart)
            record.putInt(0)
            record.putInt(posStart)
            record.putShort(textBytes.size.toShort())
            record.putShort(0)
            record.putShort(posBytes.size.toShort())
            record.putShort(0)
            records.add(record.array())
            val entry = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            entry.putInt(wordStart)
            entry.putInt(senseIndex)
            entry.putShort(wordBytes.size.toShort())
            entry.putShort(1)
            entries.add(entry.array())
            senseIndex++
        }
        val text = arena.toByteArray()
        val entryBytes = entries.fold(ByteArray(0)) { all, part -> all + part }
        val senseBytes = records.fold(ByteArray(0)) { all, part -> all + part }
        val sections = listOf("TEXT" to text, "ENTR" to entryBytes, "SENS" to senseBytes)
        var offset = 32 + sections.size * 24
        val body = mutableListOf<ByteArray>()
        val placed = mutableListOf<Triple<String, Long, Long>>()
        for ((tag, bytes) in sections) {
            placed.add(Triple(tag, offset.toLong(), bytes.size.toLong()))
            body.add(bytes)
            offset += (bytes.size + 7) / 8 * 8
        }
        val output = java.nio.ByteBuffer.allocate(offset).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.put("QINGJIAN".toByteArray(Charsets.US_ASCII))
        output.putShort(1)
        output.putShort(3)
        output.putInt(sections.size)
        output.put(ByteArray(16))
        for ((tag, start, length) in placed) {
            output.put(tag.toByteArray(Charsets.US_ASCII))
            output.putInt(0)
            output.putLong(start)
            output.putLong(length)
        }
        for (bytes in body) {
            output.put(bytes)
            val padding = (bytes.size + 7) / 8 * 8 - bytes.size
            if (padding > 0) output.put(ByteArray(padding))
        }
        file.writeBytes(output.array())
        return Glossary.open(file)!!
    }

    private fun personalRows() = listOf(
        PersonalGloss("en", "扭矩", "n.", "torque", null),
        PersonalGloss("en", "候选", "n.", "candidate", null),
        PersonalGloss("ja", "候補", "n.", "候補", null))

    @Test
    fun personalGlossesDropTheOnesAlreadyInTheBundledTable() {
        val personal = PersonalGlosses.of(personalRows(), "en", table())
        // 「候选」随包表里有，不进覆盖层；「扭矩」表里没有，留着
        assertEquals(1, personal.size())
        assertNotNull(personal.find("扭矩"))
        assertNull(personal.find("候选"))
    }

    @Test
    fun personalGlossesOfAnotherLanguageAreIgnored() {
        val personal = PersonalGlosses.of(personalRows(), "en", table())
        assertNull(personal.find("候補"))
    }

    @Test
    fun withoutTheBundledTableEveryPersonalGlossIsKept() {
        val personal = PersonalGlosses.of(personalRows(), "en", null)
        assertEquals(2, personal.size())
    }

    @Test
    fun lookupPrefersThePersonalGloss() {
        val lookup = GlossaryLookup(table(), PersonalGlosses.of(personalRows(), "en", table()))
        // 「候选」个人释义被差集去掉了，查得到的是随包表那条
        val sense = lookup.find("候选")
        assertNotNull(sense)
        assertEquals("candidate", sense!!.text)
        // 「扭矩」随包表没有，个人释义顶上
        assertEquals("torque", lookup.find("扭矩")!!.text)
    }

    @Test
    fun lookupFallsBackToTheBundledTable() {
        val lookup = GlossaryLookup(table(), PersonalGlosses.of(personalRows(), "en", table()))
        assertNotNull(lookup.find("部署"))
        assertNull(lookup.find("没有这个词"))
    }

    @Test
    fun searchPutsPersonalGlossesFirst() {
        val lookup = GlossaryLookup(table(), PersonalGlosses.of(personalRows(), "en", table()))
        val results = lookup.search("候", 10)
        assertEquals(1, results.size)
        assertEquals("候选", results[0].first)
    }
}
