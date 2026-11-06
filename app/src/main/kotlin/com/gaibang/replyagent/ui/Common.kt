package com.gaibang.replyagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.logging.AppLogger

/* ────────────── 页面骨架 ────────────── */

@Composable
fun PageHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    val ty = AppTheme.type
    Column(Modifier.padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = ty.headlineMedium, color = AppTheme.color.onSurface)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = ty.bodyMedium, color = AppTheme.color.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/**
 * 分区面板：Material 3 的 tonal surface —— 没有描边，靠色阶和圆角分层，
 * 一页里最多两三层，避免到处是分割线的「控制台感」。
 */
@Composable
fun Section(
    title: String,
    desc: String = "",
    tone: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val ty = AppTheme.type
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        shape = AppShape.large,
        colors = SurfaceColors(
            if (tone != null) tone.copy(alpha = 0.07f) else scheme.surfaceContainerLow,
            scheme.onSurface
        ),
        border = null
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                title,
                style = ty.titleMedium,
                color = tone ?: AppTheme.color.onSurface
            )
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(desc, style = ty.bodySmall, color = AppTheme.color.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = AppShape.large,
        colors = SurfaceColors(fill, contentColor),
        border = border,
        content = content
    )
}

@Composable
fun Notice(text: String, tone: Tone = Tone.INFO, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (icon, color) = when (tone) {
        Tone.INFO -> Icons.Rounded.Info to scheme.primary
        Tone.OK -> Icons.Rounded.CheckCircle to tokens().ok
        Tone.WARN -> Icons.Rounded.WarningAmber to tokens().warn
        Tone.ERROR -> Icons.Rounded.ErrorOutline to scheme.error
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.medium,
        colors = SurfaceColors(
            if (tone == Tone.ERROR) scheme.errorContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh,
            scheme.onSurface
        ),
        border = null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, Modifier.padding(top = 1.dp).size(17.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = AppTheme.type.bodySmall,
                color = AppTheme.color.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

enum class Tone { INFO, OK, WARN, ERROR }

@Composable
fun Pill(text: String, tone: Tone = Tone.INFO) {
    val scheme = MaterialTheme.colorScheme
    val color = when (tone) {
        Tone.INFO -> scheme.primary
        Tone.OK -> tokens().ok
        Tone.WARN -> tokens().warn
        Tone.ERROR -> scheme.error
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, style = AppTheme.type.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun EmptyHint(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
    }
}

/* ────────────── 表单控件 ────────────── */

@Composable
fun Label(text: String, help: String = "") {
    Column(Modifier.padding(bottom = 7.dp)) {
        Text(text, style = AppTheme.type.titleSmall)
        if (help.isNotBlank()) {
            Text(
                help,
                style = AppTheme.type.bodySmall,
                color = AppTheme.color.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    help: String = "",
    singleLine: Boolean = true,
    lines: Int = 4,
    placeholder: String = "",
    /** 密钥类输入：界面上不回显明文 */
    secret: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Label(label, help)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            placeholder = {
                if (placeholder.isNotBlank()) {
                    Text(placeholder, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
                }
            },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            minLines = if (singleLine) 1 else lines,
            maxLines = if (singleLine) 1 else lines + 6,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun IntRow(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    help: String = "",
    unit: String = "",
    range: LongRange = 0L..100_000_000L,
    modifier: Modifier = Modifier
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier) {
        Label(label, help)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = draft,
                onValueChange = { raw ->
                    val cleaned = raw.filter { it.isDigit() || it == '-' }
                    draft = cleaned
                    cleaned.toLongOrNull()?.let { onValueChange(it.coerceIn(range.first, range.last)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            if (unit.isNotBlank()) {
                Text(unit, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    help: String = "",
    range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    steps: Int = 19,
    fmt: (Double) -> String = { String.format("%.2f", it) },
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Label(label, help) }
            Text(
                fmt(value),
                style = AppTheme.type.titleSmall,
                color = AppTheme.color.primary
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String = "",
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val ty = AppTheme.type
    Row(
        modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = ty.bodyMedium,
                color = if (enabled) AppTheme.color.onSurface else AppTheme.color.onSurfaceVariant
            )
            if (help.isNotBlank()) {
                Text(help, style = ty.bodySmall, color = AppTheme.color.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    help: String = "",
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    render: (T) -> String,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Label(label, help)
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = render(selected),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                shape = AppShape.small,
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(render(option), style = AppTheme.type.bodyMedium) },
                        onClick = {
                            onSelected(option)
                            open = false
                        },
                        leadingIcon = if (option == selected) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

/** 字符串列表编辑器：加一个词、删一个词，全部即刻写回配置 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsEditor(
    label: String,
    items: List<String>,
    onChange: (List<String>) -> Unit,
    help: String = "",
    placeholder: String = "输入后回车添加"
) {
    var draft by remember { mutableStateOf("") }
    Column {
        Label(label, help)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(item, style = AppTheme.type.labelMedium) },
                    trailingIcon = {
                        MiniIconButton(Icons.Rounded.Close) { onChange(items - item) }
                    },
                    shape = AppShape.small
                )
            }
        }
        if (items.isNotEmpty()) Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = {
                        Text(placeholder, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PushButton(
                text = "添加",
                icon = Icons.Rounded.Add,
                style = PushButtonStyle.Secondary,
                enabled = draft.isNotBlank(),
                onClick = {
                    val v = draft.trim()
                    if (v.isNotEmpty() && v !in items) {
                        onChange(items + v)
                        draft = ""
                    }
                }
            )
        }
    }
}

@Composable
fun MonoBlock(text: String, maxHeightDp: Int = 220, copyable: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.medium,
        colors = SurfaceColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface),
        border = null
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (copyable) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    PushButton(
                        text = "复制",
                        icon = Icons.Rounded.ContentCopy,
                        style = PushButtonStyle.Borderless,
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            AppLogger.d("ui", "已复制到剪贴板（${text.length} 字）")
                        }
                    )
                }
            }
            Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp)) {
                Text(
                    text.ifBlank { "(空)" },
                    style = AppTheme.type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AppTheme.color.onSurface,
                    maxLines = maxHeightDp / 16,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
fun KvRow(key: String, value: String, keyWeight: Float = 0.34f) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key,
            style = AppTheme.type.bodySmall,
            color = AppTheme.color.onSurfaceVariant,
            modifier = Modifier.weight(keyWeight)
        )
        Text(
            value,
            style = AppTheme.type.bodySmall,
            color = AppTheme.color.onSurface,
            modifier = Modifier.weight(1f - keyWeight)
        )
    }
}

/* ────────────── 按钮 ────────────── */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    PushButton(text = text, onClick = onClick, modifier = modifier, icon = icon, enabled = enabled)
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    PushButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        style = PushButtonStyle.Secondary,
        enabled = enabled
    )
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    PushButton(text = text, onClick = onClick, icon = icon, style = PushButtonStyle.Borderless, enabled = enabled)
}

@Composable
fun SectionSpace(height: Int = 8) = Spacer(Modifier.height(height.dp))

/** 让 Checkbox 行在整行可点 */
@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .clickable { onChange(!checked) }
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(text, style = AppTheme.type.bodyMedium)
    }
}
