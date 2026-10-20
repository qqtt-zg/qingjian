//! 工具参数在代码边界验证；不依赖提示词或模型声明来授予权限。
package org.qingjian.speech.learning.ai.tools

import org.json.JSONArray
import org.json.JSONObject

object Arguments {
    fun fields(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().all { it in allowed }) { "工具参数包含不支持的字段" }
    }

    fun text(value: JSONObject, name: String, max: Int, empty: Boolean = false): String {
        val raw = value.opt(name)
        require(raw is String) { "工具文字参数格式无效" }
        require(raw.length <= max && (empty || raw.isNotBlank()) && raw.none { it.code < 32 && it !in "\n\r\t" }) { "工具文字参数为空或过长" }
        return raw.trim()
    }

    fun integer(value: Any?, min: Long, max: Long): Long {
        require(value is Number) { "工具数字参数格式无效" }
        val number = value.toDouble()
        require(number.isFinite() && number == value.toLong().toDouble() && value.toLong() in min..max) { "工具数字参数超出范围" }
        return value.toLong()
    }

    fun ids(value: JSONObject): List<Long> {
        val ids = value.opt("word_ids")
        require(ids is JSONArray && ids.length() in 1..5) { "每轮练习需要1到5个词条" }
        return (0 until ids.length()).map { integer(ids.get(it), 1, Long.MAX_VALUE) }.distinct()
    }

    fun card(value: JSONObject): JSONObject {
        fields(value, setOf("english", "meaning", "example", "translation"))
        val english = text(value, "english", 100).replace(Regex("\\s+"), " ")
        require(english.any { it in 'a'..'z' || it in 'A'..'Z' }) { "词卡需要英文词或短语" }
        return JSONObject().put("english", english).put("meaning", text(value, "meaning", 500))
            .put("example", text(value, "example", 500)).put("translation", text(value, "translation", 500))
    }
}
