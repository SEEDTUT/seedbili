package com.gaibang.replyagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ForwardToInbox
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.domain.model.LoginStatus

const val WINDOW_TITLE = "seedbili"

enum class Page(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Rounded.Dashboard),
    RUN("运行", Icons.Rounded.SmartToy),
    INBOX("收件箱", Icons.Rounded.ForwardToInbox),
    LOGIN("登录", Icons.Rounded.QrCode2),
    API("模型 API", Icons.Rounded.Cloud),
    PROMPT("提示词", Icons.Rounded.Terminal),
    STRATEGY("策略配置", Icons.Rounded.Tune),
    LEDGER("台账", Icons.Rounded.Assignment),
    SANDBOX("Linux 环境", Icons.Rounded.DeveloperBoard),
    LOGS("日志", Icons.Rounded.Article),
    SETTINGS("设置", Icons.Rounded.Settings),
    ABOUT("关于", Icons.Rounded.Info)
}

@Composable
fun AppRoot(app: AppState, startPage: Page? = null) {
    val cfg by app.config.state.collectAsState()
    var systemDark by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { systemDark = Win11.systemUsesDarkTitleBars() }
    app.systemDark = systemDark
    val dark = app.isDark

    LaunchedEffect(dark) { Win11.applyTitleBarDarkWithRetry(WINDOW_TITLE, dark) }

    ReplyAgentTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                !app.booted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("正在启动…", style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
                    }
                }
                startPage == null && !cfg.ui.onboarded -> {
                    if (!app.introDone) FirstRunIntro { app.introDone = true }
                    else OnboardingWizard(app, cfg)
                }
                else -> Shell(app, cfg, startPage)
            }
        }
    }
}

@Composable
private fun Shell(
    app: AppState,
    cfg: com.gaibang.replyagent.domain.model.AppConfig,
    startPage: Page?
) {
    var page by remember { mutableStateOf(startPage ?: Page.OVERVIEW) }
    val progress by app.engine.progress.collectAsState()
    val inbox by app.engine.inbox.collectAsState()
    val ledgerEntries by app.ledger.entries.collectAsState()
    val approval by app.engine.pendingApproval.collectAsState()

    ApprovalDialog(app, approval)

    val pendingInbox = remember(inbox, ledgerEntries) {
        app.engine.pendingManual().size
    }

    Row(Modifier.fillMaxSize()) {
        NavPane(app, cfg, page, pendingInbox) { page = it }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 28 else -it / 28 }
                    val exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { if (forward) -it / 40 else it / 40 }
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "page"
            ) { target ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 30.dp, end = 30.dp, top = 20.dp, bottom = 18.dp)
                ) {
                    when (target) {
                        Page.OVERVIEW -> OverviewPage(app, cfg, progress) { page = it }
                        Page.RUN -> RunPage(app, cfg, progress, pendingInbox) { page = it }
                        Page.INBOX -> InboxPage(app) { page = it }
                        Page.LOGIN -> LoginPage(app)
                        Page.API -> ApiPage(app, cfg)
                        Page.PROMPT -> PromptPage(app, cfg)
                        Page.STRATEGY -> StrategyPage(app, cfg)
                        Page.LEDGER -> LedgerPage(app)
                        Page.SANDBOX -> SandboxPage(app, cfg)
                        Page.LOGS -> LogsPage(app)
                        Page.SETTINGS -> SettingsPage(app, cfg)
                        Page.ABOUT -> AboutPage()
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPane(
    app: AppState,
    cfg: com.gaibang.replyagent.domain.model.AppConfig,
    page: Page,
    pendingInbox: Int,
    onPick: (Page) -> Unit
) {
    Column(
        Modifier
            .width(248.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon.BrandMark(38)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "seedbili",
                    style = AppTheme.type.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.color.onSurface,
                    maxLines = 1
                )
                Text(
                    "SEEDTUT LAB",
                    style = AppTheme.type.labelSmall,
                    color = AppTheme.color.onSurfaceVariant
                )
            }
        }

        // 中段可滚动：窗口矮的时候底部卡片也不会把「关于」挤出去
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            NavGroup("工作台") {
                NavItem(Page.OVERVIEW, page, pendingInbox, onPick)
                NavItem(Page.RUN, page, pendingInbox, onPick)
                NavItem(Page.INBOX, page, pendingInbox, onPick)
            }
            NavGroup("配置") {
                NavItem(Page.LOGIN, page, pendingInbox, onPick)
                NavItem(Page.API, page, pendingInbox, onPick)
                NavItem(Page.PROMPT, page, pendingInbox, onPick)
                NavItem(Page.STRATEGY, page, pendingInbox, onPick)
            }
            NavGroup("环境与记录") {
                NavItem(Page.SANDBOX, page, pendingInbox, onPick)
                NavItem(Page.LEDGER, page, pendingInbox, onPick)
                NavItem(Page.LOGS, page, pendingInbox, onPick)
            }

            Spacer(Modifier.height(8.dp))

            NavItem(Page.SETTINGS, page, pendingInbox, onPick)
            NavItem(Page.ABOUT, page, pendingInbox, onPick)
        }

        Spacer(Modifier.height(10.dp))
        LoginStateCard(app, cfg) { onPick(Page.LOGIN) }
        Spacer(Modifier.height(10.dp))
        ThemeSwitch(cfg.ui.theme) { app.applyTheme(it) }
    }
}

