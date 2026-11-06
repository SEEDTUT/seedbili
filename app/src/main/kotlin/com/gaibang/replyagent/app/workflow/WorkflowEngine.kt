package com.gaibang.replyagent.app.workflow

import com.gaibang.replyagent.agent.AgentResult
import com.gaibang.replyagent.agent.AgentRunner
import com.gaibang.replyagent.agent.PromptRenderer
import com.gaibang.replyagent.agent.ToolDefinition
import com.gaibang.replyagent.agent.ToolOutcome
import com.gaibang.replyagent.agent.ToolRegistry
import com.gaibang.replyagent.agent.objSchema
import com.gaibang.replyagent.app.ledger.LedgerService
import com.gaibang.replyagent.app.policy.FilterEngine
import com.gaibang.replyagent.app.policy.Verdict
import com.gaibang.replyagent.app.ratelimit.Gate
import com.gaibang.replyagent.app.ratelimit.RateLimiter
import com.gaibang.replyagent.app.sandbox.SandboxService
import com.gaibang.replyagent.config.ConfigStore
import com.gaibang.replyagent.domain.model.Action
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.Decision
import com.gaibang.replyagent.domain.model.LedgerEntry
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.model.SandboxBackend
import com.gaibang.replyagent.domain.model.StrategyProfile
import com.gaibang.replyagent.domain.model.Target
import com.gaibang.replyagent.domain.port.Capability
import com.gaibang.replyagent.domain.port.PlatformAdapter
import com.gaibang.replyagent.domain.port.SecretStore
import com.gaibang.replyagent.llm.OpenAiClient
import com.gaibang.replyagent.llm.toDisplay
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.platform.bilibili.BiliCodes
import com.gaibang.replyagent.util.obj
import com.gaibang.replyagent.util.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

enum class RunPhase { IDLE, RESOLVING, FETCHING, DECIDING, SENDING, PAUSED, DONE, ABORTED, FAILED }

data class RunRecord(
    val comment: Comment,
    val verdict: String,
    val decision: Decision?,
    val result: String,
    val at: String
)

data class RunProgress(
    val phase: RunPhase = RunPhase.IDLE,
    val target: Target? = null,
    val scanned: Int = 0,
    val filteredOut: Int = 0,
    val decided: Int = 0,
    val sent: Int = 0,
    val escalated: Int = 0,
    val failed: Int = 0,
    val note: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val dryRun: Boolean = false
)

data class InboxItem(
    val comment: Comment,
    val decision: Decision,
    val reason: String,
    val handled: Boolean = false,
    val outcome: String = ""
)

/**
 * 一轮 run 的编排：抓取 → 本地过滤 → 按需补查楼中楼 → Agent 判断 → 发送前二次校验 → 发送 → 落账。
 * 全程串行，所以限速器的计数就是真实计数（见架构文档第三章）。
 */
