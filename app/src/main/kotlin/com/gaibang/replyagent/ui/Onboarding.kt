package com.gaibang.replyagent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.app.sandbox.EnvInstallStage
import com.gaibang.replyagent.app.sandbox.EnvStep
import com.gaibang.replyagent.app.sandbox.StepStatus
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.LoginStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ══════════════ 首次启动片头 ══════════════ */

@Composable
fun FirstRunIntro(onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val wordChars = remember { "seedbili".toList() }

    LaunchedEffect(Unit) {
        launch { ripple.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        mark.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 360f))
        word.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        delay(1_150)
        fade.animateTo(0f, tween(320))
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDone() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = fade.value }
        ) {
            Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
                // 一次性扩散的水波纹，Material 的点击语言，不转圈
                Box(
                    Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = 0.55f + ripple.value * 0.65f
                            scaleY = 0.55f + ripple.value * 0.65f
                            alpha = (1f - ripple.value) * 0.55f
                        }
                        .background(scheme.primary.copy(alpha = 0.16f), CircleShape)
                )
                Image(
                    bitmap = remember { AppIcon.bitmap(192) },
                    contentDescription = "seedbili",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(AppShape.extraLarge)
                        .graphicsLayer {
                            scaleX = 0.82f + mark.value * 0.18f
                            scaleY = 0.82f + mark.value * 0.18f
                            alpha = mark.value
                            translationY = (1f - mark.value) * 10f
                        }
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.Start) {
                wordChars.forEachIndexed { i, c ->
                    val start = i.toFloat() / wordChars.size * 0.5f
                    val local = ((word.value - start) / 0.5f).coerceIn(0f, 1f)
                    Text(
                        c.toString(),
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        modifier = Modifier.graphicsLayer {
                            alpha = local
                            translationY = (1f - local) * 16f
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "SEEDTUT LAB · B 站评论区自动回复",
                style = AppTheme.type.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    translationY = (1f - word.value) * 8f
                }
            )
        }

        Text(
            "点击任意处跳过",
            style = AppTheme.type.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .graphicsLayer { alpha = word.value * 0.6f }
        )
    }
}

/* ══════════════ 引导向导 ══════════════ */

private val OB_STEPS = listOf(
    Triple("准备环境", "命令在哪跑", 0),
    Triple("扫码登录", "手机 B 站确认", 1),
    Triple("模型 API", "填 Base URL 和 Key", 2),
    Triple("开始使用", "可以跑第一轮了", 3)
)

