//! 静态译词表的下载与安装规则：要不要换、什么时候算成功、临时文件叫什么。
//!
//! 纯逻辑放这里，才能被 JVM 单元测试覆盖；读写文件与网络在 GlossaryDownload.kt，
//! 目录位置在 GlossaryFiles.kt。手机只需要两张表，不必下整个数据包。
package org.qingjian.speech.learning.glossary

/** 一个可下载的释义表资产：Release 里的文件名与它的 SHA-256。 */
class GlossaryAsset(val name: String, val sha256: String) {
    init {
        require(name in BUNDLED_NAMES) { "未知的释义表资产：$name" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "释义表哈希格式无效" }
    }

    companion object {
        /** 中→英与英→中两张；表无关读器共用，但发布与校验各自独立。 */
        val BUNDLED_NAMES = listOf("glossary-en.qj", "glossary-zh.qj")
    }
}

/** 下载与安装的判定规则，不碰文件系统。 */
object GlossaryInstall {
    /**
     * 已装好的表要不要换。
     *
     * 哈希相同就不动——每次启动都重下 15MB 是浪费；哈希不同才换，且必须等下载与校验
     * 都成功后才替换，避免半截文件把能用的表冲掉。
     */
    fun needsReplace(installedSha256: String?, asset: GlossaryAsset): Boolean =
        installedSha256 != asset.sha256

    /**
     * 下载完成后是否接受这份字节。
     *
     * 长度必须与 Content-Length 一致（断线时短读），哈希必须与资产声明一致。
     */
    fun accept(bytes: ByteArray, expectedSize: Long, asset: GlossaryAsset, sha256: (ByteArray) -> String): Boolean {
        if (expectedSize < 0 || bytes.size.toLong() != expectedSize) return false
        return sha256(bytes) == asset.sha256
    }

    /** 落盘用的临时名：先写临时文件，校验通过再原子改名。 */
    fun stagingName(name: String): String = "$name.download"
}

/** 语言到资产名的映射：中→英用 glossary-en，英→中用 glossary-zh。 */
object GlossaryNames {
    private val BY_LANGUAGE = mapOf(
        "en" to "glossary-en.qj",
        "en-US" to "glossary-en.qj",
        "en-GB" to "glossary-en.qj",
        "zh" to "glossary-zh.qj",
    )

    fun assetName(language: String): String? = BY_LANGUAGE[language]
}
