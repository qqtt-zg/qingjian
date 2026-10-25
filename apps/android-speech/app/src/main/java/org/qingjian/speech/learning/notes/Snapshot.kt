//! 笔记版本快照，用于有条件保存与撤销，避免覆盖并发手动编辑。
package org.qingjian.speech.learning.notes

data class Snapshot(val id: Long, val english: String, val meaning: String, val revision: Long,
    val materials: String = "", val legacy: String = "", val glossOrigin: String = "unknown") {
    fun content(): org.json.JSONObject? = (materials.ifBlank {legacy}).takeIf {it.isNotEmpty()}?.let {org.json.JSONObject(it)}
}
