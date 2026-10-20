//! 录音绑定、完整评测记录、删除后的晚到拒绝和练习题号幂等。
package org.qingjian.speech

import android.content.Context
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.assessment.Store
import org.qingjian.speech.learning.assessment.Parser
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind

object AssessmentStoreProbe {
    fun run(app:Context):String {
        val book=LearningStore.get(app);book.clearLearning();val word=book.addWord("control");book.setMeaning(word,"控制")
        val practice=PracticeStore(app);val store=Store(app)
        val id=practice.saveRecording(word,"control",AssessmentFixture.audio(app),500,"Can you control the volume?","sentence")
        val recording=practice.recordings(word).single();check(recording.id==id && recording.reference.contains("volume"))
        val initial=store.start(recording);check(store.latest(id)!!.result==null)
        val result=Parser.parse(AssessmentFixture.XML);check(store.finish(initial.id,result));check(!store.finish(initial.id,result))
        check(store.latest(id)!!.result!!.words.first().issue.isNotBlank() && store.facts(word).getJSONArray("recent_assessments").length()==1)
        val second=store.start(recording);store.fail(second.id,"已取消",true);check(!store.finish(second.id,result))
        check(store.history(id).single().id==initial.id && store.history(id).single().result!!.total==86.5)
        val late=store.start(recording);practice.deleteRecording(recording);check(!store.finish(late.id,result) && store.latest(id)==null)
        val quiz=org.qingjian.speech.learning.word.Practice(app,word);quiz.prepare(Kind.SPELLING);val question=checkNotNull(quiz.current)
        quiz.answer("control");quiz.answer("wrong");check(practice.answer(question.key)!!.correct)
        check(org.qingjian.speech.learning.word.Practice(app,word).current!!.key==question.key)
        book.setMeaning(word,"调节");quiz.validate();check(quiz.current==null && practice.answer(question.key)!=null)
        val remaining=practice.saveRecording(word,"control",AssessmentFixture.audio(app),500)
        val pending=store.start(practice.recordings(word).single());book.clearLearning();check(!store.finish(pending.id,result) && store.latest(remaining)==null)
        return "PASS assessment immutable recording/reference/hash, one commit per attempt, cancelled/deleted late rejection, grade history cleanup, durable practice IDs and old answer retention"
    }
}
