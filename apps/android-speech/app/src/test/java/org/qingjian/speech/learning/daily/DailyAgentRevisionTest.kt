//! 每日任务 Agent 修订与工具声明单元测试：验证用户题数指令抽取、修订工具与批量材料工具 Schema。
package org.qingjian.speech.learning.daily

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAgentRevisionTest {

    @Test
    fun testExtractQuestionLimitFromUserInstruction() {
        assertEquals(1, Agent.extractQuestionLimit("只做一题"))
        assertEquals(1, Agent.extractQuestionLimit("今天轻松一点，后续只做一题"))
        assertEquals(1, Agent.extractQuestionLimit("做1题"))
        assertEquals(2, Agent.extractQuestionLimit("今天做两题"))
        assertEquals(2, Agent.extractQuestionLimit("做2道题"))
        assertEquals(3, Agent.extractQuestionLimit("只做三题"))
        assertEquals(4, Agent.extractQuestionLimit("限4题"))
        assertEquals(5, Agent.extractQuestionLimit("做五题"))
        assertEquals(0, Agent.extractQuestionLimit("今天不想做了"))
        assertEquals(0, Agent.extractQuestionLimit("不做了，到此为止"))
        assertEquals(0, Agent.extractQuestionLimit("0题"))
        assertNull(Agent.extractQuestionLimit("正常复习，尽量多覆盖生词"))
        assertNull(Agent.extractQuestionLimit(""))
    }

    @Test
    fun testReviseStudyPlanToolSchema() {
        val def = ToolCatalog.reviseStudyPlanDefinition()
        assertEquals("function", def.getString("type"))
        val fn = def.getJSONObject("function")
        assertEquals("revise_study_plan", fn.getString("name"))

        val params = fn.getJSONObject("parameters")
        val req = params.getJSONArray("required")
        val reqList = (0 until req.length()).map { req.getString(it) }
        assertTrue(reqList.contains("explanation"))
        assertTrue(reqList.contains("operations"))

        val props = params.getJSONObject("properties")
        val ops = props.getJSONObject("operations")
        assertEquals("array", ops.getString("type"))
        assertEquals(10, ops.getInt("maxItems"))

        val itemProps = ops.getJSONObject("items").getJSONObject("properties")
        val types = itemProps.getJSONObject("type").getJSONArray("enum")
        val typeList = (0 until types.length()).map { types.getString(it) }
        assertTrue(typeList.containsAll(listOf("drop", "swap", "reorder", "add")))
    }

    @Test
    fun testBatchMaterialsToolSchema() {
        val def = ToolCatalog.batchMaterialsDefinition()
        assertEquals("function", def.getString("type"))
        val fn = def.getJSONObject("function")
        assertEquals("prepare_word_materials_batch", fn.getString("name"))

        val params = fn.getJSONObject("parameters")
        val words = params.getJSONObject("properties").getJSONObject("words")
        assertEquals(3, words.getInt("maxItems"))
        assertEquals(1, words.getInt("minItems"))

        val itemProps = words.getJSONObject("items").getJSONObject("properties")
        val req = words.getJSONObject("items").getJSONArray("required")
        val reqList = (0 until req.length()).map { req.getString(it) }
        assertTrue(reqList.containsAll(listOf("word_id", "english", "example", "translation", "collocations", "usage")))
        assertNotNull(itemProps.getJSONObject("collocations"))
    }

    @Test
    fun testRevisionDefinitionsCount() {
        val defs = ToolCatalog.definitionsForRevision()
        assertEquals(1, defs.length())
        assertEquals("revise_study_plan", defs.getJSONObject(0).getJSONObject("function").getString("name"))
    }
}
