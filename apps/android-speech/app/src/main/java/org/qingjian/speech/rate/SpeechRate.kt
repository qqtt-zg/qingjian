//! 持久化原生合成语速；旧配置默认为正常语速，限制为 0.5～2.0 倍。
package org.qingjian.speech.rate

import android.content.Context
import java.util.Locale

object SpeechRate {
    const val KEY = "rate_percent"
    const val MIN = 50
    const val MAX = 200
    const val STEP = 5

    fun percent(context: Context): Int = context.getSharedPreferences("speech", 0).getInt(KEY, 100).coerceIn(MIN, MAX)

    fun speed(context: Context): Float = percent(context) / 100f

    fun save(context: Context, percent: Int) {
        require(percent in MIN..MAX && percent % STEP == 0)
        context.getSharedPreferences("speech", 0).edit().putInt(KEY, percent).apply()
    }

    fun label(percent: Int): String = String.format(Locale.ROOT, "%.2f×", percent / 100f)
}
