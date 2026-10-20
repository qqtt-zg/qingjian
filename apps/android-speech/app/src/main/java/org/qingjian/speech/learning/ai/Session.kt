//! 可跨界面旋转保留的单次答疑任务；问题先落盘，失败后仅手动重试。
package org.qingjian.speech.learning.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.qingjian.speech.learning.LearningStore
import java.util.concurrent.Executors

class Session(context: Context, val wordId: Long, conversation: String? = null, private val createClient: () -> Client = { Client() }) {
    private val app = context.applicationContext
    private val store = LearningStore.get(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var client: Client? = null
    @Volatile private var closed = false
    @Volatile private var cancelling = false
    private val conversations = org.qingjian.speech.learning.ai.memory.Conversations(app)
    val topic = conversation ?: if(wordId > 0) "word:$wordId" else "general"
    var busy = false
        private set
    var notice = ""
        private set
    var changed: (() -> Unit)? = null
    var lastQuestionId = 0L
        private set
    var contextDescription = ""
        private set
    val canDispatchPlayback get() = !cancelling && !closed

    fun send(question: String, retryId: Long? = null) {
        if (busy || closed) return
        if(question.isBlank() || question.length > 4000) { notice = "请输入 1～4000 字的问题"; changed?.invoke(); return }
        val snapshot = conversations.ensure(topic, wordId)
        require(snapshot.wordId == wordId)
        // 重试旧的待问时，仍可参考它之后已完成的对话，但不发送本次问题两遍。
        val before = store.readableDatabase.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM messages", null).use { c -> c.moveToFirst(); c.getLong(0) }
        val id = retryId ?: store.question(topic, question.trim())
        lastQuestionId = id
        cancelling = false; busy = true; notice = "正在请求 AI…"; changed?.invoke()
        val request = createClient(); client = request
        executor.execute {
            val message = try {
                val profile = Credentials(app).load()
                val word = if(wordId > 0) store.word(wordId) else null
                val update: (String) -> Unit = { stage -> handler.post { if(!closed && busy && !cancelling) { notice = stage; changed?.invoke() } } }
                val memory = org.qingjian.speech.learning.ai.memory.Memory(conversations, snapshot, id, before)
                val summary = memory.prepare(request, profile, { cancelling || closed }, update)
                val enabled = org.qingjian.speech.learning.ai.tools.ToolSettings.enabled(app)
                val actions = org.json.JSONArray(org.qingjian.speech.learning.ai.tools.ActionStore(app).forTopic(topic).takeLast(8).map {
                    val data = org.json.JSONObject().put("id", it.id).put("kind", it.kind).put("state", it.state).put("word_id", it.wordId)
                    if(it.kind == "prepare_practice") {
                        val questions = it.payload.optJSONArray("questions") ?: org.json.JSONArray()
                        val practice = org.qingjian.speech.learning.practice.PracticeStore(app)
                        val results = (0 until questions.length()).mapNotNull { index ->
                            practice.answer(questions.getJSONObject(index).getString("key"))?.let { answer ->
                                org.json.JSONObject().put("english", answer.english).put("kind", answer.kind.name).put("correct", answer.correct)
                            }
                        }
                        data.put("practice_results", org.json.JSONArray(results)).put("questions", questions.length())
                    }
                    data
                })
                val preferences = org.qingjian.speech.learning.StudyPreferences(app).context()
                val related = org.qingjian.speech.learning.ai.memory.Retrieval(app).find(question)
                related.put("learning_archive",org.qingjian.speech.learning.archive.Store(app).context())
                related.put("pronunciation",org.qingjian.speech.learning.assessment.Store(app).facts(wordId))
                if(wordId>0)related.put("current_word_practice",org.qingjian.speech.learning.word.Practice(app,wordId).facts())
                val context = buildString {
                    append("学习目标：${preferences.optString("基础")}；${preferences.optString("学习用途")}\n")
                    append("期望每天 ${preferences.optInt("期望每天新词")} 个新词、${preferences.optInt("期望每天分钟")} 分钟（目标，不是完成记录）\n")
                    word?.let { append("当前词条：${it.english} · ${it.meaning}\n") }
                    if(summary.isNotBlank()) append("本会话摘要（AI 整理，可能有误）：\n$summary\n")
                    val matching = related.getJSONArray("matching_words")
                    if(matching.length() > 0) append("相关词汇：" + (0 until matching.length()).joinToString("；") { i -> matching.getJSONObject(i).let { "${it.optString("english")} · ${it.optString("user_note")}" } } + "\n")
                    val mistakes = related.getJSONArray("recent_mistakes")
                    if(mistakes.length() > 0) append("相关错题：" + (0 until mistakes.length()).joinToString("、") { i -> mistakes.getJSONObject(i).optString("english") } + "\n")
                    append("听过、待学习与掌握程度分别记录。")
                    val actual = (0 until actions.length()).map { actions.getJSONObject(it) }.filter { it.has("practice_results") }
                    if(actual.isNotEmpty()) {
                        val results = actual.flatMap { item -> item.getJSONArray("practice_results").let { values -> (0 until values.length()).map { values.getJSONObject(it) } } }
                        append("\n本会话近期操作中的实际作答：${results.size} 题，答对 ${results.count { it.optBoolean("correct") }} 题；不是全部历史或掌握程度。")
                    }
                }
                handler.post { if(!closed) { contextDescription = context; changed?.invoke() } }
                val messages = Teaching.messages(word, memory.history(), question, preferences, enabled, actions, summary, related)
                request.task="AI老师 · 答疑"
                val answer = org.qingjian.speech.learning.ai.tools.Runner(app).complete(request, profile, messages, topic, id, { cancelling || closed }, update, enabled)
                if(cancelling || closed) throw java.util.concurrent.CancellationException()
                check(store.answer(topic, id, answer)) { "本次问题已删除或处理，未保存晚到回答" }
                memory.save()
                "回答已保存，可离线查看；AI 解释仍需结合语境核实" + if(memory.fallback) "（本次摘要未更新）" else ""
            } catch(error: Exception) {
                store.failed(id)
                (if(cancelling) "本次请求已取消" else Client.errorMessage(error)) + "；问题已保留，已完成的操作仍可查看，新增词卡可撤销"
            }
            handler.post {
                busy = false; client = null; notice = message
                if(!closed) changed?.invoke()
            }
        }
    }

    fun cancel() {
        if(busy) { cancelling = true; notice = "正在取消本次请求…"; client?.cancel(); changed?.invoke() }
    }

    fun close() {
        closed = true; changed = null; client?.cancel(); executor.shutdown()
    }
}