@Composable
fun OnboardingWizard(app: AppState, cfg: AppConfig) {
    val t = tokens()
    val step = app.onboardingStep

    LaunchedEffect(step) {
        if (step == 1 && app.loginStatus !is LoginStatus.LoggedIn && app.qrImage == null && !app.loginBusy) {
            app.startQrLogin()
        }
    }
    // 环境装完 → 自动下一步
    LaunchedEffect(app.installStage, step) {
        if (step == 0 && app.installStage == EnvInstallStage.DONE) {
            delay(1_400)
            app.onboardingStep = 1
        }
    }
    // 选了本机 cmd → 没有安装过程，稍等一下自动下一步
    LaunchedEffect(app.envChoice, step) {
        if (step == 0 && app.envChoice == "cmd") {
            delay(1_000)
            app.onboardingStep = 1
        }
    }
    // 登录成功 → 自动下一步
    LaunchedEffect(app.loginStatus, step) {
        if (step == 1 && app.loginStatus is LoginStatus.LoggedIn) {
            delay(1_500)
            app.onboardingStep = 2
        }
    }
    // 自检通过 → 自动下一步
    LaunchedEffect(app.probeReport, step) {
        val r = app.probeReport
        if (step == 2 && r != null && !r.failed && r.toolsOk) {
            delay(1_400)
            app.onboardingStep = 3
        }
    }

    Row(
        Modifier.fillMaxSize()
    ) {
        // 左侧步骤栏
        Surface(
            colors = SurfaceColors(AppTheme.color.surfaceContainerLow, AppTheme.color.onSurface),
            modifier = Modifier.width(268.dp).fillMaxHeight()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon.BrandMark(28)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("seedbili", style = AppTheme.type.titleSmall)
                        Text("首次使用", style = AppTheme.type.labelSmall, color = t.subtle)
                    }
                }
                Spacer(Modifier.height(34.dp))
                OB_STEPS.forEachIndexed { i, (title, sub, _) ->
                    StepRailItem(i, title, sub, step, t)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "整个过程没有密码输入口：登录只有扫码这一条路。",
                    style = AppTheme.type.labelSmall,
                    color = t.subtle
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 26.dp, top = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 ${step + 1} 步 / 共 4 步",
                    style = AppTheme.type.labelMedium,
                    color = t.subtle
                )
                Spacer(Modifier.weight(1f))
                PushButton(style = PushButtonStyle.Borderless, onClick = { app.finishOnboarding() }) { Text("跳过引导") }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 40.dp, end = 32.dp, top = 8.dp, bottom = 20.dp)
            ) {
                when (step) {
                    0 -> EnvStepContent(app)
                    1 -> LoginStepContent(app)
                    2 -> ApiStepContent(app, cfg)
                    else -> DoneStepContent(app)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 32.dp, top = 8.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PushButton(style = PushButtonStyle.Secondary, 
                    onClick = { app.onboardingStep = (step - 1).coerceAtLeast(0) },
                    enabled = step > 0
                ) { Text("上一步") }
                Spacer(Modifier.weight(1f))
                Text(
                    when (step) {
                        0 -> when {
                            app.installStage == EnvInstallStage.DONE -> "WSL 环境已就绪"
                            app.envChoice == "cmd" -> "本机 cmd，点下一步继续"
                            app.envChoice == "wsl" -> "装完会自动进入下一步"
                            app.envSkipped -> "已跳过，点下一步继续"
                            else -> "先选一个执行环境"
                        }
                        1 -> if (app.loginStatus is LoginStatus.LoggedIn) "已登录" else "扫码成功后自动继续"
                        2 -> "自检通过后自动继续"
                        else -> ""
                    },
                    style = AppTheme.type.bodySmall,
                    color = t.subtle
                )
                Spacer(Modifier.width(14.dp))
                PushButton(style = PushButtonStyle.Accent, 
                    onClick = {
                        if (step == 3) app.finishOnboarding()
                        else app.onboardingStep = (step + 1).coerceAtMost(3)
                    },
                    enabled = when (step) {
                        0 -> app.installStage == EnvInstallStage.DONE || app.envSkipped || app.envChoice == "cmd"
                        1 -> app.loginStatus is LoginStatus.LoggedIn
                        2 -> app.hasSavedKey()
                        else -> true
                    }
                ) { Text(if (step == 3) "开始使用" else "下一步") }
            }
        }
    }
}

@Composable
private fun StepRailItem(index: Int, title: String, sub: String, current: Int, t: AppTokens) {
    val accent = AppTheme.color.primary
    val done = index < current
    val active = index == current
    Row(Modifier.height(62.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> accent
                            active -> accent.copy(alpha = 0.14f)
                            else -> t.divider
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    done -> Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = Color.White)
                    active -> Text(
                        "${index + 1}",
                        style = AppTheme.type.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = accent
                    )
                    else -> Text("${index + 1}", style = AppTheme.type.labelSmall, color = t.subtle)
                }
            }
            if (index < OB_STEPS.size - 1) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(36.dp)
                        .background(if (done) accent.copy(alpha = 0.5f) else t.divider)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(top = 3.dp)) {
            Text(
                title,
                style = AppTheme.type.bodyMedium.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (active) AppTheme.color.onSurface else t.subtle
            )
            Text(sub, style = AppTheme.type.labelSmall, color = t.subtle)
        }
    }
}

/* ── 第 1 步：环境 ── */

