//! 盲写草稿与辅助标记持久化，查看答案后再答对仍计辅助作答。
package org.qingjian.speech.learning.spelling

import android.content.Context
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.quiz.Question
import java.util.UUID

class Session(context:Context,val wordId:Long) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("spelling_drafts",0)
    private var data=runCatching {JSONObject(prefs.getString(wordId.toString(),"{}")!!)}.getOrDefault(JSONObject())
    val english get()=LearningStore.get(app).word(wordId)?.english.orEmpty()
    var draft:String get()=data.optString("draft");set(value){data.put("draft",value.take(500));save()}
    val assisted get()=data.optInt("hint")>0
    val hint get()=data.optInt("hint")
    val submitted get()=data.optBoolean("submitted")
    val correct get()=data.optBoolean("correct")
    init {if(data.optString("english")!=english)reset()}
    fun reset(){data=JSONObject().put("english",english).put("key",UUID.randomUUID().toString());save()}
    fun assist(level:Int){data.put("hint",maxOf(hint,level));save()}
    fun submit(){
        require(!submitted&&draft.isNotBlank()&&english.isNotBlank())
        val valid=Question.normalize(draft)==Question.normalize(english)
        org.qingjian.speech.learning.archive.Store(app).event(wordId,english,"拼写",JSONObject().put("answer",draft).put("correct",valid).put("assisted",assisted).put("hint",hint),data.getString("key"))
        data.put("submitted",true).put("correct",valid);save()
    }
    private fun save(){prefs.edit().putString(wordId.toString(),data.toString()).apply()}
}
