//! 配对连接内单向导入电脑生词与可读备份；无连接时排队，不上传凭据与模型。
//!
//! 拉来源、传备份、刷新状态三步各自成败互不阻断：一步失败不能让另外两步不执行，
//! 也不能把「这次没取到」解释成「电脑上的词都没了」。
package org.qingjian.speech.learning.archive

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.Pairing
import org.qingjian.speech.learning.ConnectionStatus
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.glossary.GlossaryStore
import org.qingjian.speech.learning.glossary.PersonalGlosses
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object Transfer {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var active: HttpURLConnection? = null
    private const val LANGUAGE = "en"

    fun cancel() { generation.incrementAndGet(); active?.disconnect() }

    fun run(context: Context, manual: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("learning_archive", 0)
        if (!running.compareAndSet(false, true)) return
        val run = generation.get()
        worker.execute {
            var sourceProblem: String? = null
            var backupProblem: String? = null
            try {
                val speech = app.getSharedPreferences("speech", 0)
                // 老版本只写过 `status`，新版本两处都写；与 ConnectionBar / MainActivity 的回退顺序一致。
                val statusText = speech.getString("connection_status", null) ?: speech.getString("status", "").orEmpty()
                require(ConnectionStatus.from(statusText) == ConnectionStatus.CONNECTED) {
                    "等待电脑连接；学习资料仍保存在手机"
                }
                val pair = Pairing.parse(speech.getString("url", "").orEmpty())
                val epoch = org.qingjian.speech.learning.daily.PlanStore(app).epoch()
                prefs.edit().putString("transfer_status", "正在同步电脑来源…").apply()

                // 第一步：拉来源并写库。失败只影响来源，不动已有数据。
                val pulled = runCatching { pull(app, prefs, pair, run) }
                if (pulled.isFailure) sourceProblem = pulled.exceptionOrNull()?.message ?: "电脑来源同步失败"

                // 第二步：导出并上传备份。失败不影响来源，也不清掉上次备份记录。
                val backup = runCatching { backup(app, prefs, pair, epoch, manual, run) }
                if (backup.isFailure) backupProblem = backup.exceptionOrNull()?.message ?: "学习档案备份失败"

                // 第三步：刷新状态。三段结果拼起来，失败的明确写出来。
                if (generation.get() == run) {
                    val status = statusOf(pulled.getOrNull(), sourceProblem, backupProblem)
                    prefs.edit().putString("transfer_status", status).apply()
                }
            } catch (error: Exception) {
                if (generation.get() == run) {
                    prefs.edit().putString("transfer_status", error.message ?: "同步失败，连接后重试").apply()
                }
            } finally {
                running.set(false)
            }
        }
    }

    /** 拉电脑来源：先试新接口，404 退回旧接口。返回这次的结果，失败时抛异常由调用方兜。 */
    private fun pull(app: Context, prefs: SharedPreferences, pair: Pairing, run: Long): DesktopContext {
        val response = try {
            request(pair, "/api/learning/context", run = run)
        } catch (error: HttpStatusException) {
            // 旧版电脑工具没有这个路由，退回旧接口；别的错误码照常往上抛。
            if (error.status != 404) throw error
            request(pair, "/api/learning/words", run = run)
        }
        val context = if (response.has("schema")) {
            ContextJson.parseContext(response)
        } else {
            ContextJson.parseLegacyWords(response)
        }
        require(context.words.size <= 50000) { "电脑来源词条过多，请先在电脑上清理生词本" }
        val book = LearningStore.get(app)
        val db = book.writableDatabase
        db.beginTransaction()
        try {
            for (word in context.words) {
                val english = word.english
                require(english.length in 1..500)
                val existed = db.rawQuery(
                    "SELECT 1 FROM words WHERE lookup=?",
                    arrayOf(english.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT))
                ).use { it.moveToFirst() }
                val id = book.addWord(english, "desktop-vocab")
                val current = checkNotNull(book.word(id))
                if (current.glossOrigin == "unknown") {
                    book.setGlossOrigin(id, "desktop-vocab")
                }
                if (current.meaning.isBlank() && word.meaning.length <= 1000) book.setMeaning(id, word.meaning)
                // 桌面的末见是 UTC 午夜的日期；解析不出来就记 0 表示未知。
                // 写 1 会在「今日接触」这类筛选里变成 1970，写当前时间则是假的上屏时间。
                if (!existed && word.lastSeenMillis > 0) {
                    db.execSQL("UPDATE words SET last_seen=? WHERE id=?", arrayOf<Any>(word.lastSeenMillis, id))
                }
                db.insertWithOnConflict("desktop_sources", null, ContentValues().apply {
                    put("word_id", id); put("payload", word.payload)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 个人释义表：个人表优先，随包表（还没下载通道，通常为 null）兜底。
        if (context.glosses.isNotEmpty() || context.source == DesktopContext.Source.CONTEXT) {
            GlossaryStore.save(app, LANGUAGE, PersonalGlosses.of(context.glosses, LANGUAGE, GlossaryStore.bundled(app, LANGUAGE)))
        }
        prefs.edit().putString("sync_time", DateFormat.getDateTimeInstance().format(Date()).apply { }).apply()
        return context
    }

    /** 导出手机学习档案并上传。返回是否真的传了（哈希没变就跳过）。 */
    private fun backup(app: Context, prefs: SharedPreferences, pair: Pairing, epoch: Long, manual: Boolean, run: Long): Boolean {
        val data = export(app)
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 20 * 1024 * 1024) { "备份超过20MiB，未上传；请减少历史或另行导出" }
        val digest = MessageDigest.getInstance("SHA-256").digest(data.getJSONObject("tables").toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (!(manual || prefs.getBoolean("auto_backup", true))) return false
        if (!manual && prefs.getString("backup_hash", "") == digest) return false
        prefs.edit().putString("transfer_status", "正在备份手机学习档案…").apply()
        request(pair, "/api/learning/backup", bytes, run)
        require(epoch == org.qingjian.speech.learning.daily.PlanStore(app).epoch()) { "学习数据已清空，忽略旧备份回执" }
        prefs.edit().putString("backup_hash", digest)
            .putString("backup_time", DateFormat.getDateTimeInstance().format(Date())).apply()
        return true
    }

    /** 把三步结果拼成一句状态。失败的部分写出来，成功的部分照常说明。 */
    private fun statusOf(context: DesktopContext?, sourceProblem: String?, backupProblem: String?): String {
        val parts = mutableListOf<String>()
        if (context != null) {
            parts += ContextParse.describeAvailability(context.available, context.words.size)
            ContextParse.describeInvalidDates(context.invalidDateRows)?.let { parts += it }
            ContextParse.describeGlossTruncation(context.glossesTruncated)?.let { parts += it }
            if (context.truncated) parts += "词条较多，只同步了前一部分"
            if (context.source == DesktopContext.Source.LEGACY_WORDS) parts += "电脑工具是旧版，建议更新"
            parts += "手机成绩独立保存"
        }
        if (sourceProblem != null) parts += "电脑来源没取到：$sourceProblem（保留上次数据）"
        if (backupProblem != null) parts += "备份没传成功：$backupProblem"
        return parts.joinToString("；")
    }

    fun export(context: Context): JSONObject {
        val db = LearningStore.get(context).readableDatabase
        val tables = JSONObject()
        db.beginTransaction()
        try {
            for (table in listOf(
                "words", "word_materials", "messages", "conversations", "learning_actions", "word_workspaces",
                "oral_recordings", "quiz_attempts", "oral_assessments", "learning_events", "independent_exams",
                "learner_memory", "ai_usage", "study_plans", "study_items", "study_questions", "study_reviews",
                "desktop_sources", "learner_model", "user_overrides", "study_item_stages", "plan_revisions"
            )) {
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM $table", null).use { c ->
                    while (c.moveToNext()) {
                        val row = JSONObject()
                        for (i in 0 until c.columnCount) row.put(c.getColumnName(i), when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        })
                        rows.put(row)
                    }
                }
                tables.put(table, rows)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return JSONObject().put("schema", 1).put("created", System.currentTimeMillis()).put("tables", tables)
    }

    /** 非 200 的状态码要能区分 404 与其它，回退逻辑才写得出来。 */
    private class HttpStatusException(val status: Int, message: String) : Exception(message)

    private fun request(pair: Pairing, path: String, body: ByteArray? = null, run: Long): JSONObject {
        check(generation.get() == run) { "连接已停止，等待下一次连接" }
        val connection = URL(pair.base + path).openConnection() as HttpURLConnection
        active = connection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer ${pair.token}")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code != 200) {
                val hint = when (code) {
                    401, 403 -> "配对地址无效或已过期，请重新扫码"
                    404 -> "电脑工具版本过旧"
                    else -> "学习同步失败（HTTP $code），手机数据保留"
                }
                throw HttpStatusException(code, hint)
            }
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val block = ByteArray(8192)
                while (true) {
                    val n = stream.read(block)
                    if (n < 0) break
                    require(out.size() + n <= 20 * 1024 * 1024)
                    out.write(block, 0, n)
                }
            }
            check(generation.get() == run) { "连接已停止" }
            return JSONObject(out.toString("UTF-8"))
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }
}
