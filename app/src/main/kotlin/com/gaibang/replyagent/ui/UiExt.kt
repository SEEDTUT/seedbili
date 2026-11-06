package com.gaibang.replyagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.app.workflow.RunPhase
import com.gaibang.replyagent.domain.model.Action
import com.gaibang.replyagent.domain.model.RateLimit
import androidx.compose.material3.MaterialTheme

@Composable
fun Stat(label: String, value: Any, color: Color) {
    Surface(
        modifier = Modifier.width(116.dp),
        shape = AppShape.medium,
        colors = SurfaceColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        border = null
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text("$value", style = MaterialTheme.typography.titleLarge, color = color, textAlign = TextAlign.Start)
            Text(
                label,
                style = AppTheme.type.labelSmall,
                color = AppTheme.color.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SurfaceForCard(content: @Composable () -> Unit) {
    Panel(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        content = content
    )
}

@Composable
fun TextButtonRow(text: String, onClick: () -> Unit) {
    PushButton(text = text, onClick = onClick, style = PushButtonStyle.Borderless)
}

@Composable
fun RowOf(spacing: Int = 8, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
}

fun RunPhase.label(): String = when (this) {
    RunPhase.IDLE -> "空闲"
    RunPhase.RESOLVING -> "解析投稿"
    RunPhase.FETCHING -> "抓取评论"
    RunPhase.DECIDING -> "模型判断"
    RunPhase.SENDING -> "发送"
    RunPhase.PAUSED -> "已暂停（风控保护）"
    RunPhase.DONE -> "完成"
    RunPhase.ABORTED -> "已中止"
    RunPhase.FAILED -> "失败"
}

fun Action.labelCn(): String = when (this) {
    Action.REPLY -> "回复"
    Action.SKIP -> "不回"
    Action.ESCALATE -> "转人工"
}

fun RateLimit.describe(): String = buildString {
    append("间隔 ${minIntervalMs / 1000}s+抖动 ${jitterMs / 1000}s")
    append("｜单轮上限 $perRunCap 条｜单日上限 $perDayCap 条")
    append("｜连续失败 $maxConsecutiveFailures 次即停")
}

@Composable
fun TwoColumn(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), content = left)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), content = right)
    }
}

@Composable
fun InlineGap(h: Int = 8) = Spacer(Modifier.height(h.dp))
