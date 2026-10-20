//! 日学习各步骤的原生内容；固定底部主行动，练习即时反馈，小测结束后统一揭晓。
package org.qingjian.speech.learning.daily

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.PreviewSpeech

class StudyViews(private val activity: Activity, private val plans: PlanStore, private val speech: PreviewSpeech,
    private val action: (String,String) -> Unit) {
    private val ui=Views(activity)
    private fun button(foot: LinearLayout,label: String,code: String,value: String="") = ui.button(label,true) {action(code,value)}.also {it.id=R.id.dailyPrimary;foot.addView(it)}
    private fun ask(body: LinearLayout,id: Long) {body.addView(ui.button("没太明白，问问老师") {action("ask",id.toString())})}

    fun show(p: Plan,body: LinearLayout,foot: LinearLayout,revealed: Boolean) {
        val items=plans.items(p.id)
        when(p.phase) {
            "LEARN" -> {
                val rows=items.filter {it.purpose=="LEARN"};val item=rows[p.cursor]
                body.addView(ui.caption("认识词与搭配 · ${p.cursor+1} / ${rows.size}"))
                body.addView(ui.caption(p.reason));body.addView(ui.word(item.english));body.addView(speech.button("word:${item.id}",item.english))
                body.addView(ui.text(item.meaning,21f));ui.card(body) {card->card.addView(ui.text(item.example,19f));card.addView(ui.caption(item.translation));card.addView(speech.button("example:${item.id}",item.example))}
                body.addView(speech.label());ask(body,item.wordId)
                body.addView(ui.button("调整后续小测") {action("adjust","")})
                button(foot,if(p.cursor+1<rows.size)"下一词" else "开始练习","learn",item.id.toString())
                foot.addView(ui.caption("看过不等于掌握，接下来通过练习检验。"))
            }
            "PRACTICE","EXAM" -> question(p,body,foot)
            "ADAPT" -> {
                body.addView(ui.title("根据刚才的表现，\n安排下一步。"));body.addView(ui.text("老师会读取实际作答与提示使用情况，选择小测重点和难度。",14f,true))
                button(foot,"让老师安排下一步","adapt")
                foot.addView(ui.button("离线，按原计划继续") {action("skip","")})
            }
            "EXAM_INTRO" -> {
                body.addView(ui.title("不看提示，\n再试一小轮。"));body.addView(ui.text("${plans.questions(p.id,"EXAM").size} 道题，全部完成后统一看讲解。听辨题可以重播；使用文字辅助会单独注明。",15f,true))
                if(p.adjustmentReason.isNotBlank())ui.card(body) {it.addView(ui.caption("老师根据本轮资料调整"));it.addView(ui.text(p.adjustmentReason))}
                body.addView(ui.button("调整后续小测") {action("adjust","")})
                body.addView(ui.caption("随时返回，题目和当前选择会保留。"));button(foot,"开始独立小测","next")
            }
            "RESULT" -> {
                results(p,body,true);button(foot,if(items.any {it.purpose=="REVIEW"})"接着复习旧词" else "生成今日回顾","next")
            }
            "REVIEW" -> {
                val rows=items.filter {it.purpose=="REVIEW"};val item=rows[p.cursor]
                body.addView(ui.caption("回忆旧词 · ${p.cursor+1} / ${rows.size}"));body.addView(ui.word(item.english));body.addView(ui.caption("先想一想常用意思，再看答案。"))
                if(!revealed)button(foot,"查看答案","reveal") else {
                    body.addView(ui.text(item.meaning,21f));body.addView(ui.text(item.example));body.addView(ui.caption(item.translation));body.addView(speech.button("review:${item.id}",item.english));body.addView(speech.label());ask(body,item.wordId)
                    foot.addView(ui.button("还没想起来") {action("rate","no")});button(foot,"这次想起来了","rate","yes");foot.addView(ui.caption("这是记忆自评，不是考试成绩。"))
                }
            }
            "SUMMARY","DONE" -> {
                results(p,body,false)
                if(p.reflection.isNotBlank())ui.card(body) {it.addView(ui.caption("AI 回顾与建议 · 可能有误，分数以本机记录为准"));it.addView(ui.text(p.reflection))}
                else body.addView(ui.button("生成 AI 回顾") {action("summary","")})
                button(foot,"今天先到这里","finish")
            }
        }
    }

    private fun question(p: Plan,body: LinearLayout,foot: LinearLayout) {
        val rows=plans.questions(p.id,p.phase);val q=rows[p.cursor];val question=q.question;val exam=p.phase=="EXAM"
        body.addView(ui.caption("${if(exam)"独立小测" else "带提示练习"} · ${p.cursor+1} / ${rows.size}"))
        body.addView(ui.caption(if(exam)"完成后统一看答案" else "选一个答案，马上看讲解"))
        val prompt=when {
            q.listening -> "听一个英文词，选择它的常用意思。"
            question.kind==Kind.CHOICE -> "${question.english} 的常用意思是什么？"
            question.kind==Kind.CLOZE -> "按本课例句填空：\n${question.prompt}"
            question.kind==Kind.SPELLING -> "请写出表示「${question.meaning}」的本课英文。"
            else -> "哪个英文表示「${question.meaning}」？"
        }
        body.addView(ui.text(prompt,20f))
        if(q.listening) {
            val replayCount=ui.caption("已请求播放 ${q.replays} 次；重播次数不作独立正确的依据。")
            body.addView(ui.button("播放英文题目") {
                val key="listen:${question.key}"
                if(!speech.isActive(key))plans.replay(p.id)
                speech.toggle(key,question.english)
                replayCount.text="已请求播放 ${plans.questions(p.id,p.phase)[p.cursor].replays} 次；再点播放可停止。"
            })
            body.addView(speech.label(maskText=true));body.addView(replayCount)
            if(q.assisted)body.addView(ui.text("文字辅助：${question.english}"))
            else body.addView(ui.button("音频不可用，显示文字辅助") {action("hint","")})
        }
        val pending=q.answer.isEmpty()
        val primary=button(foot,if(!exam&&!pending)"继续" else if(question.kind==Kind.SPELLING&&!exam)"检查答案" else if(exam&&p.cursor+1==rows.size)"完成小测" else if(exam)"下一题" else "选择一个答案",if(!pending&&!exam)"next" else "submit")
        primary.isEnabled=!pending||q.draft.isNotBlank()
        if(question.kind==Kind.SPELLING) {
            val input=ui.input("填写英文",q.draft).apply {contentDescription="每日拼写答案";isEnabled=pending}
            input.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int)=Unit
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {if(pending){runCatching {plans.draft(p.id,s.toString().take(500),question.key)};primary.isEnabled=!s.isNullOrBlank()}}
                override fun afterTextChanged(s: Editable?)=Unit
            });body.addView(input)
        } else for(option in question.options) {
            val row=ui.row();val optionButton=ui.button(option) {action("choose",option)}.apply {
                contentDescription="每日选项：$option";isEnabled=pending
                val correct=!exam&&!pending&&question.correct(option)
                val selected=(if(pending)q.draft else q.answer)==option
                if(correct||selected)background=ui.shape(ui.color(if(correct||pending)R.color.chip_background_active else R.color.warm_background),12,true)
            }
            row.addView(optionButton,LinearLayout.LayoutParams(0,-2,1f))
            if(!exam&&question.englishOptions)row.addView(speech.button("option:${question.key}:$option",option))
            body.addView(row)
        }
        if(!exam) {
            if(pending)body.addView(ui.button(if(q.assisted)"提示已展开" else "给我一点提示") {action("hint","")})
            if(q.assisted)body.addView(ui.text("本课提示：${question.english} · ${question.meaning}",13f,true))
            if(!pending)ui.card(body) {card->card.addView(ui.text(if(q.correct)"这次答对了。" else "先记住本课的答案。",18f));card.addView(ui.text(if(question.kind==Kind.CHOICE)question.meaning else question.english));card.addView(ui.caption("按本课资料核对，不表示其他语境没有合理表达。"))}
            body.addView(speech.label());ask(body,question.wordId)
        }
        foot.addView(ui.caption(if(exam)"小测只记录本次作答，不代表已掌握。" else "提示使用与独立小测分别记录。"))
    }

    private fun results(p: Plan,body: LinearLayout,showAnswers: Boolean) {
        val r=plans.results(p.id)
        body.addView(ui.title(if(showAnswers)"看看这次的表现。" else "今天，积累了一点。"))
        val items=plans.items(p.id)
        ui.card(body) {card->
            card.background=ui.shape(ui.color(R.color.chip_background),20)
            val values=listOf("${r.getInt("independent_correct")} / ${r.getInt("submitted")}" to "小测独立答对","${r.getInt("assisted")}" to "使用文字辅助","${items.count {it.learned}}" to "已看完学习词","${items.count {it.rating.isNotBlank()}}" to "已自评复习词")
            for(pair in values.chunked(2))card.addView(ui.row().apply {pair.forEach {(value,label)->
                addView(ui.column().apply {addView(ui.word(value).apply {textSize=29f});addView(ui.caption(label))},LinearLayout.LayoutParams(0,-2,1f))
            }})
        }
        body.addView(ui.caption("本次答对 ${r.getInt("correct")} 题。提示、自评与独立成绩分别记录，不等于掌握程度。"))
        if(showAnswers) plans.questions(p.id,"EXAM").filter {it.answer.isNotBlank()}.forEach {q->ui.card(body) {card->
            card.addView(ui.text("${q.question.english} · ${if(q.correct)"本题答对" else "需要巩固"}${if(q.assisted)" · 使用辅助" else ""}"))
            card.addView(ui.caption(q.question.meaning));card.addView(ui.caption("你的答案：${q.answer}"));card.addView(speech.button("result:${q.question.key}",q.question.english))
        } }
        items.filter {it.rating=="again"}.takeIf {it.isNotEmpty()}?.let {body.addView(ui.text("自评还需巩固：${it.joinToString("、") {w->w.english}}",14f,true))}
        if(showAnswers)body.addView(speech.label())
    }
}
