//! 学习资料补全防篡改与词条来源的单元测试：验证已有释义时不出现 meaning 字段及 4 种来源取值。
package org.qingjian.speech.learning.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesAgentSchemaTest {

    @Test
    fun testSchemaOmitsMeaningWhenAuthoritativeMeaningExists() {
        // 1. 已有权威词义：definitions 中不应包含 meaning 属性
        val toolDefs = Agent.definitions(hasAuthoritativeMeaning = true)
        val toolFn = toolDefs.getJSONObject(0).getJSONObject("function")
        val params = toolFn.getJSONObject("parameters")
        val props = params.getJSONObject("properties")
        val required = params.getJSONArray("required")

        assertFalse("已有词义时，properties 绝不能包含 meaning", props.has("meaning"))
        for (i in 0 until required.length()) {
            assertFalse("已有词义时，required 绝不能包含 meaning", required.getString(i) == "meaning")
        }

        // 2. 无权威词义：definitions 中必须包含 meaning
        val toolDefsNeedMeaning = Agent.definitions(hasAuthoritativeMeaning = false)
        val propsNeed = toolDefsNeedMeaning.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertTrue("无词义时，properties 必须包含 meaning", propsNeed.has("meaning"))
    }

    @Test
    fun testPackParsesFourFieldsWhenMeaningIsFixed() {
        val fourFields = JSONObject()
            .put("example", "The candidate won the election.")
            .put("translation", "候选人赢得了选举。")
            .put("collocations", JSONArray().put(JSONObject().put("phrase", "prime candidate").put("translation", "首要候选人")))
            .put("usage", "常作名词，指候选人或应试者。")

        val pack = Pack.parse(fourFields, "candidate", fallbackMeaning = "n. 候选人")
        assertEquals("n. 候选人", pack.meaning)
        assertEquals("The candidate won the election.", pack.example)
    }

    @Test
    fun testFourGlossOriginsFixture() {
        val origins = listOf("desktop-vocab", "static-table", "ai", "unknown")
        for (origin in origins) {
            val snapshot = Snapshot(
                id = 10L,
                english = "deploy",
                meaning = "v. 部署",
                revision = 1L,
                glossOrigin = origin
            )
            assertEquals(origin, snapshot.glossOrigin)

            val isAuthoritative = snapshot.meaning.isNotBlank() && (origin in listOf("desktop-vocab", "static-table") || snapshot.meaning.isNotEmpty())
            val context = Agent.buildContext(snapshot, isAuthoritative, JSONObject())
            if (isAuthoritative) {
                assertFalse("桌面已给词义或权威词义时，context 中不重复出现 meaning", context.has("meaning"))
            } else {
                assertTrue(context.has("meaning"))
            }
        }
    }
}
