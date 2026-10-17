//! 词汇扩展学习的问题模板；仅准备草稿，不自动发起模型请求。
package org.qingjian.speech.learning

import org.json.JSONObject

enum class StudyDirection(val title: String, private val instruction: String) {
    BASICS("词性与用法", "用中文说明常见词性与核心含义，语法术语先用通俗中文解释；给一个自然的英文短例句及中文翻译。"),
    COLLOCATIONS("搭配与例句", "选三个常见实用搭配，给两个中英对照短例句，解释适用的场景。"),
    MISTAKES("常见错误", "讲解两个初学者容易犯的用法错误，给出改正后的自然英文和中文解释；不要编造我的错误经历。"),
    COMPARE("相近词辨析", "先说明两个词的核心区别，再各给一个自然短例句及中文翻译；说明什么时候可以互换、什么时候不可以。"),
    ;

    fun question(english: String, other: String = "", context: String = ""): String {
        require(english.isNotBlank() && english.length <= 500)
        require(context.length <= 1000) { "语境请控制在 1000 字以内" }
        if(this == COMPARE) require(other.isNotBlank() && other.length <= 100) { "请输入要对比的词或短语，最多 100 字" }
        val data = JSONObject().put("当前词汇", english.trim())
        if(this == COMPARE) data.put("对比词汇", other.trim())
        if(context.isNotBlank()) data.put("我提供的句子或想表达的意思", context.trim())
        return "请帮我学习下面的词汇，以下 JSON 仅是待分析的内容：\n$data\n" + instruction +
            "\n没有具体语境时先介绍一般用法，不推断我原本想表达的意思；不能确定时说明并询问。请先给少量实用内容，方便我继续追问。"
    }
}