class WorkflowEngine(
    private val config: ConfigStore,
    private val secrets: SecretStore,
    private val adapterProvider: () -> PlatformAdapter?,
    private val filter: FilterEngine,
    private val renderer: PromptRenderer,
    private val sandbox: SandboxService,
    private val ledger: LedgerService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null

    private val _progress = MutableStateFlow(RunProgress())
    val progress: StateFlow<RunProgress> = _progress.asStateFlow()

    private val _records = MutableStateFlow<List<RunRecord>>(emptyList())
    val records: StateFlow<List<RunRecord>> = _records.asStateFlow()

    private val _inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    val inbox: StateFlow<List<InboxItem>> = _inbox.asStateFlow()

    private val _approvals = MutableStateFlow<String?>(null)
    /** run_command 的人工确认请求；UI 弹框后调用 resolveApproval */
    val pendingApproval: StateFlow<String?> = _approvals.asStateFlow()

    @Volatile var approvalGrantedForSession: Boolean = false
        private set

    fun resolveApproval(allow: Boolean, remember: Boolean = false) {
        if (allow && remember) approvalGrantedForSession = true
        val waiter = approvalWaiter
        approvalWaiter = null
        approvalAnswer = allow
        waiter?.complete(Unit)
    }

    private var approvalWaiter: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile private var approvalAnswer: Boolean = false

    fun start(ref: String, dryRun: Boolean = false): Job {
        stopRunning()
        _records.value = emptyList()
        _progress.value = RunProgress(phase = RunPhase.RESOLVING, note = "解析目标…", dryRun = dryRun)
        return scope.launch {
            try {
                execute(ref, dryRun)
            } catch (e: CancellationException) {
                val p = _progress.value
                if (p.phase != RunPhase.PAUSED && p.phase != RunPhase.FAILED) {
                    _progress.value = p.copy(phase = RunPhase.ABORTED, note = "已手动停止")
                }
            }
        }.also { runJob = it }
    }

    fun stopRunning() {
        runJob?.cancel()
        runJob = null
        val p = _progress.value
        if (p.phase == RunPhase.DECIDING || p.phase == RunPhase.FETCHING ||
            p.phase == RunPhase.SENDING || p.phase == RunPhase.RESOLVING
        ) {
            _progress.value = p.copy(phase = RunPhase.ABORTED, note = "已手动停止")
        }
    }

    fun close() = scope.cancel()

    private suspend fun execute(ref: String, dryRun: Boolean) {
        val cfg = config.current
        val strategy = cfg.strategy()
        val adapter = adapterProvider() ?: run { fail("没有可用的平台适配器"); return }

        val login = runCatching { adapter.loginStatus() }.getOrDefault(LoginStatus.NotLoggedIn)
        val selfUid = (login as? LoginStatus.LoggedIn)?.uid ?: ""
        if (login !is LoginStatus.LoggedIn) {
            fail("还没登录，或登录态已失效。先到「登录」页完成扫码。")
            return
        }

        val target = adapter.resolveTarget(ref).getOrElse {
            fail("解析投稿失败：${it.toDisplay()}")
            return
        }
        _progress.value = _progress.value.copy(
            phase = RunPhase.FETCHING, target = target,
            note = "抓取评论：${target.title.ifBlank { target.id }}"
        )

        val profile = cfg.apiProfile()
        val apiKey = readApiKey(profile.keyRef)
        if (apiKey.isNullOrBlank()) {
            fail("当前 API 配置「${profile.name}」里没有 Key。到「模型 API」页填一次。")
            return
        }

        val rate = RateLimiter(strategy.rate)
        var page = 1
        var pages = 0
        val seen = HashSet<String>()

        while (scope.isActive && pages < MAX_PAGES) {
            pages++
            val batch = adapter.fetchComments(target, page, PAGE_SIZE).getOrElse {
                AppLogger.w("run", "抓取第 $page 页失败：${it.message}")
                break
            }
            if (batch.isEmpty()) break
            _progress.value = _progress.value.copy(note = "第 $page 页抓到 ${batch.size} 条")

            for (c in batch) {
                if (!scope.isActive) break
                if (!seen.add(c.id)) continue
                handleOne(adapter, target, strategy, cfg, selfUid, apiKey, c, rate, dryRun)
            }
            page++
            delay(FETCH_PAGE_PAUSE_MS)
        }

        val p = _progress.value
        val finalPhase = when {
            rate.shouldAbortRun() -> RunPhase.PAUSED
            !scope.isActive -> RunPhase.ABORTED
            else -> RunPhase.DONE
        }
        val note = when (finalPhase) {
            RunPhase.PAUSED -> "连续失败触发上限，本轮提前停止，避免继续撞风控（${rate.snapshot()}）"
            RunPhase.ABORTED -> "已停止"
            else -> if (dryRun) "试跑完成：全程没有真的发出去" else "本轮结束（${rate.snapshot()}）"
        }
        _progress.value = p.copy(phase = finalPhase, note = note)
        AppLogger.i("run", "本轮结束：发送 ${p.sent}，转人工 ${p.escalated}，失败 ${p.failed}，过滤 ${p.filteredOut}")
    }

    private suspend fun handleOne(
        adapter: PlatformAdapter,
        target: Target,
        strategy: StrategyProfile,
        cfg: AppConfig,
        selfUid: String,
        apiKey: String,
        raw: Comment,
        rate: RateLimiter,
        dryRun: Boolean
    ) {
        bump { it.copy(scanned = it.scanned + 1) }

        val verdict = filter.evaluate(raw, strategy, selfUid) { id ->
            ledger.handled(id) && !ledger.retryable(id)
        }
        if (verdict is Verdict.Filtered) {
            bump { it.copy(filteredOut = it.filteredOut + 1) }
            record(raw, "本地过滤：${verdict.reason}", null, "未进入模型")
            putLedger(raw, target, "filter", LedgerStatus.FILTERED, reason = verdict.reason)
            return
        }

        var comment = raw
        // 需要判断「是否已回过」且平台支持读子回复时，按需补查楼中楼
        if (strategy.scope.skipAlreadyAnswered && comment.isTopLevel &&
            Capability.LIST_REPLIES in adapter.capabilities
        ) {
            val kids = adapter.childReplies(target, comment.id).getOrNull() ?: emptyList()
            val mainUids = strategy.identities
                .filter { it.enabled && it.uid.isNotBlank() && it.kind == com.gaibang.replyagent.domain.model.IdentityKind.MAIN_UP }
                .map { it.uid }.toSet()
            val bySelf = kids.any { selfUid.isNotBlank() && it.authorUid == selfUid }
            val byMainUp = kids.any { it.authorUid in mainUids }
            comment = comment.copy(answeredBySelf = bySelf, answeredByMainUp = byMainUp)
            if (bySelf || byMainUp) {
                bump { it.copy(filteredOut = it.filteredOut + 1) }
                record(comment, "已回过（补查楼中楼）", null, "未进入模型")
                putLedger(comment, target, "filter", LedgerStatus.FILTERED, reason = "子回复里已有本人或主 UP 的发言")
                return
            }
        }

        if (verdict is Verdict.Escalate) {
            bump { it.copy(escalated = it.escalated + 1, decided = it.decided + 1) }
            val d = Decision(Action.ESCALATE, "", 1.0, verdict.reason)
            record(comment, "规则直接转人工", d, verdict.reason)
            pushInbox(InboxItem(comment, d, verdict.reason))
            putLedger(comment, target, "escalate", LedgerStatus.ESCALATED,
                reply = "", reason = verdict.reason)
            return
        }

        bump { it.copy(phase = RunPhase.DECIDING, decided = it.decided + 1, note = "判断：${comment.content.take(40)}") }

        val result = decide(comment, strategy, cfg, selfUid, apiKey)
        if (result == null) {
            bump { it.copy(failed = it.failed + 1) }
            record(comment, "判断失败", null, "模型不可用")
            putLedger(comment, target, "decide", LedgerStatus.FAILED, error = "模型判断失败")
            return
        }
        val decision = result.decision

        when (decision.action) {
            Action.SKIP -> {
                record(comment, "Agent 决定不回", decision, decision.reason)
                putLedger(comment, target, "skip", LedgerStatus.SKIPPED, reply = decision.text,
                    reason = "${decision.reason}｜把握度 ${decision.confidence}", model = cfg.apiProfile().model,
                    tokensIn = result.tokensIn, tokensOut = result.tokensOut, latencyMs = result.latencyMs)
                rate.markSkipped()
            }

            Action.ESCALATE -> {
                bump { it.copy(escalated = it.escalated + 1) }
                record(comment, "转人工", decision, decision.reason)
                pushInbox(InboxItem(comment, decision, decision.reason))
                putLedger(comment, target, "escalate", LedgerStatus.ESCALATED, reply = decision.text,
                    reason = decision.reason, model = cfg.apiProfile().model,
                    tokensIn = result.tokensIn, tokensOut = result.tokensOut, latencyMs = result.latencyMs)
            }

            Action.REPLY -> send(adapter, target, comment, decision, strategy, selfUid, rate, dryRun, result)
        }
    }

    private suspend fun send(
        adapter: PlatformAdapter,
        target: Target,
        comment: Comment,
        decision: Decision,
        strategy: StrategyProfile,
        selfUid: String,
        rate: RateLimiter,
        dryRun: Boolean,
        result: AgentResult
    ) {
        val recheck = filter.recheckBeforeSend(comment, strategy, selfUid)
        if (recheck is Verdict.Filtered) {
            record(comment, "发送前被拦", decision, recheck.reason)
            putLedger(comment, target, "reply", LedgerStatus.FILTERED, reply = decision.text, reason = recheck.reason)
            return
        }
        if (strategy.ledger.recheckBeforeSend && ledger.handled(comment.id) && !ledger.retryable(comment.id)) {
            record(comment, "发送前被拦（台账幂等）", decision, "这条已经处理过了")
            putLedger(comment, target, "reply", LedgerStatus.DUP_SKIPPED, reply = decision.text, reason = "台账里已有记录")
            return
        }

        bump { it.copy(phase = RunPhase.SENDING) }
        when (val g = rate.acquire()) {
            is Gate.Blocked -> {
                record(comment, "受节奏限制未发", decision, g.why)
                bump { it.copy(note = g.why) }
                putLedger(comment, target, "reply", LedgerStatus.COOLDOWN, reply = decision.text, reason = g.why)
                return
            }
            is Gate.Wait -> bump { it.copy(note = "等待 ${g.millis / 1000}s：${g.why}") }
            Gate.Go -> Unit
        }

        if (dryRun) {
            record(comment, "试跑：本应发送", decision, decision.text)
            pushInbox(InboxItem(comment, decision, "试跑模式：原本会真的发出去", outcome = decision.text))
            putLedger(comment, target, "reply-dryrun", LedgerStatus.ESCALATED, reply = decision.text,
                reason = "试跑模式未真实发送")
            return
        }

        val res = adapter.postReply(target, comment.id, decision.text).getOrElse {
            bump { it.copy(failed = it.failed + 1) }
            record(comment, "发送异常", decision, it.toDisplay())
            putLedger(comment, target, "reply", LedgerStatus.FAILED, reply = decision.text, error = it.toDisplay())
            rate.onFailure(-1, false, it.toDisplay())
            return
        }

        if (res.ok) {
            rate.markSent()
            bump { it.copy(sent = it.sent + 1) }
            record(comment, "已发送", decision, res.replyId)
            putLedger(comment, target, "reply", LedgerStatus.SENT, code = res.code, reply = decision.text,
                reason = decision.reason, model = config.current.apiProfile().model,
                tokensIn = result.tokensIn, tokensOut = result.tokensOut, latencyMs = result.latencyMs)
            AppLogger.i("run", "已回复 ${comment.id} → rpid=${res.replyId}")
        } else {
            bump { it.copy(failed = it.failed + 1) }
            val cooldown = BiliCodes.shouldCooldown(res.code)
            rate.onFailure(res.code, cooldown, res.message)
            val status = if (res.code == BiliCodes.DUPLICATE_COMMENT) LedgerStatus.DUP_SKIPPED else LedgerStatus.FAILED
            record(comment, "发送失败", decision, "code=${res.code} ${res.message}")
            putLedger(comment, target, "reply", status, code = res.code, reply = decision.text, error = res.message)

            if (BiliCodes.isFatalForRun(res.code)) {
                bump { it.copy(phase = RunPhase.PAUSED, note = "登录态失效，本轮中止，请重新登录后再跑") }
                AppLogger.e("run", "登录态失效，中止本轮")
                runJob?.cancel()
                return
            }
            if (rate.shouldAbortRun()) {
                bump { it.copy(phase = RunPhase.PAUSED, note = "连续失败达上限，本轮暂停：${rate.snapshot()}") }
                runJob?.cancel()
            }
        }
    }

    private suspend fun decide(
        comment: Comment,
        strategy: StrategyProfile,
        cfg: AppConfig,
        selfUid: String,
        apiKey: String
    ): AgentResult? {
        val adapter = adapterProvider() ?: return null
        val registry = buildToolRegistry(comment, strategy, cfg, adapter, selfUid)
        val client = OpenAiClient(cfg.apiProfile(), apiKey)
        val messages = renderer.buildMessages(
            prompt = cfg.prompt(), strategy = strategy, comment = comment,
            target = _progress.value.target, selfUid = selfUid
        )
        val r = runCatching { AgentRunner(client, registry).decide(messages, strategy) }
            .onFailure { AppLogger.e("run", "Agent 判断异常", it) }
            .getOrNull() ?: return null
        r.notes.forEach { AppLogger.d("run", "备注：$it") }
        bump { it.copy(tokensIn = it.tokensIn + r.tokensIn, tokensOut = it.tokensOut + r.tokensOut) }
        return r
    }

    /** 需求书第六章第 1 条：用户手动粘贴内容，单独跑一次判断 */
    suspend fun tryOne(text: String): AgentResult {
        val cfg = config.current
        val strategy = cfg.strategy()
        val adapter = adapterProvider()
        val selfUid = (runCatching { adapter?.loginStatus() }.getOrNull() as? LoginStatus.LoggedIn)?.uid ?: ""
        val registry = buildToolRegistry(
            Comment(id = "manual", targetId = "-", content = text), strategy, cfg, adapter, selfUid
        )
        val client = OpenAiClient(cfg.apiProfile(), readApiKey(cfg.apiProfile().keyRef) ?: "")
        val messages = renderer.buildManualMessages(cfg.prompt(), strategy, text, selfUid)
        return AgentRunner(client, registry).decide(messages, strategy)
    }

    /** 收件箱里人工改定后的文本，由用户点发送。失败不动台账，条目留在收件箱可重试 */
    suspend fun sendManual(item: InboxItem, text: String): PlatformSendOutcome {
        val adapter = adapterProvider() ?: return PlatformSendOutcome(false, "没有可用平台")
        val cfg = config.current
        val target = _progress.value.target ?: adapter.resolveTarget(item.comment.targetId).getOrNull()
            ?: return PlatformSendOutcome(false, "找不到这条评论所属的投稿")
        val res = adapter.postReply(target, item.comment.id, text).getOrElse {
            return PlatformSendOutcome(false, it.toDisplay())
        }
        if (!res.ok) return PlatformSendOutcome(false, "code=${res.code} ${res.message}")
        putLedger(item.comment, target, "manual-reply", LedgerStatus.SENT,
            code = res.code, reply = text, reason = "人工定夺后发送", model = cfg.apiProfile().model)
        return PlatformSendOutcome(true, "已发送 rpid=${res.replyId}")
    }

    data class PlatformSendOutcome(val ok: Boolean, val note: String)

    private fun buildToolRegistry(
        comment: Comment,
        strategy: StrategyProfile,
        cfg: AppConfig,
        adapter: PlatformAdapter?,
        selfUid: String
    ): ToolRegistry {
        val r = ToolRegistry()
        r.register(ToolDefinition(
            name = "get_policy",
            description = "查看当前生效的策略摘要（范围、名单、兜底、阈值）",
            schema = objSchema()
        ) {
            val bl = strategy.identities.filter { it.enabled && it.kind.name == "BLACKLIST" }
                .joinToString { it.name.ifBlank { it.uid } }.ifBlank { "无" }
            val skip = strategy.identities.filter { it.enabled && it.kind.name != "BLACKLIST" }
                .joinToString { it.name.ifBlank { it.uid } }.ifBlank { "无" }
            ToolOutcome.ok(
                "范围=${if (strategy.scope.topLevelOnly) "只回顶层评论" else "含楼中楼"}；" +
                        "跳过名单=$skip；黑名单=$bl；兜底=${strategy.fallback}；" +
                        "自动回复把握度阈值=${strategy.minConfidenceForAutoReply}；" +
                        "本条是否顶层=${comment.isTopLevel}"
            )
        })
        r.register(ToolDefinition(
            name = "check_login",
            description = "查询当前平台登录状态",
            schema = objSchema()
        ) {
            ToolOutcome.ok(when (val s = runCatching { adapter?.loginStatus() }.getOrNull()) {
                is LoginStatus.LoggedIn -> "已登录：${s.name}（${s.uid}）"
                is LoginStatus.Expired -> "登录态已失效，需要用户重新登录"
                else -> "未登录"
            })
        })

        // 未开启沙箱时，run_command 连 schema 都不出现，而不是「出现但拒绝」
        if (cfg.sandbox.backend != SandboxBackend.NONE && cfg.sandbox.allowRunCommand) {
            r.register(ToolDefinition(
                name = "run_command",
                description = "在虚拟 Linux 环境执行一条命令",
                requiresApproval = cfg.sandbox.confirmEachCommand && !approvalGrantedForSession,
                approvalPrompt = "Agent 请求执行命令",
                schema = objSchema("command" to "string|要执行的 shell 命令")
            ) { args ->
                val cmd = args.str("command")
                if (cmd.isBlank()) return@ToolDefinition ToolOutcome.fail("command 不能为空")
                if (cfg.sandbox.confirmEachCommand && !approvalGrantedForSession) {
                    if (!awaitApproval("$cmd（宿主：${cfg.sandbox.backend}）")) {
                        return@ToolDefinition ToolOutcome.fail("用户拒绝了这条命令")
                    }
                }
                val out = when (cfg.sandbox.backend) {
                    SandboxBackend.WSL -> sandbox.runCommand(cmd, cfg.sandbox.distro, cfg.sandbox.commandTimeoutSeconds)
                    else -> sandbox.runLocal(cmd, cfg.sandbox.commandTimeoutSeconds)
                }
                ToolOutcome(ok = out.exit == 0, content = "[${out.how}] exit=${out.exit}\n${out.output.take(4000)}")
            })
        }
        return r
    }

    private suspend fun awaitApproval(command: String): Boolean {
        approvalAnswer = false
        approvalWaiter = kotlinx.coroutines.CompletableDeferred()
        _approvals.value = command
        AppLogger.w("run", "Agent 请求执行命令，等待用户确认：$command")
        approvalWaiter?.await()
        _approvals.value = null
        return approvalAnswer
    }

    private fun bump(transform: (RunProgress) -> RunProgress) {
        _progress.value = transform(_progress.value)
    }

    private fun pushInbox(item: InboxItem) {
        _inbox.value = (listOf(item) + _inbox.value.filter { it.comment.id != item.comment.id }).take(200)
    }

    /** 待人工处理的条目：本轮内存收件箱 + 台账里仍未闭环的 ESCALATED（重启后也不丢） */
    fun pendingManual(): List<InboxItem> {
        val live = _inbox.value.filter { !it.handled }
        val liveIds = live.map { it.comment.id }.toSet()
        val fromLedger = ledger.all()
            .filter { it.status == LedgerStatus.ESCALATED && it.commentId !in liveIds }
            .map { e ->
                InboxItem(
                    comment = Comment(
                        id = e.commentId,
                        targetId = e.targetId,
                        content = e.commentContent.ifBlank { "（内容见台账导出）" }
                    ),
                    decision = Decision(Action.ESCALATE, text = e.replyContent, reason = e.reason),
                    reason = e.reason.ifBlank { "转人工" }
                )
            }
        return live + fromLedger
    }

    fun markInboxHandled(commentId: String, outcome: String) {
        _inbox.value = _inbox.value.map {
            if (it.comment.id == commentId) it.copy(handled = true, outcome = outcome) else it
        }
        ledger.entryFor(commentId)?.takeIf { it.status == LedgerStatus.ESCALATED }?.let { e ->
            ledger.record(e.copy(action = "manual-skip", status = LedgerStatus.SKIPPED,
                reason = outcome.ifBlank { "人工查看后处理" }, atEpochMs = System.currentTimeMillis()))
        }
    }

    private fun putLedger(
        comment: Comment,
        target: Target,
        action: String,
        status: LedgerStatus,
        code: Long = 0,
        reply: String = "",
        reason: String = "",
        error: String = "",
        model: String = "",
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        latencyMs: Long = 0
    ) {
        ledger.record(
            LedgerEntry(
                commentId = comment.id,
                targetId = target.id,
                action = action,
                status = status,
                platformCode = code,
                commentContent = comment.content,
                replyContent = reply,
                reason = reason,
                model = model,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                latencyMs = latencyMs,
                error = error
            )
        )
    }

    private fun record(comment: Comment, verdict: String, decision: Decision?, result: String) {
        _records.value = (listOf(
            RunRecord(comment, verdict, decision, result, LocalTime.now().toString().take(8))
        ) + _records.value).take(500)
    }

    private fun fail(msg: String) {
        bump { it.copy(phase = RunPhase.FAILED, note = msg) }
        AppLogger.e("run", msg)
    }

    private fun readApiKey(ref: String): String? =
        if (ref.isBlank()) null else runCatching { secrets.get(ref)?.let { String(it) } }.getOrNull()

    fun currentTarget(): Target? = _progress.value.target

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_PAGES = 50
        private const val FETCH_PAGE_PAUSE_MS = 1200L
    }
}
