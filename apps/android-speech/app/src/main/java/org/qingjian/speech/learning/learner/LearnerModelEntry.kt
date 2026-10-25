//! 学习者能力模型实体：记录词汇在各维度上的掌握状态、连胜数与到期排程。
package org.qingjian.speech.learning.learner

data class LearnerModelEntry(
    val wordId: Long,
    val dimension: String,                // "spelling", "listening", "meaning", "pronunciation"
    val state: String,                    // "unknown", "learning", "shaky", "solid"
    val attempts: Int = 0,                // 有效作答总数
    val correct: Int = 0,                 // 正确总数
    val assisted: Int = 0,                // 辅助作答数
    val independentAttempts: Int = 0,     // 独立作答数 (assisted == false)
    val independentCorrect: Int = 0,      // 独立正确数 (assisted == false && correct == true)
    val streak: Int = 0,                  // 跨日独立成功连胜数
    val lastSuccessDay: String? = null,   // 上次独立成功日 "yyyy-MM-dd"
    val lastAt: Long? = null,             // 最后有效作答时间戳 (UTC ms)
    val nextDue: Long? = null,            // 下次复习到期时间戳 (UTC ms)
    val score: Double? = null,            // 最近有效打分
    val origin: String = "phone",         // "phone", "desktop", "both"
    val ruleVersion: Int = 1              // 规则版本
)
