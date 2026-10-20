//! 整包补全的原子保存、既有内容保留、撤销保护及练习复用。
package org.qingjian.speech

import android.content.Context
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.notes.Pack
import org.qingjian.speech.learning.notes.Store

object NotesStoreProbe {
    fun run(app: Context): String {
        val book=LearningStore.get(app);book.clearLearning();val notes=Store(app)
        val id=book.addWord("control");val original=checkNotNull(notes.snapshot(id))
        val pack=Pack.parse(NotesFixture.pack(),"control")
        book.writableDatabase.execSQL("CREATE TRIGGER reject_material_fixture BEFORE INSERT ON word_materials BEGIN SELECT RAISE(ABORT,'fixture full'); END")
        try {
            check(runCatching {notes.apply(original,pack)}.isFailure)
            check(notes.snapshot(id)==original)
        } finally {book.writableDatabase.execSQL("DROP TRIGGER reject_material_fixture")}
        val applied=checkNotNull(notes.apply(original,pack))
        check(notes.apply(original,pack)==null && notes.complete(id))
        check(notes.card(id)!!.getString("example")==pack.example)
        check(org.qingjian.speech.learning.ai.tools.ActionStore(app).approvedCard(id)!!.getString("example")==pack.example)
        val undone=checkNotNull(notes.undo(original,applied))
        check(undone.meaning.isEmpty() && undone.materials.isEmpty())
        book.setMeaning(id,"手动含义");val manual=checkNotNull(notes.snapshot(id))
        val filled=checkNotNull(notes.apply(manual,pack))
        check(filled.meaning=="手动含义" && notes.complete(id))
        book.setMeaning(id,"手动含义")
        check(notes.undo(manual,filled)==null)
        val before=checkNotNull(notes.snapshot(id));book.setMeaning(id,"");book.setMeaning(id,"手动含义")
        check(notes.apply(before,pack)==null)
        val word=book.word(id)!!;check(word.received==0 && word.played==0 && word.reviewed==0)
        val deleted=checkNotNull(notes.snapshot(id));book.clearLearning()
        check(notes.card(id)==null && book.addWord("control")>id && notes.apply(deleted,pack)==null)
        val legacyWord=book.addWord("control")
        val question=book.question("word:$legacyWord","以前保存的词卡")
        val actions=org.qingjian.speech.learning.ai.tools.ActionStore(app)
        val action=actions.queue("word:$legacyWord",question,"prepare_word_card",org.json.JSONObject()
            .put("english","control").put("meaning","控制成本的控制").put("example","I control costs.").put("translation","我控制成本。"))
        actions.saveCard(action.id)
        val legacy=checkNotNull(notes.snapshot(legacyWord));val enriched=checkNotNull(notes.apply(legacy,pack))
        check(notes.card(legacyWord)!!.getString("example")=="I control costs.")
        check(notes.undo(legacy,enriched)!=null && notes.card(legacyWord)!!.getString("example")=="I control costs.")
        for(data in listOf(NotesFixture.pack().put("url","https://other.invalid"),NotesFixture.pack().put("usage",""),
            NotesFixture.pack().put("example","An unrelated sentence."),NotesFixture.pack().put("collocations",org.json.JSONArray()))) {
            check(runCatching {Pack.parse(data,"control")}.isFailure)
        }
        book.clearLearning()
        return "PASS complete study pack atomic save/undo, manual meaning retained, same-value edit protection, deleted IDs, strict proposals and quiz example reuse"
    }
}
