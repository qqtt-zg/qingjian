//! 语音合成性能调度：自动识别旗舰大核配置，提供多档算力加速与实时调度提权。
package org.qingjian.speech

import android.content.Context
import android.os.Process

object SpeechPerformance {
    const val MODE_STANDARD = 0    // 标准省电 (2核)
    const val MODE_BALANCED = 1    // 性能加速 (4核)
    const val MODE_EXTREME = 2     // 旗舰极速 (6大核全开 + 满频调度)

    val labels = arrayOf("旗舰极速 · 6大核全开", "性能加速 · 4核", "标准省电 · 2核")

    fun defaultMode(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> MODE_EXTREME   // 8核旗舰（如一加 Ace 5 骁龙 8 Gen 3），默认拉满开启 6 大核
            cores >= 6 -> MODE_BALANCED  // 6核机型，默认 4 核加速
            else -> MODE_STANDARD        // 4核及以下入门机型
        }
    }

    fun mode(context: Context): Int {
        val prefs = context.getSharedPreferences("speech", 0)
        return prefs.getInt("perf_mode", defaultMode()).coerceIn(0, 2)
    }

    fun setMode(context: Context, mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        context.getSharedPreferences("speech", 0).edit().putInt("perf_mode", clamped).apply()
    }

    fun threads(context: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when (mode(context)) {
            MODE_EXTREME -> minOf(6, maxOf(4, cores - 2)) // 8核机上分配 6 线程，全部由 X4/A720 大核处理
            MODE_BALANCED -> minOf(4, maxOf(2, cores - 2))
            else -> 2
        }
    }

    fun applyPriority(context: Context) {
        when (mode(context)) {
            MODE_EXTREME -> {
                // 调度器最高实时音频优先级（Linux nice -19），瞬间拉升 CPU 睿频至最高
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            }
            MODE_BALANCED -> {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            }
            else -> {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
            }
        }
    }
}
