package com.gaibang.replyagent

import com.gaibang.replyagent.app.ratelimit.Gate
import com.gaibang.replyagent.app.ratelimit.RateLimiter
import com.gaibang.replyagent.domain.model.RateLimit
import com.gaibang.replyagent.platform.bilibili.BiliCodes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 节奏器是「不撞风控」的唯一保障，所以这里把时间源和 sleep 都换成假的，
 * 断言的是决策与计数，不是墙钟。
 */
class RateLimiterTest {

    private var clock = 1_000_000L
    private val slept = mutableListOf<Long>()
    private val cfg = RateLimit(
        minIntervalMs = 20_000, jitterMs = 0, perRunCap = 3, perDayCap = 5,
        cooldownOnLimitMs = 600_000, backoffBaseMs = 1_000, backoffMaxMs = 60_000,
        maxConsecutiveFailures = 2
    )

    private fun limiter(c: RateLimit = cfg) = RateLimiter(c, now = { clock }, sleeper = { slept.add(it) })

    @Test
    fun firstSendIsImmediateAndSecondMustWaitTheInterval() = runTest {
        val r = limiter()
        assertEquals(Gate.Go, r.acquire())
        r.markSent()
        clock += 5_000
        assertEquals(Gate.Go, r.acquire())   // 等待发生在内部，不返回 Blocked
        assertEquals(15_000L, slept.last(), "只等了剩余的间隔，不是重新等一整个")
    }

    @Test
    fun clockDoesNotAdvanceForFree() = runTest {
        val r = limiter()
        r.acquire()
        r.markSent()
        clock += 20_000
        assertEquals(Gate.Go, r.acquire())
    }

    @Test
    fun perRunCapBlocksFurtherSendsUntilReset() = runTest {
        val r = limiter()
        repeat(3) { r.acquire(); r.markSent(); clock += 20_000 }
        val g = r.acquire()
        assertTrue(g is Gate.Blocked, "单轮上限 3 条要拦住第 4 条")
        assertTrue((g as Gate.Blocked).why.contains("单轮上限"))
        r.resetRun()
        assertEquals(Gate.Go, r.acquire())
    }

    @Test
    fun perDayCapIsHarderThanPerRunCap() = runTest {
        val r = limiter(cfg.copy(perRunCap = 99, perDayCap = 4))
        repeat(4) { r.acquire(); r.markSent(); clock += 20_000 }
        assertTrue(r.acquire() is Gate.Blocked)
        assertTrue(r.snapshot().contains("4/4"))
    }

    @Test
    fun riskControlCodePutsTheWholeLimiterIntoCooldown() = runTest {
        val r = limiter()
        r.acquire(); r.markSent()
        clock += 20_000
        r.onFailure(BiliCodes.SEND_LIMIT, shouldCooldown = true, describe = "12014")
        clock += 1_000
        val g = r.acquire()
        assertTrue(g is Gate.Wait, "冷却期内不能给 Go")
        assertTrue((g as Gate.Wait).millis > 500_000)
    }

    @Test
    fun ordinaryFailuresBackOffExponentiallyAndAreCapped() = runTest {
        val r = limiter(cfg.copy(backoffBaseMs = 1_000, backoffMaxMs = 4_000, maxConsecutiveFailures = 99))
        r.onFailure(1L, false, "a")
        assertEquals(1_000L, cooldownOf(r))
        clock += 1_001
        r.onFailure(1L, false, "b")
        assertEquals(2_000L, cooldownOf(r))
        clock += 2_001
        r.onFailure(1L, false, "c")
        assertEquals(4_000L, cooldownOf(r))
        clock += 4_001
        r.onFailure(1L, false, "d")
        r.onFailure(1L, false, "e")
        assertEquals(4_000L, cooldownOf(r), "退避不能无限涨")
    }

    @Test
    fun abortsTheRunAtTheConsecutiveFailureLineAndRecoversOnSuccess() = runTest {
        val r = limiter()
        assertFalse(r.shouldAbortRun())
        r.onFailure(1L, false, "a")
        assertFalse(r.shouldAbortRun())
        r.onFailure(1L, false, "b")
        assertTrue(r.shouldAbortRun(), "连到 maxConsecutiveFailures 就该停，而不是继续撞")
        clock += 600_000
        r.acquire(); r.markSent()
        r.onFailure(1L, false, "c")
        assertFalse(r.shouldAbortRun(), "中间成功一次就要清零")
    }

    @Test
    fun skipDoesNotCountAsAFailure() = runTest {
        val r = limiter()
        r.onFailure(1L, false, "a")
        r.markSkipped()
        assertFalse(r.shouldAbortRun())
    }

    @Test
    fun configEditsTakeEffectOnTheFly() = runTest {
        val r = limiter()
        repeat(3) { r.acquire(); r.markSent(); clock += 20_000 }
        assertTrue(r.acquire() is Gate.Blocked)
        r.updateConfig(cfg.copy(perRunCap = 10))
        assertEquals(Gate.Go, r.acquire())
    }

    private suspend fun cooldownOf(r: RateLimiter): Long {
        val before = clock
        val g = r.acquire()
        clock = before
        return when (g) {
            is Gate.Wait -> g.millis
            else -> 0L
        }
    }

    @Test
    fun jitterIsRespectedAsAnUpperBoundNotAnExactNumber() = runTest {
        val r = limiter(cfg.copy(jitterMs = 5_000))
        r.acquire(); r.markSent()
        clock += 1
        r.acquire()
        val wait = slept.last()
        assertTrue(wait in 19_999..24_999, "间隔应落在 minInterval..minInterval+jitter，实际 $wait")
    }
}