@Composable
private fun EnvStepContent(app: AppState) {
    val t = tokens()
    val running = app.installStage == EnvInstallStage.RUNNING
    val choice = app.envChoice

    Text(
        "命令在哪执行？",
        style = AppTheme.type.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "只影响 Agent 需要跑 shell 的那部分（附件处理、脚本）。两个都能用，区别是隔不隔离、要不要装东西。先选，再往下走。",
        style = AppTheme.type.bodyMedium,
        color = AppTheme.color.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EnvChoiceCard(
            modifier = Modifier.weight(1f),
            selected = choice == "cmd",
            title = "本机 cmd",
            badge = "无需安装",
            desc = "直接用 Windows 自带的命令解释器。零下载、立刻可用；代价是没有文件系统隔离，模型要跑的命令会真的落在你这台机器上。",
            points = listOf("不占额外空间", "不要管理员权限", "无隔离"),
            icon = Icons.Rounded.Terminal,
            onClick = { app.chooseLocalEnv() }
        )
        EnvChoiceCard(
            modifier = Modifier.weight(1f),
            selected = choice == "wsl",
            title = "WSL + Debian",
            badge = "隔离更好",
            desc = "在 Linux 里执行，和你的桌面文件隔开。工具会自动装：开 WSL → 装 Debian → 补基础工具，约 300–500MB。",
            points = listOf("真隔离", "自动安装", "要一次 UAC"),
            icon = Icons.Rounded.DeveloperBoard,
            onClick = { app.chooseWslEnv() }
        )
    }
    Spacer(Modifier.height(16.dp))

    when (choice) {
        "cmd" -> {
            Notice(
                "已选本机 cmd：这一轮不会下载或安装任何东西，可以直接进入下一步。" +
                    "以后想换 WSL，去「Linux 环境」页点「一键自动安装」即可。",
                Tone.OK
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "命令默认还是关着的：到「Linux 环境」页手动打开 run_command，并且每条命令都要你确认。",
                    style = AppTheme.type.bodySmall,
                    color = t.subtle
                )
                Spacer(Modifier.weight(1f))
                GhostButton("改成 WSL + Debian", onClick = { app.chooseWslEnv() })
            }
        }
        "wsl" -> {
            Notice(
                "接下来只会做这几件事：开 WSL、装一个 Debian、apt 装基础命令行工具。" +
                    "不碰你的账号、密码、浏览器数据，也不改任何安全设置。",
                Tone.INFO
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> PushButton(style = PushButtonStyle.Secondary, onClick = { app.cancelEnvInstall() }) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("取消安装")
                    }
                    app.installStage == EnvInstallStage.DONE -> PushButton(style = PushButtonStyle.Secondary, onClick = { app.startEnvInstall() }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新检测")
                    }
                    else -> PushButton(style = PushButtonStyle.Accent, onClick = { app.startEnvInstall() }) {
                        Text(if (app.installStage == EnvInstallStage.IDLE) "开始自动安装" else "继续安装")
                    }
                }
                if (running) Spinner(Modifier.size(16.dp))
                Spacer(Modifier.weight(1f))
                if (app.installStage != EnvInstallStage.DONE) {
                    GhostButton("不装了，换成本机 cmd", onClick = { app.chooseLocalEnv() })
                }
            }
            Spacer(Modifier.height(18.dp))
            EnvInstallPanel(app)
        }
        else -> {
            Text(
                "拿不准就选本机 cmd：功能一样，只是没有隔离，随时可以换。",
                style = AppTheme.type.bodySmall,
                color = t.subtle
            )
            Spacer(Modifier.height(6.dp))
            GhostButton("两个都不用，先跳过", onClick = {
                app.skipEnvInstall()
                app.onboardingStep = 1
            })
        }
    }
}

