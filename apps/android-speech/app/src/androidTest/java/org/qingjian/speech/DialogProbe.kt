//! 隔离设备的系统对话框点击；按可见文字或无障碍描述定位，等待窗口完成切换。
package org.qingjian.speech

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

object DialogProbe {
    fun exists(test: Instrumentation, label: String) = test.uiAutomation.rootInActiveWindow?.let { find(it, label) } != null

    fun enabled(test: Instrumentation, label: String) = test.uiAutomation.rootInActiveWindow?.let { find(it, label) }?.isEnabled == true

    fun click(test: Instrumentation, label: String) {
        val automation = test.uiAutomation
        automation.waitForIdle(250, 5000)
        val end = System.nanoTime() + 10_000_000_000L
        var match: AccessibilityNodeInfo? = null
        while(match == null && System.nanoTime() < end) {
            match = automation.rootInActiveWindow?.let { find(it, label) }
            if(match == null) Thread.sleep(30)
        }
        val bounds = Rect()
        checkNotNull(match) { "对话框控件未出现：$label" }.getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + action * 30L, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        test.waitForIdleSync()
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if(node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for(index in 0 until node.childCount) node.getChild(index)?.let { find(it, label)?.let { found -> return found } }
        return null
    }
}
