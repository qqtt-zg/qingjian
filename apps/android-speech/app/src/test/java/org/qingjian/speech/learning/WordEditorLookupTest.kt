//! 词条编辑中英文双向查词与来源判定的单元测试。
package org.qingjian.speech.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qingjian.speech.learning.glossary.Sense

class WordEditorLookupTest {

    private val zhToEnMap = mapOf(
        "候选" to Sense("n.", "candidate", null),
        "部署" to Sense("v.", "deploy", null),
        "学习" to Sense("v.", "study", null)
    )

    private val enToZhMap = mapOf(
        "candidate" to Sense("n.", "候选人", null),
        "deploy" to Sense("v.", "部署", null)
    )

    @Test
    fun testChineseInputFindsEnglishAndSetsStaticTableOrigin() {
        val result = WordEditorLookup.resolve(
            input = "候选",
            userMeaning = "",
            lookupZhToEn = { zhToEnMap[it] },
            lookupEnToZh = { enToZhMap[it] }
        )
        assertTrue(result.isChineseInput)
        assertTrue(result.matchFound)
        assertEquals("candidate", result.english)
        assertEquals("n. 候选", result.meaning)
        assertEquals("static-table", result.origin)
    }

    @Test
    fun testEnglishInputFindsChineseAndSetsStaticTableOrigin() {
        val result = WordEditorLookup.resolve(
            input = "candidate",
            userMeaning = "",
            lookupZhToEn = { zhToEnMap[it] },
            lookupEnToZh = { enToZhMap[it] }
        )
        assertFalse(result.isChineseInput)
        assertTrue(result.matchFound)
        assertEquals("candidate", result.english)
        assertEquals("n. 候选人", result.meaning)
        assertEquals("static-table", result.origin)
    }

    @Test
    fun testUnmatchedChineseLeavesEnglishEmptyAndSetsUnknownOrigin() {
        val result = WordEditorLookup.resolve(
            input = "某生僻中文词",
            userMeaning = "自定义笔记",
            lookupZhToEn = { null },
            lookupEnToZh = { null }
        )
        assertTrue(result.isChineseInput)
        assertFalse(result.matchFound)
        assertEquals("", result.english)
        assertEquals("自定义笔记", result.meaning)
        assertEquals("unknown", result.origin)
    }

    @Test
    fun testUnmatchedEnglishSetsUnknownOrigin() {
        val result = WordEditorLookup.resolve(
            input = "unmatchedword",
            userMeaning = "手动填写的中文",
            lookupZhToEn = { null },
            lookupEnToZh = { null }
        )
        assertFalse(result.isChineseInput)
        assertFalse(result.matchFound)
        assertEquals("unmatchedword", result.english)
        assertEquals("手动填写的中文", result.meaning)
        assertEquals("unknown", result.origin)
    }
}