@Composable
private fun EnvChoiceCard(
    modifier: Modifier = Modifier,
    selected: Boolean,
    title: String,
    badge: String,
    desc: String,
    points: List<String>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val t = tokens()
    val accent = AppTheme.color.primary
    Surface(
        shape = AppShape.medium,
        tintColor = if (selected) t.accentSoft.copy(alpha = 0.4f) else null,
        colors = SurfaceColors(if (selected) t.accentSoft else AppTheme.color.surface, AppTheme.color.onSurface),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) accent else t.cardBorder
        ),
        modifier = modifier
            .clip(AppShape.medium)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) accent.copy(alpha = 0.16f) else t.divider.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, Modifier.size(18.dp), tint = if (selected) accent else t.subtle)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = AppTheme.type.titleSmall)
                    Text(
                        badge,
                        style = AppTheme.type.labelSmall,
                        color = if (selected) accent else t.subtle
                    )
                }
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) accent else t.divider.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                desc,
                style = AppTheme.type.bodySmall,
                color = AppTheme.color.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            points.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (selected) accent else t.subtle))
                    Spacer(Modifier.width(7.dp))
                    Text(p, style = AppTheme.type.labelSmall, color = t.subtle)
                }
            }
        }
    }
}

/** 环境安装的进度条 + 实时日志。引导页和「Linux 环境」页共用这一份。 */
@Composable
fun EnvInstallPanel(app: AppState) {
    val t = tokens()
    val steps = EnvStep.plan()

    Surface(
        shape = AppShape.medium,
        colors = SurfaceColors(AppTheme.color.surface, AppTheme.color.onSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, t.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            steps.forEachIndexed { i, s ->
                val st = app.installStatus.getOrNull(i) ?: StepStatus.PENDING
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
                    StepDot(st, t)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            style = AppTheme.type.bodyMedium,
                            color = if (st == StepStatus.PENDING) t.subtle else AppTheme.color.onSurface
                        )
                        Text(
                            app.installNotes[i] ?: s.detail,
                            style = AppTheme.type.labelSmall,
                            color = when (st) {
                                StepStatus.FAILED -> t.error
                                StepStatus.OK -> t.ok
                                else -> t.subtle
                            }
                        )
                    }
                    when (st) {
                        StepStatus.RUNNING -> Spinner(Modifier.size(15.dp))
                        StepStatus.OK -> Text("完成", style = AppTheme.type.labelSmall, color = t.ok)
                        StepStatus.SKIPPED -> Text("跳过", style = AppTheme.type.labelSmall, color = t.subtle)
                        StepStatus.FAILED -> Text("失败", style = AppTheme.type.labelSmall, color = t.error)
                        StepStatus.PENDING -> Text("等待", style = AppTheme.type.labelSmall, color = t.subtle)
                    }
                }
            }
        }
    }

    app.installResult?.let { msg ->
        Spacer(Modifier.height(14.dp))
        val tone = when (app.installStage) {
            EnvInstallStage.DONE -> Tone.OK
            EnvInstallStage.NEED_REBOOT -> Tone.WARN
            EnvInstallStage.CANCELLED -> Tone.WARN
            else -> Tone.ERROR
        }
        Notice(msg, tone)
        if (app.installStage == EnvInstallStage.NEED_REBOOT) {
            Spacer(Modifier.height(10.dp))
            Text(
                "重启后重新打开这个工具，会直接回到这一步，点「继续安装」即可。",
                style = AppTheme.type.bodySmall,
                color = AppTheme.color.onSurfaceVariant
            )
        }
    }

    if (app.installLog.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        MonoBlock(app.installLog, maxHeightDp = 190)
    }
}

@Composable
private fun StepDot(st: StepStatus, t: AppTokens) {
    val run = AppTheme.color.primary
    val color = when (st) {
        StepStatus.OK -> t.ok
        StepStatus.FAILED -> t.error
        StepStatus.RUNNING -> run
        else -> t.divider
    }
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = if (st == StepStatus.PENDING) 1f else 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        when (st) {
            StepStatus.OK -> Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = t.ok)
            StepStatus.FAILED -> Icon(Icons.Rounded.Close, null, Modifier.size(12.dp), tint = t.error)
            else -> Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
    }
}

