//! 验证各子页使用原生顶部返回图标，并能真正结束当前页面。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toolbar
import org.qingjian.speech.learning.DataSettingsActivity
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.update.UpdateActivity

object ToolbarProbe {
    fun run(test: Instrumentation): String {
        val pages = listOf(SpeechSettingsActivity::class.java, DataSettingsActivity::class.java, SettingsActivity::class.java,
            UpdateActivity::class.java, TutorActivity::class.java)
        for(page in pages) {
            val activity = test.startActivitySync(Intent(test.targetContext, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync()
            test.runOnMainSync { back(activity) }
            test.waitForIdleSync()
            check(activity.isFinishing || activity.isDestroyed) { "${page.simpleName} did not navigate back" }
        }
        return "PASS native toolbar back icons and navigation on speech, AI, updates, data/about and tutor pages"
    }

    fun back(activity: Activity) {
        val toolbar = checkNotNull(activity.findViewById<Toolbar>(R.id.pageToolbar))
        check(toolbar.navigationIcon != null && toolbar.navigationContentDescription == "返回")
        check(!visibleReturnButton(activity.window.decorView))
        checkNotNull(arrow(toolbar)).performClick()
    }

    private fun arrow(root: View): ImageButton? {
        if(root is ImageButton && root.contentDescription == "返回") return root
        if(root is ViewGroup) for(i in 0 until root.childCount) arrow(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun visibleReturnButton(root: View): Boolean {
        if(root.visibility != View.VISIBLE) return false
        if(root is Button && root.text.toString().startsWith("返回")) return true
        return root is ViewGroup && (0 until root.childCount).any { visibleReturnButton(root.getChildAt(it)) }
    }
}
