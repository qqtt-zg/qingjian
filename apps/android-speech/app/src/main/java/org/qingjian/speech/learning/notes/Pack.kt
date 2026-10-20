//! 模型学习资料的严格边界；整包通过校验后才允许提交。
package org.qingjian.speech.learning.notes

import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.ai.tools.Arguments

data class Pack(val meaning: String, val example: String, val translation: String,
    val collocations: List<Collocation>, val usage: String) {
    fun json()=JSONObject().put("meaning",meaning).put("example",example).put("translation",translation)
        .put("collocations",JSONArray(collocations.map {JSONObject().put("phrase",it.phrase).put("translation",it.translation)}))
        .put("usage",usage)

    fun merge(before: Snapshot): Pack {
        val old=before.content()
        val oldPairs=old?.optJSONArray("collocations")
        return copy(meaning=before.meaning.ifBlank {meaning},
            example=old?.optString("example").orEmpty().ifBlank {example},
            translation=old?.optString("translation").orEmpty().ifBlank {translation},
            collocations=if(oldPairs!=null && oldPairs.length()>0) (0 until oldPairs.length()).map {
                val item=oldPairs.getJSONObject(it);Collocation(item.getString("phrase"),item.getString("translation"))
            } else collocations,
            usage=old?.optString("usage").orEmpty().ifBlank {usage})
    }

    companion object {
        fun parse(data: JSONObject, english: String): Pack {
            Arguments.fields(data,setOf("meaning","example","translation","collocations","usage"))
            fun chinese(name:String,max:Int):String=Arguments.text(data,name,max).also {
                require(it.any {c->c in '\u3400'..'\u9fff'}) {"$name 缺少中文说明，整包未保存"}
            }
            val example=Arguments.text(data,"example",500)
            require(example.contains(english,true)) {"例句未包含当前英文，整包未保存"}
            val pairs=data.opt("collocations")
            require(pairs is JSONArray && pairs.length() in 1..3) {"需要 1～3 条常用搭配，整包未保存"}
            val collocations=(0 until pairs.length()).map {
                val item=pairs.getJSONObject(it);Arguments.fields(item,setOf("phrase","translation"))
                val phrase=Arguments.text(item,"phrase",150)
                require(phrase.any {c->c in 'a'..'z' || c in 'A'..'Z'}) {"搭配缺少英文"}
                val translated=Arguments.text(item,"translation",150)
                require(translated.any {c->c in '\u3400'..'\u9fff'}) {"搭配缺少中文"}
                Collocation(phrase,translated)
            }
            return Pack(chinese("meaning",1000),example,chinese("translation",500),collocations,chinese("usage",800))
        }
    }
}