/* ── 第 2 步：登录 ── */

@Composable
private fun LoginStepContent(app: AppState) {
    val t = tokens()
    Text(
        "扫码登录 B 站",
        style = AppTheme.type.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "二维码由本机生成，不经过任何第三方。手机 B 站 App → 我的 → 右上角扫码，确认后会自动进入下一步。",
        style = AppTheme.type.bodyMedium,
        color = AppTheme.color.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))

    val logged = app.loginStatus as? LoginStatus.LoggedIn

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(
            Modifier.size(248.dp).clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            val qr = app.qrImage
            when {
                logged != null -> Surface(
                    shape = AppShape.medium,
                    colors = SurfaceColors(AppTheme.color.surface, AppTheme.color.onSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, t.ok.copy(alpha = 0.45f))
                ) {
                    Column(
                        Modifier.size(240.dp).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(58.dp).clip(CircleShape).background(t.ok.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(30.dp), tint = t.ok)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(logged.name, style = AppTheme.type.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "UID ${logged.uid}",
                            style = AppTheme.type.bodySmall,
                            color = t.subtle
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "登录态已加密保存在本机",
                            style = AppTheme.type.labelSmall,
                            color = t.ok
                        )
                    }
                }
                qr != null -> Image(qr, "登录二维码", Modifier.size(240.dp))
                else -> Surface(
                    shape = AppShape.medium,
                    colors = SurfaceColors(AppTheme.color.surface, AppTheme.color.onSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, t.cardBorder)
                ) {
                    Column(
                        Modifier.size(240.dp).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (app.loginBusy) Spinner(Modifier.size(22.dp))
                        Text(
                            app.loginNote.ifBlank { "点「获取二维码」" },
                            style = AppTheme.type.bodySmall,
                            color = AppTheme.color.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            if (logged == null) {
                PushButton(style = PushButtonStyle.Accent, 
                    onClick = { app.startQrLogin() },
                    enabled = !app.loginBusy || app.qrImage != null
                ) { Text(if (app.qrImage == null) "获取二维码" else "换一张") }
                Spacer(Modifier.height(8.dp))
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.cancelQrLogin() }, enabled = app.loginBusy) { Text("取消等待") }
            } else {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    app.forgetLogin()
                    app.startQrLogin()
                }) { Text("换一个账号登录") }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                app.loginNote.ifBlank { "还没有发起登录。" },
                style = AppTheme.type.bodySmall,
                color = t.subtle
            )
            Spacer(Modifier.height(16.dp))
            Notice(
                "这个工具没有接收密码的入口：登录只有扫码或你自己粘 Cookie 两种，" +
                    "Agent 能做的只有「打开登录 / 查状态 / 提示重新登录」。",
                Tone.INFO
            )
        }
    }
}

/* ── 第 3 步：模型 API ── */

