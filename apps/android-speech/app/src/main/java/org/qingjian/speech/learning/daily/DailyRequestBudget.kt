//! 每日任务网络请求预算管理器：单次任务严格限制最多 2 次 HTTP 请求，防止过度调用。
package org.qingjian.speech.learning.daily

import org.json.JSONObject

class DailyRequestBudget(val maxRequests: Int = 2) {
    var usedRequests: Int = 0
        private set

    val remainingRequests: Int get() = (maxRequests - usedRequests).coerceAtLeast(0)

    fun canRequest(): Boolean = usedRequests < maxRequests

    /**
     * 记录一次发起的网络请求。若已达预算上限，立即抛出 IllegalStateException。
     */
    fun recordRequest(taskNote: String = ""): Int {
        check(canRequest()) { "已达到单次每日任务网络请求预算上限 (${maxRequests} 次)，禁止隐式发起额外请求" }
        usedRequests++
        return usedRequests
    }

    fun toMetricsJson(): JSONObject = JSONObject()
        .put("max_requests", maxRequests)
        .put("used_requests", usedRequests)
        .put("budget_exhausted", !canRequest())
}
