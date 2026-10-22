//! 释义表的发布信息：版本号、资产地址与哈希。
//!
//! 手机不读仓库里的 `tools/release/data.lock`（它不在 APK 里），所以这里内置一份。
//! **发新数据时同步改这里**：`data-bundle.sh` 写 `data.lock` 之后，把同名的哈希抄过来。
//! 哈希没填时界面说「本版还没配词汇表地址」，不拿空哈希去下载再报校验失败。
//! 地址走公开 Release，免鉴权，不经过电脑配对。
package org.qingjian.speech.learning.glossary

object GlossaryRelease {
    /** 与 `tools/release/data.lock` 的 tag 一致。 */
    const val TAG = "data-v7"

    private const val BASE = "https://github.com/qingjian-team/qingjian/releases/download"

    /**
     * 资产名 → SHA-256，与 `data.lock` 里同名的行逐字一致。
     *
     * 现在还是空的：`data-v7` 发布时没有这两份独立资产，`data.lock` 里也没有对应的行。
     * 下一次 `data-bundle.sh` 发版后把两行哈希抄过来，手机才能校验并安装。
     */
    private val HASHES = mapOf(
        "glossary-en.qj" to "",
        "glossary-zh.qj" to "",
    )

    fun url(name: String): String = "$BASE/$TAG/$name"

    /** 取资产；哈希还没填时返回 null。 */
    fun asset(name: String): GlossaryAsset? {
        val hash = HASHES[name]?.takeIf { it.length == 64 } ?: return null
        return GlossaryAsset(name, hash)
    }

    fun assets(): List<GlossaryAsset> = GlossaryAsset.BUNDLED_NAMES.mapNotNull { asset(it) }

    /** 界面用：本版是否已经配好可下载的词汇表。 */
    fun configured(): Boolean = assets().size == GlossaryAsset.BUNDLED_NAMES.size
}
