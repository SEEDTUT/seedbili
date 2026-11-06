package com.gaibang.replyagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/* ────────────── 文本与图标 ────────────── */

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current
) {
    MaterialText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        style = style
    )
}

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    MaterialIcon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

/* ────────────── 按钮 ────────────── */

enum class PushButtonStyle { Accent, Secondary, Borderless }

@Composable
fun PushButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PushButtonStyle = PushButtonStyle.Accent,
    enabled: Boolean = true
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            MaterialIcon(icon, null, Modifier.size(17.dp))
        }
        MaterialText(text)
    }
    when (style) {
        PushButtonStyle.Accent -> Button(onClick, modifier, enabled = enabled, content = content)
        PushButtonStyle.Secondary -> FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
        PushButtonStyle.Borderless -> TextButton(onClick, modifier, enabled = enabled, content = content)
    }
}

/** 带自定义内容的版本（图标 + 文案混排、或者纯文字链接） */
@Composable
fun PushButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PushButtonStyle = PushButtonStyle.Accent,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit
) {
    val inner: @Composable RowScope.() -> Unit = {
        if (icon != null) MaterialIcon(icon, null, Modifier.size(17.dp))
        content()
    }
    when (style) {
        PushButtonStyle.Accent -> Button(onClick, modifier, enabled = enabled, content = inner)
        PushButtonStyle.Secondary -> FilledTonalButton(onClick, modifier, enabled = enabled, content = inner)
        PushButtonStyle.Borderless -> TextButton(onClick, modifier, enabled = enabled, content = inner)
    }
}

/* ────────────── 状态指示 ────────────── */

/** 只有图标的小按钮（移除词条、清空输入之类） */
@Composable
fun MiniIconButton(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(28.dp)) {
        MaterialIcon(icon, null, Modifier.size(15.dp))
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(18.dp), strokeWidth = 2.dp)
}

/** 兼容旧调用：0 或不传表示不确定进度 */
@Composable
fun LinearProgress(value: Float = 0f, modifier: Modifier = Modifier) {
    if (value <= 0f) {
        LinearProgressIndicator(modifier = modifier)
    } else {
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = modifier
        )
    }
}

/* ────────────── 输入框 ────────────── */

/**
 * 页面层统一用 String 绑定值；内部保留 TextFieldValue 让中文输入的候选与光标不跳。
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null
) {
    var state by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    Column(modifier) {
        if (header != null) {
            header()
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = state,
            onValueChange = { next ->
                state = next
                if (next.text != value) onValueChange(next.text)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            maxLines = maxLines,
            minLines = minLines,
            placeholder = placeholder,
            shape = AppShape.small,
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

/* ────────────── 容器 ────────────── */

/** 与控件同款的语义色对 */
data class SurfaceColors(val fill: Color, val content: Color)

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = AppShape.medium,
    colors: SurfaceColors? = null,
    tintColor: Color? = null,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    MaterialSurface(
        modifier = modifier,
        shape = shape,
        color = colors?.fill ?: tintColor ?: scheme.surfaceContainerLow,
        contentColor = colors?.content ?: scheme.onSurface,
        border = border,
        content = content
    )
}
