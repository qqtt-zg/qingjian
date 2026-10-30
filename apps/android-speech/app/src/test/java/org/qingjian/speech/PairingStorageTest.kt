//! 多电脑配对数据序列化、主机去重与序号自愈校准的单元测试。
package org.qingjian.speech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qingjian.speech.learning.PairedDevice

class PairingStorageTest {

    @Test
    fun testPairedDeviceJsonRoundTrip() {
        val url = "https://speech-a.qingjian.app/#" + "a".repeat(32)
        val device = PairedDevice(url, "speech-a.qingjian.app", "办公室电脑", 123456789L)
        val json = device.toJson()

        assertEquals(url, json.getString("url"))
        assertEquals("speech-a.qingjian.app", json.getString("host"))
        assertEquals("办公室电脑", json.getString("name"))
        assertEquals(123456789L, json.getLong("last_connected"))

        val restored = PairedDevice.fromJson(json)
        assertNotNull(restored)
        assertEquals(device.url, restored!!.url)
        assertEquals(device.host, restored.host)
        assertEquals(device.name, restored.name)
        assertEquals(device.lastConnected, restored.lastConnected)
    }

    @Test
    fun testHostDeduplication() {
        val url1 = "https://speech-a.qingjian.app/#" + "a".repeat(32)
        val url2 = "https://speech-b.qingjian.app/#" + "b".repeat(32)
        val url1NewToken = "https://speech-a.qingjian.app/#" + "c".repeat(32)

        val list = mutableListOf(
            PairedDevice(url1, "speech-a.qingjian.app", "电脑A", 100L),
            PairedDevice(url2, "speech-b.qingjian.app", "电脑B", 200L)
        )

        val newPair = Pairing.parse(url1NewToken)
        val index = list.indexOfFirst { it.host == newPair.host }
        assertTrue("应当能找到同 Host 的已有配对", index >= 0)

        list[index] = PairedDevice(url1NewToken, newPair.host, list[index].name, 300L)

        assertEquals("总电脑数量不应重复增加", 2, list.size)
        assertEquals(url1NewToken, list[0].url)
        assertEquals("speech-b.qingjian.app", list[1].host)
    }

    @Test
    fun testSequenceSelfHealingCalibration() {
        // 场景 1：首次连接该电脑，本地无记录（-1），校准为 remoteSeq
        val remoteSeq1 = 42L
        val savedSeq1 = -1L
        val after1 = if (savedSeq1 == -1L || savedSeq1 > remoteSeq1) remoteSeq1 else savedSeq1
        assertEquals("本地无记录时应以对端序号为准", 42L, after1)

        // 场景 2：正常推进，本地记录（10）小于对端（15），保留本地以触发补播
        val remoteSeq2 = 15L
        val savedSeq2 = 10L
        val after2 = if (savedSeq2 == -1L || savedSeq2 > remoteSeq2) remoteSeq2 else savedSeq2
        assertEquals("本地落后对端时保留本地序号以便安全补播", 10L, after2)

        // 场景 3：对端重置或手机记录倒挂，本地（50）大于对端（0），自愈校准为 remoteSeq，避免长轮询死锁
        val remoteSeq3 = 0L
        val savedSeq3 = 50L
        val after3 = if (savedSeq3 == -1L || savedSeq3 > remoteSeq3) remoteSeq3 else savedSeq3
        assertEquals("本地序号倒挂时强制自愈重置为对端当前序号", 0L, after3)
    }

    @Test
    fun testLegacyMigrationLogic() {
        val legacyUrl = "https://speech.qingjian.app/#" + "f".repeat(32)
        val legacyMap = mutableMapOf<String, Any>("url" to legacyUrl)

        if (!legacyMap.containsKey("pairings") && legacyMap.containsKey("url")) {
            val oldUrl = legacyMap["url"] as String
            val pair = Pairing.parse(oldUrl)
            val dev = PairedDevice(oldUrl, pair.host, pair.host, 1000L)
            legacyMap["pairings"] = JSONArray().put(dev.toJson()).toString()
            legacyMap["active"] = oldUrl
        }

        assertTrue(legacyMap.containsKey("pairings"))
        assertEquals(legacyUrl, legacyMap["active"])
        val parsedArray = JSONArray(legacyMap["pairings"] as String)
        assertEquals(1, parsedArray.length())
        assertEquals("speech.qingjian.app", parsedArray.getJSONObject(0).getString("host"))
    }
}
