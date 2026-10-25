//! 仅用于隔离模拟器：验证 v1～v12 升级到 v13 保留词汇、笔记、旧录音与会话目录，并就绪模型投影表与意图表。
package org.qingjian.speech

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind

object MigrationProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        for(version in 1..12) {
            app.deleteDatabase("learning.db")
            val path = app.getDatabasePath("learning.db"); path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE words (id INTEGER PRIMARY KEY ${if(version>=5) "AUTOINCREMENT" else ""}, lookup TEXT NOT NULL UNIQUE, english TEXT NOT NULL, meaning TEXT NOT NULL DEFAULT '' ${if(version>=12) ", gloss_origin TEXT NOT NULL DEFAULT 'unknown'" else ""}, received INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, reviewed INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY ${if(version >= 3) "AUTOINCREMENT" else ""}, topic TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL ${if(version >= 4) ",reply_to INTEGER" else ""})")
                db.execSQL("CREATE INDEX messages_topic ON messages(topic,id)")
                db.execSQL("INSERT INTO words VALUES(1,'control','Control','控制',7,4,2,100)")
                db.execSQL("INSERT INTO messages(id,topic,role,body,state) VALUES(1,'word:1','assistant','旧版已保存的解释','done')")
                if(version >= 2) {
                    PracticeStore.createTables(db)
                    if(version<9)db.execSQL("ALTER TABLE oral_recordings DROP COLUMN reference_text")
                    if(version<9)db.execSQL("ALTER TABLE oral_recordings DROP COLUMN target_mode")
                    db.execSQL("INSERT INTO oral_recordings(word_id,english,filename,duration,rating,created) VALUES(1,'Control','legacy.wav',1000,'again',100)")
                    db.execSQL("INSERT INTO quiz_attempts(question_key,word_id,english,meaning,kind,answer,correct,created) VALUES('old-key',1,'Control','控制','CHOICE','控制',1,100)")
                }
                if(version == 3) org.qingjian.speech.learning.ai.tools.ActionStore.createTables(db)
                if(version >= 4) {
                    if(version>=5) org.qingjian.speech.learning.ai.tools.ActionStore.createTables(db) else
                    db.execSQL("CREATE TABLE learning_actions (id INTEGER PRIMARY KEY AUTOINCREMENT,topic TEXT NOT NULL,question_id INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,word_id INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,UNIQUE(question_id,kind,fingerprint))")
                    org.qingjian.speech.learning.ai.memory.Conversations.createTables(db)
                }
                if(version>=6) org.qingjian.speech.learning.daily.PlanStore.createTables(db)
                if(version>=7) {
                    org.qingjian.speech.learning.notes.Store.createRevision(db)
                    db.execSQL("UPDATE words SET note_revision=3 WHERE id=1")
                }
                if(version>=9)org.qingjian.speech.learning.assessment.Store.createTables(db)
                if(version>=10)org.qingjian.speech.learning.archive.Store.create(db)
                db.version = version
                if(version>=8)org.qingjian.speech.learning.notes.Store.createMaterials(db)
            }
            val prefs = app.getSharedPreferences("speech", 0)
            prefs.edit().putInt("voice", 7).putInt("rate_percent", 125).commit()
            val store = LearningStore.get(app)
            check(store.readableDatabase.version == 13)
            if(version>=2)check(PracticeStore(app).recordings(1).single().let {it.reference=="Control" && it.mode=="word" && it.rating=="again"})
            check(org.qingjian.speech.learning.assessment.Store(app).facts().getJSONArray("recent_assessments").length()==0)
            val notes=org.qingjian.speech.learning.notes.Store(app)
            val previousRevision=if(version>=7)3L else 0L
            check(notes.snapshot(1)!!.revision==previousRevision)
            store.setMeaning(1,"控制")
            check(notes.snapshot(1)!!.revision==previousRevision+1)
            check(notes.card(1)==null)
            check(org.qingjian.speech.learning.ai.memory.Conversations(app).list(1).single().topic == "word:1")
            check(org.qingjian.speech.learning.daily.PlanStore(app).current()==null)
            val word = checkNotNull(store.word(1))
            check(word.english == "Control" && word.meaning == "控制" && word.received == 7 && word.played == 4 && word.reviewed == 2)
            check(word.glossOrigin == "unknown")
            check(store.messages("word:1").single().text == "旧版已保存的解释")
            check(PracticeStore(app).summary(Kind.CHOICE).first == if(version >= 2) 1 else 0)
            check(prefs.getInt("voice", -1) == 7 && prefs.getInt("rate_percent", -1) == 125)
            val intentCols = store.readableDatabase.rawQuery("PRAGMA table_info(study_intents)", null).use { c ->
                buildSet { while(c.moveToNext()) add(c.getString(1)) }
            }
            check("intent_key" in intentCols && "english" in intentCols)
            val previous = store.question("general", "旧问题")
            store.clearMessages("general")
            val next = store.question("general", "新问题")
            check(next > previous)
            store.answer("general", previous, "晚到回答")
            check(store.messages("general").single().text == "新问题")
            val deletedWord = store.addWord("temporary")
            store.writableDatabase.delete("words", "id=?", arrayOf(deletedWord.toString()))
            check(store.addWord("new-word") > deletedWord)
            store.close()
        }
        return "PASS schema v1-v12 to v13: words/notes/counters/chats/preferences/quiz retained, legacy recording reference/mode backfilled without fabricated assessments, material table and note revision retained, monotonic IDs, v12 gloss_origin ready, v13 study_intents ready"
    }
}
