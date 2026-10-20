//! 有界 XML 结果解析；禁用外部声明，拒绝无效录音，保留实际词级错误。
package org.qingjian.speech.learning.assessment

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object Parser {
    fun parse(xml: String): Result {
        require(xml.length in 1..262144 && !xml.contains("<!DOCTYPE",true) && !xml.contains("<!ENTITY",true)) {"评测响应格式无效"}
        val parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);parser.setInput(StringReader(xml))
        var total:Double?=null;var accuracy:Double?=null;var fluency:Double?=null;var complete:Double?=null
        val words=mutableListOf<WordResult>();var current=-1;var targetDepth=-1
        fun attr(name:String)=parser.getAttributeValue(null,name).orEmpty()
        fun score(name:String):Double? {val raw=attr(name);if(raw.isEmpty())return null;return raw.toDoubleOrNull()?.also {require(it.isFinite()&&it in 0.0..100.0)} ?: error("评测分数格式无效")}
        while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
            if(parser.eventType==XmlPullParser.START_TAG) {
                if(attr("is_rejected").lowercase() in listOf("true","1"))error("录音未通过评测，请检查是否按目标文本朗读、音量和环境后重录")
                if(parser.name in listOf("read_word","read_sentence","read_chapter") && total==null) {
                    total=score("total_score");accuracy=score("accuracy_score");fluency=score("fluency_score");complete=score("integrity_score");targetDepth=parser.depth
                }
                if(parser.name=="word" && targetDepth>=0 && parser.depth>targetDepth) {
                    require(words.size<500)
                    val flag=attr("dp_message").toIntOrNull() ?: 0
                    val issue=when {flag and 16!=0->"漏读";flag and 32!=0->"增读";flag and 64!=0->"重复朗读";flag and 128!=0->"替换朗读";else->""}
                    words+=WordResult(attr("content").take(500),score("total_score"),issue,emptyList());current=words.lastIndex
                }
                if(parser.name=="syll" && current>=0 && attr("serr_msg").toIntOrNull() in listOf(1,2049)) {
                    words[current]=words[current].copy(issue=words[current].issue.ifBlank {"音节或重音需要练习"})
                }
                if(parser.name=="phone" && current>=0 && attr("dp_message").toIntOrNull()?.let {it!=0}==true) {
                    val old=words[current];words[current]=old.copy(phones=(old.phones+attr("content").take(100)).take(100))
                }
            } else if(parser.eventType==XmlPullParser.END_TAG && parser.name=="word")current=-1
            parser.next()
        }
        return Result(checkNotNull(total){"评测未返回有效分数，请重录或稍后重试"},accuracy,fluency,complete,words.filter {it.text.isNotBlank()})
    }
}
