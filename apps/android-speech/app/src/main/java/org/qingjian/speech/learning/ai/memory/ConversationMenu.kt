//! AI 页内的会话切换与可查看、可清除的摘要；不额外增加全局设置入口。
package org.qingjian.speech.learning.ai.memory

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

class ConversationMenu(private val activity: Activity, private val ui: Views, private val topic: String, private val wordId: Long, private val busy: Boolean, private val open: (String) -> Unit) {
    private val conversations = Conversations(activity)

    fun show(): Dialog {
        val dialog = Dialog(activity)
        val column = ui.column().apply { setPadding(ui.dp(18), ui.dp(22), ui.dp(18), ui.dp(20)); setBackgroundColor(ui.color(R.color.screen_background)) }
        column.addView(ui.title("学习对话"))
        column.addView(ui.button("新建学习对话", true) { val next = conversations.create(wordId); dialog.dismiss(); open(next.topic) }.apply { isEnabled = !busy })
        column.addView(ui.caption("最近的对话"))
        val list = ui.column()
        conversations.list(wordId).forEach { value ->
            list.addView(ui.button((if(value.topic == topic) "✓ " else "") + value.title) {
                dialog.dismiss(); if(value.topic != topic) open(value.topic)
            }.apply {
                contentDescription = "切换对话：${value.title}"; isEnabled = !busy; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
        }
        column.addView(ui.scroll(list), LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(ui.caption(if(busy) "回答完成后可切换对话。" else "当前：${conversations.get(topic)?.title.orEmpty()}"))
        column.addView(ui.button("关闭对话列表") { dialog.dismiss() })
        dialog.setContentView(column); dialog.setCanceledOnTouchOutside(true); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(ui.color(R.color.screen_background))); setGravity(Gravity.START)
            setLayout(minOf(ui.dp(320), (activity.resources.displayMetrics.widthPixels * .86).toInt()), -1)
        }
        return dialog
    }

    fun settings(column: LinearLayout) {
        column.addView(ui.caption("每段对话单独保存。发送时会附带学习偏好、当前词、相关词汇/错题、近期问答及本会话摘要；原文保留在手机。"))
        column.addView(Switch(activity).apply {
            text = "长对话自动摘要"; textSize = 15f; isChecked = conversations.automatic; isEnabled = !busy
            setOnCheckedChangeListener { _, checked -> conversations.automatic = checked }
        })
        column.addView(ui.caption("开启后，一次发送最多额外请求一次 AI 整理旧对话，可能增加用量。摘要可能遗漏或有误；关闭后仅发送近期问答，不使用摘要。"))
        column.addView(ui.button("查看会话摘要") {
            val summary = conversations.get(topic)?.summary.orEmpty()
            AlertDialog.Builder(activity).setTitle("本会话摘要 · AI 生成")
                .setMessage(summary.ifEmpty { "尚未生成摘要。对话较长时，在下一次发送问题时整理。" })
                .setPositiveButton("关闭", null).setNeutralButton("清除摘要") { _, _ -> conversations.forgetSummary(topic) }.show()
        }.apply { isEnabled = !busy })
        column.addView(ui.button("重命名本次对话") {
            val field = ui.input("对话名称", conversations.get(topic)?.title.orEmpty())
            field.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            val dialog = AlertDialog.Builder(activity).setTitle("重命名对话").setView(field)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if(field.text.isBlank()) field.error = "请输入对话名称"
                    else { conversations.rename(topic, field.text.toString()); dialog.dismiss(); open(topic) }
                }
            }
            dialog.show()
        }.apply { isEnabled = !busy })
    }
}
