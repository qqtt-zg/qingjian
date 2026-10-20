//! 词卡内的可展开区域，保留独立视图与输入框，不因后台状态更新重建。
package org.qingjian.speech.learning.word

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

class Fold(activity: Activity,title: String,private val toggled:(Boolean)->Unit={}) {
    private val ui=Views(activity)
    val root=ui.column()
    val body=ui.column().apply {visibility=View.GONE}
    val header=ui.button(title) {setOpen(!open)}.apply {
        setBackgroundColor(Color.TRANSPARENT);gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0,ui.dp(6),0,ui.dp(6));setCompoundDrawables(null,null,ui.icon(R.drawable.ic_chevron),null)
    }
    val open get()=body.visibility==View.VISIBLE
    init {root.addView(header);ui.divider(root);root.addView(body)}
    fun setOpen(value: Boolean){body.visibility=if(value)View.VISIBLE else View.GONE;header.contentDescription="${header.text}，${if(value)"收起" else "展开"}";toggled(value)}
}
