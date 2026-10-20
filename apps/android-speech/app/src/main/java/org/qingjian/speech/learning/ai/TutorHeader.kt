//! AI 主页复用统一标题栏；词条子页只保留返回与可展开对话的标题。
package org.qingjian.speech.learning.ai

import android.app.Activity
import android.view.View
import org.qingjian.speech.learning.MainHeader
import org.qingjian.speech.learning.Views

object TutorHeader {
    fun create(activity: Activity, home: Boolean, menu: () -> Unit): View {
        val ui = Views(activity)
        if(!home) return ui.header("AI 老师").apply {
            setTitleTextAppearance(activity, android.R.style.TextAppearance_Material_Widget_ActionBar_Title)
            setOnClickListener { menu() }
        }
        return MainHeader.create(activity, "AI 老师", menu = menu)
    }
}
