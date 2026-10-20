//! 本机学习操作及实际执行状态；区分自动执行、历史草稿与本次新增来源。
package org.qingjian.speech.learning.ai.tools

import org.json.JSONObject

data class Action(
    val id: Long,

    val kind: String,

    val payload: JSONObject,

    val state: String,

    val wordId: Long,

    val automatic: Boolean = false,

    val createdWord: Boolean = false,

    val questionId: Long = 0,
)