@Composable
private fun ApiStepContent(app: AppState, cfg: AppConfig) {
    val t = tokens()
    val profile = cfg.apiProfile()

    Text(
        "接上模型",
        style = AppTheme.type.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "OpenAI 兼容的 /chat/completions 就行：官方、DeepSeek、Moonshot、自建中转、本地 Ollama 都行。",
        style = AppTheme.type.bodyMedium,
        color = AppTheme.color.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))

    Section("接入点", "") {
        TextFieldRow(
            "Base URL", profile.baseUrl,
            { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { if (it.id == profile.id) it.copy(baseUrl = v) else it }) } },
            placeholder = "https://api.openai.com/v1"
        )
        SectionSpace(10)
        TextFieldRow(
            "模型名", profile.model,
            { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { if (it.id == profile.id) it.copy(model = v) else it }) } },
            help = "必须和服务端实际模型名一致",
            placeholder = "gpt-4o-mini / deepseek-chat / qwen-plus"
        )
    }

    Section("API Key", "只存在本机密文里，配置文件和日志中都不会出现明文。") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            TextFieldRow(
                "Key", app.apiKeyDraft, { app.apiKeyDraft = it },
                help = "填一次即可，界面只显示指纹",
                secret = true,
                modifier = Modifier.weight(1f)
            )
            Column {
                Spacer(Modifier.height(2.dp))
                PushButton(style = PushButtonStyle.Accent, onClick = { app.saveApiKey() }, enabled = app.apiKeyDraft.isNotBlank()) { Text("加密保存") }
            }
        }
        SectionSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(
                if (app.hasSavedKey()) "已保存 ${profile.keyFingerprint}" else "尚未设置 Key",
                if (app.hasSavedKey()) Tone.OK else Tone.WARN
            )
            PushButton(style = PushButtonStyle.Accent, 
                onClick = { app.runProbe() },
                enabled = !app.probeRunning && app.hasSavedKey()
            ) {
                if (app.probeRunning) Spinner(Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存并自检")
            }
        }
        app.keyNote?.let { SectionSpace(10); Notice(it, Tone.INFO) }
        SectionSpace(8)
        KvRow("保护级别", app.secrets.protectionDescription)
    }

    Section("自检结果", "不是简单 ping：会要求模型真的调用一个假工具，验证函数调用可用。") {
        when (val r = app.probeReport) {
            null -> Text(
                if (app.probeRunning) "自检中，可能要十几秒…" else "还没自检。",
                style = AppTheme.type.bodySmall,
                color = t.subtle
            )
            else -> if (r.failed) {
                Notice("失败：${r.error}\n建议：${r.hint.ifBlank { "检查 Base URL、模型名与 Key" }}", Tone.ERROR)
            } else {
                Notice(
                    "可达｜模型回显：${r.modelEcho.ifBlank { profile.model }}｜函数调用：${if (r.toolsOk) "可用" else "不可用"}",
                    if (r.toolsOk) Tone.OK else Tone.WARN
                )
            }
        }
    }
}

/* ── 第 4 步：完成 ── */

@Composable
private fun DoneStepContent(app: AppState) {
    val t = tokens()
    Text(
        "都准备好了",
        style = AppTheme.type.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "接下来在「运行」页填投稿链接跑第一轮。默认节奏很保守：两条回复之间 20–45 秒、每天上限 20 条，" +
            "重复评论不会再回第二遍。",
        style = AppTheme.type.bodyMedium,
        color = AppTheme.color.onSurfaceVariant
    )
    Spacer(Modifier.height(22.dp))

    val envOk = app.installStage == EnvInstallStage.DONE
    SummaryRow(
        envOk,
        if (envOk) "Linux 环境：${app.installDistro.ifBlank { "已就绪" }}" else "Linux 环境：未启用（用本机或完全不开都行）",
        t
    )
    val loginOk = app.loginStatus is LoginStatus.LoggedIn
    SummaryRow(
        loginOk,
        if (loginOk) "登录：${(app.loginStatus as LoginStatus.LoggedIn).name}" else "登录：还没登录，随时可以在「登录」页补",
        t
    )
    SummaryRow(app.hasSavedKey(), if (app.hasSavedKey()) "模型 API：Key 已加密保存" else "模型 API：还没填 Key", t)

    Spacer(Modifier.height(26.dp))
    Notice(
        "防提示词注入的规则是硬编码的，界面上关不掉；发送前会再查一次台账和黑名单。" +
            "第一轮建议挑自己的投稿试，看清楚再说。",
        Tone.INFO
    )
    Spacer(Modifier.height(20.dp))
    PushButton(style = PushButtonStyle.Accent, onClick = { app.finishOnboarding() }) { Text("进入主界面") }
}

@Composable
private fun SummaryRow(ok: Boolean, text: String, t: AppTokens) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(
                if (ok) t.ok.copy(alpha = 0.18f) else t.divider
            ),
            contentAlignment = Alignment.Center
        ) {
            if (ok) Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = t.ok)
            else Box(Modifier.size(6.dp).clip(CircleShape).background(t.subtle))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = AppTheme.type.bodyMedium)
    }
}
