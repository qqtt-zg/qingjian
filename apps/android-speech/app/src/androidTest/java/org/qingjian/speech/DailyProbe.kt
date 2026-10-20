//! 每日学习资料的迁移与执行入口；只在隔离设备运行。
package org.qingjian.speech

import android.app.Instrumentation
import org.qingjian.speech.learning.LearningStore

object DailyProbe {
    fun run(test: Instrumentation): String {
        val db = LearningStore.get(test.targetContext).readableDatabase
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }
        check("study_plans" in tables && "study_questions" in tables && "study_reviews" in tables) { "每日计划与分项复习资料尚未持久保存" }
        return "PASS daily learning persistence tables\n" + DailyStoreProbe.run(test.targetContext) + "\n" + DailyAgentProbe.run(test)
    }
}
