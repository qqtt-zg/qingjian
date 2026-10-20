//! 长回答分段与语言切换；有界合并 PCM，单段英语保持原音频路径。
package org.qingjian.speech

object SpeechPlan {
    fun split(text:String):List<SpeechSegment> {
        require(text.length<=4000)
        val plain=text.replace(Regex("```[\\s\\S]*?```")," ").replace(Regex("[#*_`>]"),"")
        val result=mutableListOf<SpeechSegment>();val current=StringBuilder();var language:Boolean?=null
        fun flush(){val value=current.toString().trim();if(value.isNotEmpty())result+=SpeechSegment(value,language==true);current.clear()}
        for(c in plain) {
            val next=when {c in '\u3400'..'\u9fff'->true;c.isLetter()->false;else->language}
            if(language!=null && next!=null && next!=language)flush()
            if(next!=null)language=next
            current.append(c)
            if(current.length>=350 && (c.isWhitespace()||c in "。！？.!?；;，,"))flush()
            else if(current.length>=450)flush()
        }
        flush();return result
    }
    fun merge(parts:List<Pair<FloatArray,Int>>):Pair<FloatArray,Int> {
        require(parts.isNotEmpty())
        if(parts.size==1)return parts.first()
        val rate=24000;val gap=rate/20
        val lengths=parts.map {(samples,source)->require(source in 8000..96000);(samples.size.toLong()*rate/source).toInt()}
        val count=lengths.sumOf {it.toLong()}+(parts.size-1L)*gap;require(count in 1..rate*600L)
        val output=FloatArray(count.toInt());var offset=0
        for(index in parts.indices) {
            val (samples,source)=parts[index];val size=lengths[index]
            for(i in 0 until size){val at=i.toDouble()*source/rate;val left=at.toInt().coerceAtMost(samples.lastIndex);val right=(left+1).coerceAtMost(samples.lastIndex);val blend=(at-left).toFloat();output[offset+i]=samples[left]*(1-blend)+samples[right]*blend}
            offset+=size+gap
        }
        return output to rate
    }
}
