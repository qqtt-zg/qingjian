//! 手机学习资料的受控模型提案，测试不使用个人密钥或真实模型。
package org.qingjian.speech

import org.json.JSONArray
import org.json.JSONObject

object NotesFixture {
    fun pack()=JSONObject().put("meaning","v. 控制；调节")
        .put("example","You can control the volume.").put("translation","你可以调节音量。")
        .put("collocations",JSONArray().put(JSONObject().put("phrase","control the volume").put("translation","调节音量"))
            .put(JSONObject().put("phrase","control costs").put("translation","控制成本")))
        .put("usage","作及物动词时直接接宾语，例如音量或成本；不要在 control 和宾语之间加介词。")

    fun answer(text:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",text)))).toString()
    fun tool()=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",JSONObject.NULL)
        .put("tool_calls",JSONArray().put(JSONObject().put("id","materials-proposal").put("type","function")
            .put("function",JSONObject().put("name","prepare_word_materials").put("arguments",pack().toString()))))))).toString()

    fun validate() = org.qingjian.speech.learning.notes.Pack.parse(pack(),"control")
}
