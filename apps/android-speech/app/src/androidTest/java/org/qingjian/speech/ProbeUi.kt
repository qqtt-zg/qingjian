//! 新练习探针共用的主线程断言与有界等待，避免断言异常直接杀死测试进程。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

class ProbeUi(private val test: Instrumentation) {
    fun main(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch(error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    fun await(label: String, timeoutMillis: Long = 15000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000L
        while(System.nanoTime() < end) {
            var done = false; main { done = condition() }
            if(done) return
            Thread.sleep(30)
        }
        error("timed out: $label")
    }

    fun text(view: View): String = when(view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }

    fun button(view: View, label: String): Button? {
        if(view is Button && view.text.toString() == label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
        return null
    }

    fun target(view: View, label: String): View? {
        if(view.contentDescription?.toString() == label || view is Button && view.text.toString() == label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) target(view.getChildAt(i), label)?.let { return it }
        return null
    }

    fun click(activity: Activity, label: String) = main { check(checkNotNull(target(activity.window.decorView, label)) { "找不到控件：$label" }.performClick()) }
}
