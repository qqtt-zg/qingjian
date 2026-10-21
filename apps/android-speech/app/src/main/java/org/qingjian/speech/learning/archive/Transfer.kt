//! 配对连接内单向导入电脑生词与可读备份；无连接时排队，不上传凭据与模型。
package org.qingjian.speech.learning.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.Pairing
import org.qingjian.speech.learning.LearningStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object Transfer {
    private val worker=Executors.newSingleThreadExecutor()
    private val running=AtomicBoolean(false)
    private val generation=AtomicLong()
    @Volatile private var active:HttpURLConnection?=null
    fun cancel(){generation.incrementAndGet();active?.disconnect()}
    fun run(context:Context,manual:Boolean=false){
        val app=context.applicationContext;val prefs=app.getSharedPreferences("learning_archive",0)
        if(!running.compareAndSet(false,true))return
        val run=generation.get()
        worker.execute {
            try {
                val speech=app.getSharedPreferences("speech",0)
                require(org.qingjian.speech.learning.ConnectionStatus.from(speech.getString("connection_status","").orEmpty())==org.qingjian.speech.learning.ConnectionStatus.CONNECTED){"等待电脑连接；学习资料仍保存在手机"}
                val pair=Pairing.parse(speech.getString("url","").orEmpty());prefs.edit().putString("transfer_status","正在同步电脑来源…").apply()
                val response=request(pair,"/api/learning/words",run=run)
                val book=LearningStore.get(app);val epoch=org.qingjian.speech.learning.daily.PlanStore(app).epoch();val db=book.writableDatabase
                val rows=response.getJSONArray("words");require(rows.length()<=50000)
                db.beginTransaction();try {
                    for(i in 0 until rows.length()){
                        val row=rows.getJSONObject(i);val english=row.getString("english");require(english.length in 1..500)
                        val existed=db.rawQuery("SELECT 1 FROM words WHERE lookup=?",arrayOf(english.trim().replace(Regex("\\s+")," ").lowercase(java.util.Locale.ROOT))).use {it.moveToFirst()}
                        val id=book.addWord(english);val word=checkNotNull(book.word(id));if(word.meaning.isBlank()&&row.optString("meaning").length<=1000)book.setMeaning(id,row.optString("meaning"))
                        // 桌面的末见是 UTC 午夜的 Unix 秒数（旧的 last 字段按秒数解释）。拿不到就记 0 表示未知：
                        // 写 1 会在「今日接触」这类筛选里变成 1970，写当前时间则是假的上屏时间。
                        if(!existed){val seen=row.optLong("last");val time=if(seen in 1..System.currentTimeMillis()/1000)seen*1000 else 0L;db.execSQL("UPDATE words SET last_seen=? WHERE id=?",arrayOf<Any>(time,id))}
                        db.insertWithOnConflict("desktop_sources",null,ContentValues().apply {put("word_id",id);put("payload",row.toString())},SQLiteDatabase.CONFLICT_REPLACE)
                    };db.setTransactionSuccessful()
                }finally{db.endTransaction()}
                prefs.edit().putString("sync_time",java.text.DateFormat.getDateTimeInstance().format(java.util.Date())).apply()
                val data=export(app);val bytes=data.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=20*1024*1024){"备份超过20MiB，未上传；请减少历史或另行导出"}
                val digest=MessageDigest.getInstance("SHA-256").digest(data.getJSONObject("tables").toString().toByteArray()).joinToString(""){"%02x".format(it)}
                if((manual||prefs.getBoolean("auto_backup",true))&&(manual||prefs.getString("backup_hash","")!=digest)){
                    prefs.edit().putString("transfer_status","正在备份手机学习档案…").apply();request(pair,"/api/learning/backup",bytes,run)
                    require(epoch==org.qingjian.speech.learning.daily.PlanStore(app).epoch()){"学习数据已清空，忽略旧备份回执"}
                    prefs.edit().putString("backup_hash",digest).putString("backup_time",java.text.DateFormat.getDateTimeInstance().format(java.util.Date())).apply()
                }
                // 电脑生词本里日期异常被跳过的词要说出来：静默丢掉的话，用户只看到「0 词」却不知道原因。
                val skipped=response.optInt("invalid_date_rows",0)
                if(generation.get()==run)prefs.edit().putString("transfer_status",if(response.optBoolean("available"))"同步完成 · 电脑来源 ${rows.length()} 词"+(if(skipped>0)"（另有 $skipped 词日期异常已跳过）" else "")+"；手机成绩独立保存" else "学习档案已检查备份；电脑尚未找到生词本文件").apply()
            }catch(error:Exception){if(generation.get()==run)prefs.edit().putString("transfer_status",error.message ?: "同步失败，连接后重试").apply()}
            finally{running.set(false)}
        }
    }
    fun export(context:Context):JSONObject {
        val db=LearningStore.get(context).readableDatabase;val tables=JSONObject()
        db.beginTransaction();try {
            for(table in listOf("words","word_materials","messages","conversations","learning_actions","word_workspaces","oral_recordings","quiz_attempts","oral_assessments","learning_events","independent_exams","learner_memory","ai_usage","study_plans","study_items","study_questions","study_reviews","desktop_sources")){
                val rows=JSONArray();db.rawQuery("SELECT * FROM $table",null).use {c->while(c.moveToNext()) {val row=JSONObject();for(i in 0 until c.columnCount)row.put(c.getColumnName(i),when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL;android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i);android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)});rows.put(row)}};tables.put(table,rows)
            };db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return JSONObject().put("schema",1).put("created",System.currentTimeMillis()).put("tables",tables)
    }
    private fun request(pair:Pairing,path:String,body:ByteArray?=null,run:Long):JSONObject {
        check(generation.get()==run){"连接已停止，等待下一次连接"}
        val connection=URL(pair.base+path).openConnection() as HttpURLConnection
        active=connection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=30000;connection.setRequestProperty("Authorization","Bearer ${pair.token}")
            if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use {it.write(body)}}
            val code=connection.responseCode;require(code==200){if(code==404)"请更新电脑连接工具以支持词库和备份" else "学习同步失败（HTTP $code），手机数据保留"}
            val out=java.io.ByteArrayOutputStream();connection.inputStream.use {stream->val block=ByteArray(8192);while(true){val n=stream.read(block);if(n<0)break;require(out.size()+n<=20*1024*1024);out.write(block,0,n)}}
            check(generation.get()==run){"连接已停止"};return JSONObject(out.toString("UTF-8"))
        }finally{connection.disconnect();if(active===connection)active=null}
    }
}
