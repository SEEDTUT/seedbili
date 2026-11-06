package com.gaibang.replyagent.app.ratelimit

import com.gaibang.replyagent.domain.model.RateLimit
import com.gaibang.replyagent.logging.AppLogger
import java.time.LocalDate
import java.time.ZoneId

sealed class Gate {
    data object Go : Gate()
    data class Wait(val millis: Long, val why: String) : Gate()
    data class Blocked(val why: String) : Gate()
}

/**
 * 发送节奏。所有发送动作排在同一条协程上，所以这里的计数就是真实计数。
 * 命中风控码后进入冷却；连续失败按指数退避，不硬撞。
 */
class RateLimiter(
    @Volatile private var cfg: RateLimit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {

    private var nextAllowedAt = 0L
    private var coolingUntil = 0L
    private var sentThisRun = 0
    private var sentToday = 0
    private var today: LocalDate = LocalDate.now()
    private var consecutiveFailures = 0

    @Volatile var lastDecisionNote: String = ""
        private set

    fun updateConfig(newCfg: RateLimit) {
        cfg = newCfg
    }

    fun resetRun() {
        sentThisRun = 0
        consecutiveFailures = 0
        coolingUntil = 0
        nextAllowedAt = 0
    }

    private fun rollDay() {
        val d = LocalDate.now(ZoneId.systemDefault())
        if (d != today) {
            today = d
            sentToday = 0
            AppLogger.i("rate", "跨天，今日配额重置")
        }
    }

    /** 阻塞到可以发送为止；返回 Blocked 表示本轮没必要继续 */
    suspend fun acquire(): Gate {
        rollDay()
        val c = cfg

        if (c.perDayCap in 1..sentToday)
            return Gate.Blocked("今日已发 ${sentToday} 条，达到上限 ${c.perDayCap}（可在策略配置里调）")

        if (c.perRunCap in 1..sentThisRun)
            return Gate.Blocked("本轮已发 ${sentThisRun} 条，达到单轮上限 ${c.perRunCap}")

        val t = now()
        if (t < coolingUntil) {
            val wait = coolingUntil - t
            lastDecisionNote = "冷却中，还需 ${wait / 1000}s"
            if (wait > 0) sleeper(wait.coerceAtMost(5_000))
            return Gate.Wait(wait, "风控冷却中")
        }

        if (t < nextAllowedAt) {
            val wait = nextAllowedAt - t
            sleeper(wait)
        }
        scheduleNext()
        return Gate.Go
    }

    private fun scheduleNext() {
        val c = cfg
        val gap = c.minIntervalMs + (0L..maxOf(0, c.jitterMs)).random()
        nextAllowedAt = now() + gap
    }

    fun markSent() {
        rollDay()
        sentToday++
        sentThisRun++
        consecutiveFailures = 0
        lastDecisionNote = "已发送（今日 $sentToday/${cfg.perDayCap}，本轮 $sentThisRun/${cfg.perRunCap}）"
    }

    fun markSkipped() {
        consecutiveFailures = 0
    }

    /** 平台返回失败。shouldCooldown 由调用方按风控码判断 */
    fun onFailure(code: Long, shouldCooldown: Boolean, describe: String) {
        consecutiveFailures++
        val c = cfg
        if (shouldCooldown) {
            coolingUntil = now() + c.cooldownOnLimitMs
            AppLogger.w("rate", "命中风控 code=$code（$describe），冷却 ${c.cooldownOnLimitMs / 1000}s")
        } else {
            val backoff = (c.backoffBaseMs shl (consecutiveFailures - 1).coerceAtMost(6))
                .coerceAtMost(c.backoffMaxMs)
            coolingUntil = now() + backoff
            AppLogger.w("rate", "第 $consecutiveFailures 次连续失败，退避 ${backoff}ms")
        }
        lastDecisionNote = "失败 code=$code：$describe"
    }

    fun shouldAbortRun(): Boolean = consecutiveFailures >= cfg.maxConsecutiveFailures && cfg.maxConsecutiveFailures > 0

    fun snapshot(): String = "今日 $sentToday/${cfg.perDayCap}，本轮 $sentThisRun/${cfg.perRunCap}，" +
            "连续失败 $consecutiveFailures，下次可发 ${((nextAllowedAt - now()).coerceAtLeast(0) / 1000)}s 后"

    fun sentToday(): Int = sentToday
    fun sentThisRun(): Int = sentThisRun
}
