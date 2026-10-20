//! 同页呈现例句、搭配与用法，英文可独立试听，保留旧版词卡的例句。
package org.qingjian.speech.learning.notes

import android.widget.LinearLayout
import org.json.JSONObject
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.PreviewSpeech

class MaterialsView(private val ui: Views, private val speech: PreviewSpeech) {
    fun render(parent: LinearLayout, card: JSONObject?) {
        parent.removeAllViews();speech.stop();speech.clearBindings()
        if(card==null) return
        if(card.optString("example").isNotBlank()) ui.card(parent) {content ->
            content.addView(ui.caption("例句 · 已保存在手机"))
            val row=ui.row()
            row.addView(ui.text(card.getString("example"),20f).apply {setTextIsSelectable(true)},LinearLayout.LayoutParams(0,-2,1f))
            row.addView(speech.button("material-example",card.getString("example")))
            content.addView(row);content.addView(ui.text(card.optString("translation"),15f,true))
        }
        val pairs=card.optJSONArray("collocations")
        if(pairs!=null && pairs.length()>0) ui.card(parent) {content ->
            content.addView(ui.caption("常用搭配"))
            for(index in 0 until pairs.length()) {
                val item=pairs.getJSONObject(index);val row=ui.row()
                row.addView(ui.column().apply {
                    addView(ui.text(item.getString("phrase"),18f));addView(ui.caption(item.getString("translation")))
                },LinearLayout.LayoutParams(0,-2,1f))
                row.addView(speech.button("material-collocation-$index",item.getString("phrase")));content.addView(row)
            }
        }
        if(card.optString("usage").isNotBlank()) ui.card(parent) {content ->
            content.addView(ui.caption("用法提示"))
            content.addView(ui.text(card.getString("usage")).apply {setTextIsSelectable(true)})
        }
        parent.addView(ui.caption("AI 资料可能有误，请结合语境核实。"))
    }
}
