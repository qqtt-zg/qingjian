//! Kokoro 同一模型内的中文音色；与英语音色独立选择，不增加模型权重。
package org.qingjian.speech

import android.content.Context

object ChineseVoices {
    val labels=arrayOf("晓贝 · 女声","晓妮 · 女声","晓晓 · 女声","晓伊 · 女声","云健 · 男声","云希 · 男声","云夏 · 男声","云扬 · 男声")
    fun index(context:Context)=context.getSharedPreferences("speech",0).getInt("chinese_voice",0).coerceIn(labels.indices)
    fun speaker(context:Context)=45+index(context)
    fun label(context:Context)="中文 · "+labels[index(context)]
    fun save(context:Context,index:Int){require(index in labels.indices);context.getSharedPreferences("speech",0).edit().putInt("chinese_voice",index).apply()}
}
