//! 释义表的下载判定与安装规则：不依赖 Android，秒级反馈。
//!
//! 真下载与文件替换在 GlossaryDownload.kt（需要 Context），这里只验判定逻辑。
package org.qingjian.speech.learning.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryInstallTest {
    private val sha = "a".repeat(64)
    private val asset = GlossaryAsset("glossary-en.qj", sha)

    @Test
    fun replacesWhenNothingIsInstalled() {
        assertTrue(GlossaryInstall.needsReplace(null, asset))
    }

    @Test
    fun keepsTheInstalledTableWhenTheHashMatches() {
        // 哈希一致就不重下：每次启动拉 15MB 是浪费
        assertFalse(GlossaryInstall.needsReplace(sha, asset))
    }

    @Test
    fun replacesWhenTheInstalledHashDiffers() {
        assertTrue(GlossaryInstall.needsReplace("b".repeat(64), asset))
    }

    @Test
    fun acceptsOnlyMatchingLengthAndHash() {
        val bytes = ByteArray(16) { it.toByte() }
        assertEquals(true, GlossaryInstall.accept(bytes, 16, asset) { sha })
        assertEquals(false, GlossaryInstall.accept(bytes, 17, asset) { sha })
        assertEquals(false, GlossaryInstall.accept(bytes, -1, asset) { sha })
        assertEquals(false, GlossaryInstall.accept(bytes, 16, asset) { "c".repeat(64) })
    }

    @Test
    fun writesThroughAStagingName() {
        // 先写临时文件、校验通过再改名：半截文件不能把能用的表冲掉
        assertEquals("glossary-en.qj.download", GlossaryInstall.stagingName("glossary-en.qj"))
    }

    @Test
    fun rejectsUnknownAssetNames() {
        for (name in listOf("dict.qj", "glossary-ja.qj", "glossary-en.qj.bak")) {
            val failed = runCatching { GlossaryAsset(name, sha) }.isFailure
            assertTrue("应当拒绝 $name", failed)
        }
    }

    @Test
    fun rejectsMalformedHashes() {
        for (hash in listOf("", "abc", "A".repeat(64), "a".repeat(63))) {
            val failed = runCatching { GlossaryAsset("glossary-en.qj", hash) }.isFailure
            assertTrue("应当拒绝哈希 $hash", failed)
        }
    }

    @Test
    fun servesBothEnglishTables() {
        // 中→英与英→中各一张，表无关读器共用
        assertEquals(listOf("glossary-en.qj", "glossary-zh.qj"), GlossaryAsset.BUNDLED_NAMES)
        assertTrue(runCatching { GlossaryAsset("glossary-zh.qj", sha) }.isSuccess)
    }
}
