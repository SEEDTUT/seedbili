package com.gaibang.replyagent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/**
 * 主题 = Material 3 + Material You。
 * 只给一个种子色（SEEDTUT 的品牌橙），整套色板由 material-kolor 从种子推导，
 * 页面不手写任何颜色值，深浅色自动成对。
 */
private val SeedOrange = Color(0xFFF26522)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

/** 桌面端不需要手机那套超大字号，整体压紧一档，字重与字距沿用 M3 的比例。 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp
    )
)

class AppTokens(
    val cardBorder: Color,
    val divider: Color,
    val subtle: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
    val accentSoft: Color,
    val elevated: Color
)

@Composable
fun ReplyAgentTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = rememberDynamicColorScheme(
        seedColor = SeedOrange,
        isDark = dark,
        style = PaletteStyle.Fidelity
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        val tokens = AppTokens(
            cardBorder = scheme.outlineVariant,
            divider = scheme.outlineVariant,
            subtle = scheme.onSurfaceVariant,
            ok = if (dark) Color(0xFF7BD88F) else Color(0xFF1B7A34),
            warn = if (dark) Color(0xFFFFC46B) else Color(0xFF8A5A00),
            error = scheme.error,
            accentSoft = scheme.secondaryContainer,
            elevated = scheme.surfaceContainerHigh
        )
        CompositionLocalProvider(LocalAppTokens provides tokens) { content() }
    }
}

val LocalAppTokens = staticCompositionLocalOf {
    AppTokens(
        cardBorder = Color(0xFFE2E2E6),
        divider = Color(0xFFD8D8DE),
        subtle = Color(0xFF5F5F66),
        ok = Color(0xFF1B7A34),
        warn = Color(0xFF8A5A00),
        error = Color(0xFFBA1A1A),
        accentSoft = Color(0xFFFFDBCB),
        elevated = Color(0xFFF4F4F7)
    )
}

@Composable
fun tokens(): AppTokens = LocalAppTokens.current

/**
 * 页面里沿用的语义名（titleSmall / onSurfaceVariant …）在这里一次性映射到
 * Material 3 的 Typography 与 ColorScheme，页面代码不用关心底层叫什么。
 */
@Stable
class AppColors(
    val primary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val surface: Color,
    val surfaceContainerLow: Color
)

@Stable
class AppType(
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle
)

object AppTheme {
    val color: AppColors
        @Composable
        get() {
            val c = MaterialTheme.colorScheme
            return AppColors(
                primary = c.primary,
                onSurface = c.onSurface,
                onSurfaceVariant = c.onSurfaceVariant,
                surface = c.surface,
                surfaceContainerLow = c.surfaceContainerLow
            )
        }

    val type: AppType
        @Composable
        get() {
            val t = MaterialTheme.typography
            return AppType(
                body = t.bodyLarge,
                bodyMedium = t.bodyMedium,
                bodySmall = t.bodySmall,
                headlineMedium = t.headlineMedium,
                headlineSmall = t.headlineSmall,
                titleLarge = t.titleLarge,
                titleMedium = t.titleMedium,
                titleSmall = t.titleSmall,
                labelMedium = t.labelLarge,
                labelSmall = t.labelSmall
            )
        }
}

/** 页面里用到的圆角档位，与 M3 shapes 的 8 / 12 / 18 / 28 dp 对应 */
object AppShape {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(18.dp)
    val extraLarge = RoundedCornerShape(28.dp)
}
