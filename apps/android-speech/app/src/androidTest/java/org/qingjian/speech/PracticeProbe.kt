//! 离线题目、幂等成绩、错题恢复与录音资料生命周期验证；不访问 AI 或个人录音。
package org.qingjian.speech

import android.app.Instrumentation
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.Wave
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Builder
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.random.Random

object PracticeProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val practice = PracticeStore(app)
        for((english, meaning) in listOf("control" to "控制", "control system" to "控制系统", "water" to "水", "realize" to "实现", "implement" to "实现")) {
            store.setMeaning(store.addWord(english), meaning)
        }
        val choice = Builder.build(store.words(), Kind.CHOICE, random = Random(7))
        check(choice.size == 5 && choice.map { it.key }.distinct().size == 5)
        for(question in choice) {
            check(question.options.size in 2..4 && question.options.distinct().size == question.options.size)
            check(question.options.count { it == question.meaning } == 1)
            check(Question.from(question.json()) == question)
        }
        val spelling = Builder.build(store.words(), Kind.SPELLING)
        check(spelling.size == 3 && spelling.none { it.english in listOf("realize", "implement") })
        check(spelling.first { it.english == "control system" }.correct("  CONTROL   SYSTEM "))
        val question = choice.first()
        val wrong = question.options.first { it != question.meaning }
        check(!practice.submit(question, wrong).correct)
        check(!practice.submit(question, question.meaning).correct)
        check(practice.summary(Kind.CHOICE) == (1 to 0))
        check(practice.mistakes().single().wordId == question.wordId)
        val retry = question.copy(key = UUID.randomUUID().toString())
        check(practice.submit(retry, question.meaning).correct)
        check(practice.summary(Kind.CHOICE) == (2 to 1) && practice.mistakes().isEmpty())
        store.setMeaning(question.wordId, "修改后的笔记")
        check(practice.answer(question.key)!!.meaning == question.meaning)
        check(store.word(question.wordId)!!.reviewed == 0)
        val header = Wave.header(Wave.RATE)
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        check(String(header.copyOfRange(0, 4)) == "RIFF" && bytes.getInt(24) == 16000 && bytes.getInt(40) == 32000)
        check(runCatching { Wave.header(Wave.MAX_FRAMES + 1) }.isFailure)
        check(runCatching { AudioFiles.file(app, "../voice.wav") }.isFailure)
        fun saveClip(): Long {
            val file = AudioFiles.temporary(app)
            file.writeBytes(header + ByteArray(32000))
            val id = practice.saveRecording(question.wordId, question.english, file, 1000)
            check(!file.exists())
            return id
        }
        val recordingId = saveClip()
        var recording = practice.recordings(question.wordId).single()
        check(recording.id == recordingId && recording.rating == "unreviewed")
        val path = AudioFiles.file(app, recording.filename); check(path.isFile && path.length() == 32044L)
        practice.rateRecording(recording.id, "again")
        check(practice.recordings(question.wordId).single().rating == "again")
        practice.deleteRecording(recording)
        check(!path.exists() && practice.recordings(question.wordId).isEmpty())
        saveClip(); recording = practice.recordings(question.wordId).single()
        val keep = app.getSharedPreferences("speech", 0)
        keep.edit().putInt("voice", 7).commit()
        store.clearLearning()
        check(!AudioFiles.file(app, recording.filename).exists())
        check(practice.summary(Kind.CHOICE).first == 0 && practice.recordings(question.wordId).isEmpty())
        check(keep.getInt("voice", -1) == 7)
        check(runCatching { practice.submit(question.copy(key = UUID.randomUUID().toString()), question.meaning) }.isFailure)
        check(practice.summary(Kind.CHOICE).first == 0)
        return "PASS offline quiz snapshots/options, ambiguous spelling skipped, idempotent grades, wrong-answer correction, separate counters, WAV bounds, private recording save/rate/delete and learning clear"
    }
}
