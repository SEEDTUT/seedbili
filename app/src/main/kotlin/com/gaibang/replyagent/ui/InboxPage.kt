package com.gaibang.replyagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.app.workflow.InboxItem
import kotlinx.coroutines.launch

/* ══════════════ 收件箱 ══════════════ */

@Composable
fun InboxPage(app: AppState, goto: (Page) -> Unit) {
    val inbox by app.engine.inbox.collectAsState()
    val ledgerEntries by app.ledger.entries.collectAsState()
    val pending = remember(inbox, ledgerEntries) { app.engine.pendingManual() }
    var flash by remember { mutableStateOf("") }
    var flashTone by remember { mutableStateOf(Tone.INFO) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "收件箱",
            subtitle = "模型没把握或规则拦下转人工的评论都收在这里，重启后也不丢。改几个字直接发，或者决定不回。"
        )

        if (flash.isNotBlank()) {
            Notice(flash, flashTone)
            SectionSpace(10)
        }

        Section(
            if (pending.isEmpty()) "待处理" else "待处理（${pending.size}）",
            "发送走你自己的登录态，和自动回复同一个接口；每一条发没发出去都以台账为准。"
        ) {
            if (pending.isEmpty()) {
                EmptyHint("没有待处理的评论。跑一轮之后，需要你拿主意的会自动到这里。")
                SectionSpace(8)
                Row {
                    PushButton(text = "去运行一轮", style = PushButtonStyle.Secondary, onClick = { goto(Page.RUN) })
                }
            } else {
                pending.forEach { item -> ManualRow(app, item, onFlash = { text, tone ->
                    flash = text
                    flashTone = tone
                }) }
            }
        }
    }
}

@Composable
private fun ManualRow(app: AppState, item: InboxItem, onFlash: (String, Tone) -> Unit) {
    val scope = rememberCoroutineScope()
    val t = tokens()
    var draft by remember(item.comment.id) { mutableStateOf(item.decision.text) }
    var sending by remember(item.comment.id) { mutableStateOf(false) }
    val who = item.comment.authorName.ifBlank { "观众" }

    SurfaceForCard {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.comment.authorUid.isBlank()) who else "$who（UID ${item.comment.authorUid}）",
                    style = AppTheme.type.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Pill("转人工", Tone.WARN)
            }
            SectionSpace(4)
            Text(item.comment.content, style = AppTheme.type.bodySmall, maxLines = 4)
            if (item.reason.isNotBlank()) {
                SectionSpace(4)
                Text("为什么转人工：${item.reason}", style = AppTheme.type.bodySmall, color = t.warn)
            }
            SectionSpace(8)
            TextField(
                value = draft,
                onValueChange = { draft = it },
                minLines = 2,
                maxLines = 5,
                placeholder = { Text("写下你会怎么回", style = AppTheme.type.bodyMedium) }
            )
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(
                    text = "按这段发送",
                    style = PushButtonStyle.Accent,
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            val r = runCatching { app.engine.sendManual(item, draft.trim()) }
                            val outcome = r.getOrNull()
                            when {
                                outcome == null -> onFlash(
                                    "发送异常：${r.exceptionOrNull()?.message.orEmpty()}", Tone.ERROR
                                )
                                outcome.ok -> {
                                    app.engine.markInboxHandled(item.comment.id, "人工回复已发送")
                                    onFlash(outcome.note, Tone.OK)
                                }
                                else -> onFlash(outcome.note, Tone.ERROR)
                            }
                            sending = false
                        }
                    }
                )
                TextButtonRow("这条不回") {
                    app.engine.markInboxHandled(item.comment.id, "人工查看后决定不回")
                    onFlash("已标记不回，这条不会再出现在收件箱", Tone.INFO)
                }
                if (sending) Spinner(Modifier.size(16.dp))
            }
        }
    }
}
