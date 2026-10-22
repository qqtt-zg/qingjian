//! 释义表的更新通道：版本号、资产地址与哈希。
//!
//! 释义表随 APK 打包（`assets/glossaries/`），装完就能离线查词；这里是**可选**的
//! 更新通道，只有配置了资产哈希才会启用。个人版不发布独立数据资产，所以默认关闭：
//! `HASHES` 为空时设置页只说明「已是最新」，不发起任何网络请求。
//!
//! 若要启用：`data-bundle.sh` 发出带独立释义表资产的 `data-vN` 后，把 tag 与
//! 两个 SHA-256 抄到这里即可；下载走公开 Release、免鉴权，不经过电脑配对。
package org.qingjian.speech.learning.glossary

object GlossaryRelease {
    /** 与 `tools/release/data.lock` 的 tag 一致；未启用更新通道时不会被用到。 */
    const val TAG = "data-v7"

    private const val BASE = "https://github.com/qingjian-team/qingjian/releases/download"

    /** 资产名 → SHA-256。留空表示不启用更新通道。 */
    private val HASHES = mapOf(
        "glossary-en.qj" to "",
        "glossary-zh.qj" to "",
    )

    fun url(name: String): String = "$BASE/$TAG/$name"

    /** 取资产；哈希没填时返回 null。 */
    fun asset(name: String): GlossaryAsset? {
        val hash = HASHES[name]?.takeIf { it.length == 64 } ?: return null
        return GlossaryAsset(name, hash)
    }

    fun assets(): List<GlossaryAsset> = GlossaryAsset.BUNDLED_NAMES.mapNotNull { asset(it) }

    /** 更新通道是否启用。未启用不影响随包表使用。 */
    fun configured(): Boolean = assets().size == GlossaryAsset.BUNDLED_NAMES.size
}
