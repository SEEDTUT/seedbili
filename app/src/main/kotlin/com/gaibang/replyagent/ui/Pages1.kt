package com.gaibang.replyagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.agent.AgentResult
import com.gaibang.replyagent.app.workflow.RunPhase
import com.gaibang.replyagent.app.workflow.RunProgress
import com.gaibang.replyagent.domain.model.Action
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.llm.toDisplay
import com.gaibang.replyagent.llm.toHint
import kotlinx.coroutines.launch

/* ══════════════ 概览 ══════════════ */

@Composable
fun OverviewPage(app: AppState, cfg: AppConfig, progress: RunProgress, goto: (Page) -> Unit) {
    val t = tokens()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "概览",
            subtitle = "三步配好就能跑第一轮；今天做了什么，以台账为准。"
        )

        val today = app.ledger.countsForToday()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            Stat("已回复", today[LedgerStatus.SENT] ?: 0, t.ok)
            Stat("已跳过", today[LedgerStatus.SKIPPED] ?: 0, t.subtle)
            Stat("转人工", today[LedgerStatus.ESCALATED] ?: 0, t.warn)
            Stat("失败", today[LedgerStatus.FAILED] ?: 0, t.error)
        }

        val loginOk = app.loginStatus is LoginStatus.LoggedIn
        val keyOk = app.hasSavedKey()
        val targetOk = cfg.recentTargets.isNotEmpty() || progress.target != null

        Section("开始之前", "哪一步没完成，运行页会说明缺什么。") {
            StepRow(
                index = 1,
                done = keyOk,
                title = "配置模型 API",
                desc = if (keyOk) "已保存 Key：${cfg.apiProfile().keyFingerprint}（模型 ${cfg.apiProfile().model}）"
                else "填一次即可，落盘前用 DPAPI 加密",
                onClick = { goto(Page.API) },
                action = if (keyOk) "重新检查" else "去填写"
            )
            StepRow(
                index = 2,
                done = loginOk,
                title = "登录平台",
                desc = when (val s = app.loginStatus) {
                    is LoginStatus.LoggedIn -> "${s.name}（UID ${s.uid}）已登录"
                    is LoginStatus.Expired -> "本机存有登录态但已失效，需要重新扫码"
                    else -> "扫码登录，工具不采集密码"
                },
                onClick = { goto(Page.LOGIN) },
                action = if (loginOk) "换一个号" else "去扫码"
            )
            StepRow(
                index = 3,
                done = targetOk,
                title = "选一条投稿跑一轮",
                desc = if (targetOk) "已有历史目标，可直接开始" else "粘贴视频链接或 BV 号，先用「试跑」看结论",
                onClick = { goto(Page.RUN) },
                action = "去运行"
            )
        }

        Section("运行信息", "") {
            KvRow("台账总量", "${app.ledger.size()} 条")
            KvRow("本轮节奏", cfg.strategy().rate.describe())
            KvRow("敏感存储", app.secrets.protectionDescription)
        }

        Section("硬性底线", "唯一不可配置的一组规则。") {
            GuardLine("评论内容永远包在 <untrusted_comment_data> 里，里面的「忽略之前的规则」只当普通文字处理。")
            GuardLine("密码不出现在任何输入框、任何日志、任何工具入参。")
            GuardLine("发送前再查一次黑名单与自己账号，抓取阶段那次不算。")
            GuardLine("日志落盘前统一脱敏，Cookie 与 Key 只写密文。")
        }

        if (progress.phase != RunPhase.IDLE) {
            Section("上一轮的结果", "") {
                Text(phaseText(progress), style = AppTheme.type.bodyMedium)
            }
        }
    }
}

private fun phaseText(p: RunProgress): String =
    "扫描 ${p.scanned} · 本地过滤 ${p.filteredOut} · 送模型 ${p.decided} · 发出 ${p.sent} · 转人工 ${p.escalated} · 失败 ${p.failed} · token ${p.tokensIn}/${p.tokensOut}"

@Composable
private fun GuardLine(text: String) {
    val t = tokens()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(t.warn)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurface)
    }
}