@Composable
private fun NavGroup(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = AppTheme.type.labelSmall,
        color = AppTheme.color.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
    )
    content()
}

@Composable
private fun NavItem(page: Page, current: Page, pendingInbox: Int, onPick: (Page) -> Unit) {
    NavigationDrawerItem(
        label = { Text(page.label, style = AppTheme.type.bodyMedium) },
        icon = { Icon(page.icon, null, Modifier.size(19.dp)) },
        selected = current == page,
        onClick = { onPick(page) },
        shape = AppShape.medium,
        badge = if (page == Page.INBOX && pendingInbox > 0) {
            { Badge { Text("$pendingInbox") } }
        } else null,
        modifier = Modifier.height(40.dp)
    )
}

@Composable
private fun LoginStateCard(
    app: AppState,
    cfg: com.gaibang.replyagent.domain.model.AppConfig,
    onClick: () -> Unit
) {
    val s = app.loginStatus
    val logged = s is LoginStatus.LoggedIn
    val avatarUrl = (s as? LoginStatus.LoggedIn)?.avatar.orEmpty()
    val avatar by produceState<ImageBitmap?>(null, avatarUrl) {
        value = if (avatarUrl.isBlank()) null else AvatarStore.load(avatarUrl)
    }
    val state = when (s) {
        is LoginStatus.LoggedIn -> "已登录 · ${s.name}"
        is LoginStatus.Expired -> "登录已失效，点此重登"
        LoginStatus.NotLoggedIn -> "未登录"
        else -> "检查中…"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppShape.large,
        colors = SurfaceColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        border = null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (logged) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bmp = avatar
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "账号头像",
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                    )
                } else {
                    Icon(
                        if (logged) Icons.Rounded.CheckCircle else Icons.Rounded.QrCode2,
                        null,
                        Modifier.size(17.dp),
                        tint = if (logged) MaterialTheme.colorScheme.primary else AppTheme.color.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.adapter()?.displayName ?: cfg.account.platformId,
                    style = AppTheme.type.titleSmall,
                    maxLines = 1
                )
                Text(
                    state,
                    style = AppTheme.type.labelSmall,
                    color = if (logged) MaterialTheme.colorScheme.primary else AppTheme.color.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                null,
                Modifier.size(17.dp),
                AppTheme.color.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeSwitch(current: String, onPick: (String) -> Unit) {
    val keys = listOf("light", "system", "dark")
    val labels = listOf("浅色", "跟随", "深色")
    Column {
        Text(
            "外观",
            style = AppTheme.type.labelSmall,
            color = AppTheme.color.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 7.dp)
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            keys.forEachIndexed { index, key ->
                SegmentedButton(
                    selected = key == current,
                    onClick = { onPick(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = keys.size),
                    label = { Text(labels[index], style = AppTheme.type.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun ApprovalDialog(app: AppState, command: String?) {
    if (command == null) return
    val t = tokens()
    AlertDialog(
        onDismissRequest = { app.engine.resolveApproval(false) },
        shape = AppShape.extraLarge,
        title = {
            Text(
                "Agent 想执行一条命令",
                style = AppTheme.type.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "命令不会静默执行。拒绝不影响其它功能，提示词与本地过滤照常工作。",
                    style = AppTheme.type.bodySmall,
                    color = AppTheme.color.onSurfaceVariant
                )
                Surface(
                    shape = AppShape.small,
                    colors = SurfaceColors(t.warn.copy(alpha = 0.08f), AppTheme.color.onSurface),
                    border = null
                ) {
                    Text(
                        command,
                        style = AppTheme.type.bodySmall,
                        color = AppTheme.color.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            PushButton(
                text = "只允许这一次",
                onClick = { app.engine.resolveApproval(true, remember = false) }
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PushButton(
                    text = "本轮都允许",
                    onClick = { app.engine.resolveApproval(true, remember = true) },
                    style = PushButtonStyle.Secondary
                )
                TextButton(onClick = { app.engine.resolveApproval(false) }) {
                    Text("拒绝", style = AppTheme.type.labelMedium)
                }
            }
        }
    )
}
