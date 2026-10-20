//! 仅记录服务商实际返回的用量；缺失字段为未知，缓存不重复累计。
package org.qingjian.speech.learning.archive

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject

class Usage(context:Context) {
    private val store=Store(context)
    fun record(task:String,value:JSONObject){store.db.insertOrThrow("ai_usage",null,ContentValues().apply {put("task",task.take(100));put("payload",value.toString());put("created",System.currentTimeMillis())})}
    fun rows():List<Pair<String,JSONObject>> = store.db.rawQuery("SELECT task,payload FROM ai_usage ORDER BY id DESC",null).use {c->buildList {while(c.moveToNext())add(c.getString(0) to JSONObject(c.getString(1)))}}
    companion object {
        @Volatile var recorder:((String,JSONObject)->Unit)?=null
        fun capture(task:String,value:JSONObject){runCatching {recorder?.invoke(task,value)}}
    }
}
