//! 每日请求预算管理器的单元测试：验证 ≤2 次请求的严格计数、剩余查询与超额阻断。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRequestBudgetTest {

    @Test
    fun testZeroRequestsWhenOfflineOrNoAi() {
        val budget = DailyRequestBudget(maxRequests = 2)
        assertEquals(0, budget.usedRequests)
        assertEquals(2, budget.remainingRequests)
        assertTrue(budget.canRequest())
        assertFalse(budget.toMetricsJson().getBoolean("budget_exhausted"))
    }

    @Test
    fun testSingleRequestWhenOnlyRevisionCalled() {
        val budget = DailyRequestBudget(maxRequests = 2)
        val count = budget.recordRequest("revise_study_plan")
        assertEquals(1, count)
        assertEquals(1, budget.usedRequests)
        assertEquals(1, budget.remainingRequests)
        assertTrue(budget.canRequest())
    }

    @Test
    fun testTwoRequestsWithMaterialsSupplement() {
        val budget = DailyRequestBudget(maxRequests = 2)
        budget.recordRequest("revise_study_plan")
        budget.recordRequest("prepare_word_materials_batch")
        assertEquals(2, budget.usedRequests)
        assertEquals(0, budget.remainingRequests)
        assertFalse(budget.canRequest())
        assertTrue(budget.toMetricsJson().getBoolean("budget_exhausted"))
    }

    @Test(expected = IllegalStateException::class)
    fun testThirdRequestIsBlocked() {
        val budget = DailyRequestBudget(maxRequests = 2)
        budget.recordRequest("req-1")
        budget.recordRequest("req-2")
        // 尝试发起第 3 次网络请求，必须被拦截并抛出异常
        budget.recordRequest("req-3")
    }
}