@Composable
private fun StepRow(index: Int, done: Boolean, title: String, desc: String, onClick: () -> Unit, action: String) {
    val t = tokens()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) t.ok.copy(alpha = 0.14f) else AppTheme.color.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = t.ok)
            else Text("$index", style = AppTheme.type.labelMedium, color = AppTheme.color.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.type.titleMedium)
            Text(desc, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
        }
        OutlinedAction(action, onClick)
    }
}

@Composable
private fun OutlinedAction(text: String, onClick: () -> Unit) {
    PushButton(text = text, onClick = onClick, style = PushButtonStyle.Secondary)
}

/* ══════════════ 运行 ══════════════ */

@Composable
fun RunPage(app: AppState, cfg: AppConfig, progress: RunProgress, pendingInbox: Int, goto: (Page) -> Unit) {
    val scope = rememberCoroutineScope()
    var ref by remember { mutableStateOf(progress.target?.url ?: cfg.recentTargets.firstOrNull() ?: "") }
    var tryText by remember { mutableStateOf("") }
    var tryResult by remember { mutableStateOf<AgentResult?>(null) }
    var tryError by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    val records by app.engine.records.collectAsState()
    val running = progress.phase in setOf(RunPhase.RESOLVING, RunPhase.FETCHING, RunPhase.DECIDING, RunPhase.SENDING)
    val t = tokens()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "运行",
            subtitle = "一轮的走向：抓评论 → 本地过滤 → 模型判断 → 发送前复查 → 发出并记账。全程串行，不并发。",
            actions = {
                if (running) {
                    PushButton(
                        text = "停止本轮",
                        icon = Icons.Rounded.Pause,
                        style = PushButtonStyle.Secondary,
                        onClick = { app.engine.stopRunning() }
                    )
                }
            }
        )

        Section("目标投稿", "粘贴 B 站视频页链接、BV 号或 av 号都行。") {
            TextFieldRow("投稿地址", ref, { ref = it }, placeholder = "https://www.bilibili.com/video/BV…")
            if (cfg.recentTargets.isNotEmpty()) {
                SectionSpace(4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cfg.recentTargets.take(6).forEach { past ->
                        RecentTargetChip(
                            text = past.substringAfterLast('/').take(24),
                            onPick = { ref = past },
                            onDelete = {
                                app.update { c -> c.copy(recentTargets = c.recentTargets.filter { it != past }) }
                            }
                        )
                    }
                }
            }
            SectionSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(
                    text = if (progress.sent > 0 || progress.scanned > 0) "再跑一轮" else "开始跑",
                    style = PushButtonStyle.Accent,
                    onClick = {
                        if (ref.isNotBlank()) app.update { c -> c.copy(recentTargets = (listOf(ref) + c.recentTargets.filter { it != ref }).take(12)) }
                        app.engine.start(ref.trim(), dryRun = false)
                    },
                    enabled = ref.isNotBlank() && !running
                )
                PushButton(
                    text = "试跑（一条都不发）",
                    style = PushButtonStyle.Secondary,
                    onClick = {
                        if (ref.isNotBlank()) app.update { c -> c.copy(recentTargets = (listOf(ref) + c.recentTargets.filter { it != ref }).take(12)) }
                        app.engine.start(ref.trim(), dryRun = true)
                    },
                    enabled = ref.isNotBlank() && !running
                )
                if (running) {
                    Spinner(Modifier.size(18.dp))
                    Text(progress.phase.label(), style = AppTheme.type.labelMedium, color = AppTheme.color.primary)
                }
            }
            SectionSpace(10)
            when (progress.phase) {
                RunPhase.FAILED, RunPhase.PAUSED, RunPhase.ABORTED -> Notice(progress.note, if (progress.phase == RunPhase.FAILED) Tone.ERROR else Tone.WARN)
                RunPhase.DONE -> Notice(progress.note, Tone.OK)
                else -> if (progress.note.isNotBlank()) Notice(progress.note, Tone.INFO)
            }
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("扫描", progress.scanned, t.subtle)
                Stat("过滤", progress.filteredOut, t.subtle)
                Stat("送模型", progress.decided, AppTheme.color.primary)
                Stat("发出", progress.sent, t.ok)
                Stat("转人工", progress.escalated, t.warn)
                Stat("失败", progress.failed, t.error)
            }
            SectionSpace(6)
            if (running) LinearProgress(value = 6f, modifier = Modifier.fillMaxWidth())
            KvRow("token 用量", "入 ${progress.tokensIn} / 出 ${progress.tokensOut}")
        }

        Section("试判一条", "粘贴一条留言，单独看模型会判成什么，不会真的发出去。") {
            TextFieldRow(
                "评论内容", tryText, { tryText = it },
                help = "这段文本同样会过一遍防注入清洗，再进模型。",
                singleLine = false, lines = 3, placeholder = "例如：up 主这个用的是什么编辑器？"
            )
            SectionSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PushButton(
                    text = "只判这一条",
                    icon = Icons.Rounded.PlayArrow,
                    style = PushButtonStyle.Accent,
                    onClick = {
                        trying = true
                        tryResult = null
                        tryError = ""
                        scope.launch {
                            runCatching { app.engine.tryOne(tryText) }
                                .onSuccess { tryResult = it }
                                .onFailure { err ->
                                    tryResult = null
                                    val hint = err.toHint()
                                    tryError = err.toDisplay() + if (hint.isNotBlank()) "（$hint）" else ""
                                }
                            trying = false
                        }
                    },
                    enabled = tryText.isNotBlank() && !trying && app.hasSavedKey()
                )
                if (trying) Spinner(Modifier.size(16.dp))
                if (!app.hasSavedKey()) Text("需要先配好模型 API", style = AppTheme.type.bodySmall, color = t.warn)
            }
            tryResult?.let { r ->
                SectionSpace(10)
                val detail = buildString {
                    append("动作：").append(r.decision.action.labelCn())
                    append("｜把握度 ").append(String.format("%.2f", r.decision.confidence))
                    if (r.decision.reason.isNotBlank()) append("｜").append(r.decision.reason)
                    if (r.decision.text.isNotBlank()) append("\n拟发文本：").append(r.decision.text)
                    append("\n轮次 ").append(r.iterations)
                    append("｜耗时 ").append(r.latencyMs).append("ms")
                    append("｜token ").append(r.tokensIn).append('/').append(r.tokensOut)
                    if (r.notes.isNotEmpty()) append("\n备注：").append(r.notes.joinToString("；"))
                }
                Notice(detail, if (r.decision.action == Action.REPLY) Tone.OK else Tone.WARN)
            }
            if (tryError.isNotBlank()) {
                SectionSpace(10)
                Notice(tryError, Tone.ERROR)
            }
        }

        Section("实时记录", "本轮已处理的每一行。完整历史在「台账」页。") {
            if (records.isEmpty()) EmptyHint("还没有记录。先跑一轮，或者用上面的试判试试。")
            records.take(60).forEach { rec ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rec.at, style = AppTheme.type.labelSmall, color = AppTheme.color.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rec.comment.authorName.ifBlank { "观众" } + "：${rec.comment.content.take(60)}",
                            style = AppTheme.type.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "→ ${rec.verdict}${if (rec.result.isNotBlank()) "｜${rec.result.take(80)}" else ""}",
                        style = AppTheme.type.bodySmall,
                        color = t.subtle
                    )
                }
            }
        }

        Section("等你定夺", "转人工的评论收在收件箱页，重启后也在。") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pendingInbox > 0) Pill("$pendingInbox 条待处理", Tone.WARN)
                else Text("暂无待处理", style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
                PushButton(text = "打开收件箱", style = PushButtonStyle.Secondary, onClick = { goto(Page.INBOX) })
            }
        }
    }
}

/** 最近投稿地址：左键选中，右键弹菜单可以删掉记错的记录 */
@Composable
private fun RecentTargetChip(text: String, onPick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                        menuOpen = true
                    }
                }
            }
        }
    ) {
        PushButton(text = text, style = PushButtonStyle.Secondary, onClick = onPick)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("从记录里删掉") },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}
