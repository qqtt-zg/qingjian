//! 仅用于无个人数据的隔离模拟器：构造旧数据库，确认升级增加练习表且保留原资料。
package org.qingjian.speech

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind

object MigrationProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        app.deleteDatabase("learning.db")
        val path = app.getDatabasePath("learning.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE words (id INTEGER PRIMARY KEY, lookup TEXT NOT NULL UNIQUE, english TEXT NOT NULL, meaning TEXT NOT NULL DEFAULT '', received INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, reviewed INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, topic TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL)")
            db.execSQL("CREATE INDEX messages_topic ON messages(topic,id)")
            db.execSQL("INSERT INTO words VALUES(1,'control','Control','控制',7,4,2,100)")
            db.execSQL("INSERT INTO messages VALUES(1,'word:1','assistant','旧版已保存的解释','done')")
            db.version = 1
        }
        val prefs = app.getSharedPreferences("speech", 0)
        prefs.edit().putInt("voice", 7).putInt("rate_percent", 125).commit()
        val store = LearningStore.get(app)
        check(store.readableDatabase.version == 2)
        val word = checkNotNull(store.word(1))
        check(word.english == "Control" && word.meaning == "控制" && word.received == 7 && word.played == 4 && word.reviewed == 2)
        check(store.messages("word:1").single().text == "旧版已保存的解释")
        check(PracticeStore(app).summary(Kind.CHOICE).first == 0)
        check(prefs.getInt("voice", -1) == 7 && prefs.getInt("rate_percent", -1) == 125)
        return "PASS schema v1 to v2: words, notes, counters, chat and speech preferences retained; empty practice tables added"
    }
}
