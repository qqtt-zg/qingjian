//! 跨端意图同步与应用分布协议单元测试：验证待练意图拉取、ACK 回执格式与应用聚合解析。
package org.qingjian.speech.learning.archive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransferIntentTest {

    @Test
    fun testParseIntentsJson() {
        val json = JSONObject("""
            {
                "schema": 1,
                "generated_at": "2026-10-25T14:30:00+08:00",
                "total": 2,
                "intents": [
                    {
                        "id": "desk_1",
                        "english": "deploy",
                        "meaning": "部署",
                        "dimension": "spelling",
                        "source": "desktop",
                        "created_at": 1729836000000,
                        "expires_at": 1732428000000
                    },
                    {
                        "id": "desk_2",
                        "english": "candidate",
                        "meaning": "候选词",
                        "dimension": "meaning",
                        "source": "desktop",
                        "created_at": 1729836000000,
                        "expires_at": 1732428000000
                    }
                ]
            }
        """.trimIndent())

        assertEquals(1, json.getInt("schema"))
        assertEquals(2, json.getInt("total"))
        val arr = json.getJSONArray("intents")
        assertEquals(2, arr.length())

        val item1 = arr.getJSONObject(0)
        assertEquals("desk_1", item1.getString("id"))
        assertEquals("deploy", item1.getString("english"))
        assertEquals("spelling", item1.getString("dimension"))
    }

    @Test
    fun testBuildAckPayload() {
        val ackedIds = listOf("desk_1", "desk_2")
        val payload = JSONObject().apply {
            put("schema", 1)
            put("acked_ids", JSONArray(ackedIds))
        }

        assertEquals(1, payload.getInt("schema"))
        val idsArr = payload.getJSONArray("acked_ids")
        assertEquals(2, idsArr.length())
        assertEquals("desk_1", idsArr.getString(0))
        assertEquals("desk_2", idsArr.getString(1))
    }

    @Test
    fun testParseContextWithAppDistribution() {
        val json = JSONObject("""
            {
                "schema": 1,
                "generated_at": "2026-10-25T14:30:00+08:00",
                "days": 30,
                "apps_status": "collected",
                "truncated": false,
                "total": 1,
                "available": true,
                "words": [
                    {
                        "english": "deploy",
                        "origin_text": "部署",
                        "example": "例句",
                        "last": "2026-10-22",
                        "desktop_known": false,
                        "apps": [
                            {"app": "Code.exe", "commit_with_gloss": 10, "gloss_typed": 2},
                            {"app": "chrome.exe", "commit_with_gloss": 5, "gloss_typed": 1},
                            {"app": "other", "commit_with_gloss": 2, "gloss_typed": 0}
                        ]
                    }
                ],
                "glosses": []
            }
        """.trimIndent())

        val ctx = ContextJson.parseContext(json)
        assertEquals("collected", ctx.appsStatus)
        assertEquals(1, ctx.words.size)
        val word = ctx.words[0]
        assertEquals("deploy", word.english)
        assertNotNull(word.apps)
        val apps = word.apps!!
        assertEquals(3, apps.size)
        assertEquals("Code.exe", apps[0].app)
        assertEquals(10, apps[0].commitWithGloss)
        assertEquals(2, apps[0].glossTyped)
        assertEquals("other", apps[2].app)
    }

    @Test
    fun testParseContextWithoutApps() {
        val json = JSONObject("""
            {
                "schema": 1,
                "generated_at": "2026-10-25T14:30:00+08:00",
                "days": 30,
                "apps_status": "not_collected",
                "truncated": false,
                "total": 1,
                "available": true,
                "words": [
                    {
                        "english": "deploy",
                        "origin_text": "部署",
                        "example": "例句",
                        "last": "2026-10-22",
                        "desktop_known": false
                    }
                ],
                "glosses": []
            }
        """.trimIndent())

        val ctx = ContextJson.parseContext(json)
        assertEquals("not_collected", ctx.appsStatus)
        val word = ctx.words[0]
        assertNull(word.apps)
    }
}
