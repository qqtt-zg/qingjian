//! 验证随包释义表能释放到私有目录并查得到词；不访问网络，不依赖电脑配对。
//!
//! 这是「选项 3」的兜底路径：APK 里带了表，装完就能离线查词，不需要先下载。
package org.qingjian.speech

import android.app.Instrumentation
import org.qingjian.speech.learning.glossary.Glossary
import org.qingjian.speech.learning.glossary.GlossaryFiles
import java.io.File

object GlossaryProbe {
    fun run(test: Instrumentation): String {
        val context = test.targetContext
        val notes = mutableListOf<String>()
        fun require(condition: Boolean, message: String) {
            check(condition) { message }
        }

        // 从干净状态开始：清掉之前释放/下载的表，逼它走 assets 兜底。
        val directory = GlossaryFiles.directory(context)
        directory.listFiles()?.forEach { it.delete() }

        val english = GlossaryFiles.installed(context, "en")
        require(english != null, "随包的中→英表应当可用")
        require(english!!.isFile && english.length() > 1024, "释放出来的表应当是有效文件")
        notes += "en=${english.length()}"
        // 释放结果要落在私有目录，不能在 assets 里被反复读
        require(english.path.startsWith(directory.path), "表应当释放到私有目录")

        val table = Glossary.open(english)
        require(table != null, "随包表应当能打开")
        require(table!!.size() > 200000, "真实词表应当有二十万条以上，实际 ${table.size()}")
        notes += "size=${table.size()}"

        val sense = table.find("候选")
        require(sense != null, "真实表里应当查得到「候选」")
        require(sense!!.text == "candidate", "「候选」应当译成 candidate，实际 ${sense.text}")
        notes += "候选=${sense.partOfSpeech} ${sense.text}"

        // 中文键查找不受 ASCII 小写回退影响
        require(table.find("部署")?.text == "deploy", "「部署」应当译成 deploy")

        // 英→中那张也要能用
        val chinese = GlossaryFiles.installed(context, "zh")
        require(chinese != null, "随包的英→中表应当可用")
        val reverse = Glossary.open(chinese!!)
        require(reverse != null, "英→中表应当能打开")
        require(reverse!!.find("deploy")?.text == "部署", "deploy 应当译成「部署」")
        // 大小写混合的英文键走 ASCII 小写回退
        require(reverse.find("Deploy")?.text == "部署", "Deploy 应当也能查到")
        notes += "zh=${reverse.size()}"

        // 第二次调用不该重复释放：拿到的应当是同一个文件
        val again = GlossaryFiles.installed(context, "en")
        require(again?.path == english.path, "重复调用应当复用已释放的表")

        // 缺少的资产（没有被下载、也没有随包）应当返回 null 而不是伪造
        require(GlossaryFiles.installed(context, "ja") == null, "没有的表应当返回 null")

        return notes.joinToString(" | ")
    }

    /** 探针跑完把状态说清楚，便于判断是不是「干净安装」这条路径。 */
    fun snapshot(context: android.content.Context): String {
        val directory = GlossaryFiles.directory(context)
        val files = directory.listFiles()?.joinToString(",") { "${it.name}:${it.length()}" } ?: "空"
        return "files=$files"
    }
}
