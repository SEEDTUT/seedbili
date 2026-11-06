package com.gaibang.replyagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
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
import com.gaibang.replyagent.app.sandbox.CommandOutcome
import com.gaibang.replyagent.app.sandbox.SandboxProbe
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.LedgerEntry
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.domain.model.SandboxBackend
import com.gaibang.replyagent.domain.model.SandboxSettings
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.logging.Level
import com.gaibang.replyagent.logging.LogLine
import com.gaibang.replyagent.util.Paths2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* ══════════════ 台账 ══════════════ */

@Composable
fun LedgerPage(app: AppState) {
    val entries by app.ledger.entries.collectAsState()
    val t = tokens()
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<Set<LedgerStatus>>(emptySet()) }
    var note by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "台账",
            subtitle = "每条评论怎么处理的都在这里。判断重不重复、事后复盘都靠它。",
            actions = {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val dest = Paths2.exports.resolve("ledger-${java.time.LocalDate.now()}.csv")
                    runCatching { app.ledger.exportCsv(dest) }
                        .onSuccess { note = "已导出：$dest" }
                        .onFailure { note = "导出失败：${it.message}" }
                }) { Text("导出 CSV") }
            }
        )

        Section("筛选", "") {
            TextFieldRow("搜索（评论、回复、理由）", query, { query = it })
            SectionSpace(8)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LedgerStatus.entries.forEach { st ->
                    val on = st in statusFilter
                    if (on) PushButton(style = PushButtonStyle.Accent, onClick = { statusFilter = statusFilter - st }) { Text(st.cn()) }
                    else PushButton(style = PushButtonStyle.Secondary, onClick = { statusFilter = statusFilter + st }) { Text(st.cn()) }
                }
            }
            SectionSpace(6)
            Text(
                "命中 ${filtered(entries, query, statusFilter).size} / 共 ${entries.size} 条",
                style = AppTheme.type.bodySmall, color = t.subtle
            )
            if (note.isNotBlank()) { SectionSpace(6); Notice(note, Tone.INFO) }
        }

        Section("记录", "") {
            val rows = filtered(entries, query, statusFilter).take(300)
            if (rows.isEmpty()) {
                EmptyHint("没有匹配的记录。跑一轮之后这里会有内容。")
            } else {
                Column {
                    rows.forEach { e -> LedgerRow(e) }
                }
            }
        }

        Section("维护", "清理按当前策略的保留天数执行；压缩只是重写文件，不删数据。") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val n = app.ledger.prune(app.config.current.strategy().ledger.retentionDays)
                    note = "清理了 $n 条过期记录"
                }) { Text("按保留期清理") }
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.ledger.compact(); note = "已压缩" }) { Text("压缩") }
                PushButton(style = PushButtonStyle.Secondary, onClick = { openFolder(Paths2.exports) }) { Text("打开导出目录") }
            }
        }
    }
}

private fun filtered(entries: List<LedgerEntry>, query: String, statuses: Set<LedgerStatus>): List<LedgerEntry> =
    entries.filter { e ->
        (statuses.isEmpty() || e.status in statuses) &&
            (query.isBlank() || e.commentContent.contains(query, true) ||
                e.replyContent.contains(query, true) || e.reason.contains(query, true) ||
                e.commentId.contains(query))
    }

@Composable
private fun LedgerRow(e: LedgerEntry) {
    val t = tokens()
    val tone = when (e.status) {
        LedgerStatus.SENT -> Tone.OK
        LedgerStatus.FAILED -> Tone.ERROR
        LedgerStatus.ESCALATED -> Tone.WARN
        LedgerStatus.COOLDOWN -> Tone.WARN
        else -> Tone.INFO
    }
    val time = remember(e) {
        Instant.ofEpochMilli(e.atEpochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = AppTheme.type.labelSmall, color = AppTheme.color.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Pill(e.status.cn(), tone)
            Spacer(Modifier.width(8.dp))
            Text(e.action, style = AppTheme.type.labelSmall, color = t.subtle)
            if (e.platformCode != 0L) {
                Spacer(Modifier.width(8.dp))
                Text("code ${e.platformCode}", style = AppTheme.type.labelSmall, color = t.error)
            }
            Spacer(Modifier.weight(1f))
            if (e.model.isNotBlank()) Text(e.model, style = AppTheme.type.labelSmall, color = t.subtle)
        }
        SectionSpace(2)
        Text("评论：${e.commentContent.take(180)}", style = AppTheme.type.bodySmall, maxLines = 2)
        if (e.replyContent.isNotBlank()) {
            Text("回复：${e.replyContent.take(180)}", style = AppTheme.type.bodySmall,
                color = AppTheme.color.primary, maxLines = 2)
        }
        if (e.reason.isNotBlank() || e.error.isNotBlank()) {
            Text(
                listOf(e.reason, e.error).filter { it.isNotBlank() }.joinToString("｜"),
                style = AppTheme.type.bodySmall, color = t.subtle, maxLines = 2
            )
        }
    }
}

/* ══════════════ Linux 环境 ══════════════ */

@Composable
fun SandboxPage(app: AppState, cfg: AppConfig) {
    val sb = cfg.sandbox
    val probe = app.sandboxProbe
    var cmd by remember { mutableStateOf("uname -a && cat /etc/os-release | head -3") }
    var outcome by remember { mutableStateOf<CommandOutcome?>(null) }
    var runNote by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val t = tokens()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "Linux 环境",
            subtitle = "虚拟 Linux 环境让 Agent 能跑 shell，用来处理附件和脚本。不开它，主流程照样跑。",
            actions = {
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.reprobeSandbox() }, enabled = !app.sandboxProbing) {
                    if (app.sandboxProbing) Spinner(Modifier.size(15.dp))
                    Text("重新探测")
                }
            }
        )

        Section("后端", "选完记得点「重新探测」。默认不启用，装了 WSL 之后可以在这里切换。") {
            ChoiceRow(
                label = "执行宿主",
                options = SandboxBackend.entries.toList(),
                selected = sb.backend,
                onSelected = { b -> app.update { it.copy(sandbox = it.sandbox.copy(backend = b)) }; app.reprobeSandbox() },
                render = {
                    when (it) {
                        SandboxBackend.NONE -> "不启用（默认）"
                        SandboxBackend.WSL -> "WSL 发行版（真隔离）"
                        SandboxBackend.LOCAL -> "本机 cmd（无隔离）"
                    }
                }
            )
            SectionSpace(10)
            when (probe) {
                null -> Text("还没探测。", style = AppTheme.type.bodySmall, color = t.subtle)
                SandboxProbe.NotWindows -> Notice("这里不是 Windows，WSL 路线不适用。", Tone.INFO)
                is SandboxProbe.WslMissing -> Notice(
                    "没检测到 WSL。点下面的「一键自动安装」会自动装好（会弹一次 UAC，部分系统要重启）。\n当前 Agent 不会拿到 run_command 工具。",
                    Tone.WARN
                )
                is SandboxProbe.WslNoDistro -> Notice("WSL 已在，但还没有发行版：${probe.detail}", Tone.WARN)
                is SandboxProbe.Ready -> Notice(
                    "可用发行版：" + probe.distros.joinToString("、") { "${it.name} (WSL${it.version}${if (it.default) "，默认" else ""}·${it.state})" },
                    Tone.OK
                )
                is SandboxProbe.LocalOnly -> Notice("注意：${probe.warning}", Tone.ERROR)
            }
        }

        Section("给 Agent 开命令能力", "关掉后 run_command 直接从工具列表里消失，而不是出现再拒绝。") {
            SwitchRow(
                "允许 Agent 请求执行命令", sb.allowRunCommand,
                { v -> app.update { it.copy(sandbox = it.sandbox.copy(allowRunCommand = v)) } },
                enabled = sb.backend != SandboxBackend.NONE,
                help = if (sb.backend == SandboxBackend.NONE) "先选一个执行宿主才能开"
                else "开启后模型才能看到 run_command 这个工具"
            )
            SwitchRow(
                "每一条命令都要我确认", sb.confirmEachCommand,
                { v -> app.update { it.copy(sandbox = it.sandbox.copy(confirmEachCommand = v)) } },
                enabled = sb.allowRunCommand,
                help = "关掉后本轮 run 之内不再弹确认框，第一次仍会弹"
            )
            IntRow("命令超时", sb.commandTimeoutSeconds.toLong(),
                { v -> app.update { it.copy(sandbox = it.sandbox.copy(commandTimeoutSeconds = v.toInt())) } },
                unit = "秒", range = 5L..600L)
            SectionSpace(6)
            if (sb.backend == SandboxBackend.WSL) {
                TextFieldRow(
                    "发行版名", sb.distro,
                    { v -> app.update { it.copy(sandbox = it.sandbox.copy(distro = v)) } },
                    help = "留空用默认发行版；填名字可以固定，例如 Debian",
                    placeholder = "Debian"
                )
            }
            KvRow("工作目录", app.sandbox.workDirPath())
            KvRow("WSL 视角", if (sb.backend == SandboxBackend.WSL) app.sandbox.wslPathOfWorkDir() else "—")
        }

        Section("一键自动安装", "点一下按顺序执行：开 WSL → 装 Debian → 补工具链 → 验证。需要管理员的那步由你本人通过 UAC 确认。") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    app.installStage == com.gaibang.replyagent.app.sandbox.EnvInstallStage.RUNNING ->
                        PushButton(style = PushButtonStyle.Secondary, onClick = { app.cancelEnvInstall() }) { Text("取消安装") }
                    app.installStage == com.gaibang.replyagent.app.sandbox.EnvInstallStage.DONE ->
                        PushButton(style = PushButtonStyle.Secondary, onClick = { app.startEnvInstall() }) { Text("重新检测") }
                    else -> PushButton(style = PushButtonStyle.Accent, onClick = { app.startEnvInstall() }) { Text("开始自动安装") }
                }
                if (app.installStage == com.gaibang.replyagent.app.sandbox.EnvInstallStage.RUNNING) {
                    Spinner(Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("装完会自动切到 WSL 后端并打开命令能力", style = AppTheme.type.bodySmall, color = t.subtle)
            }
            SectionSpace(12)
            EnvInstallPanel(app)
        }

        Section("手动步骤（脚本跑不通时照这个来）", "都是普通命令，可以自己复制到管理员 PowerShell 里执行。") {
            app.sandbox.initGuide().forEach { (step, command) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(step, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurface)
                    if (command.isNotBlank()) {
                        SectionSpace(4)
                        MonoBlock(command, maxHeightDp = 80)
                    }
                }
            }
        }

        Section("自己试一条", "这条不走模型，是你手动执行的，用来验证环境是否真的能用。") {
            TextFieldRow("命令", cmd, { cmd = it }, singleLine = false, lines = 3)
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(style = PushButtonStyle.Accent, 
                    onClick = {
                        busy = true
                        outcome = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    when (sb.backend) {
                                        SandboxBackend.WSL -> app.sandbox.runCommand(cmd, sb.distro, sb.commandTimeoutSeconds)
                                        else -> app.sandbox.runLocal(cmd, sb.commandTimeoutSeconds)
                                    }
                                }
                            }
                            outcome = r.getOrNull()
                            runNote = r.exceptionOrNull()?.let { "执行异常：${it.message}" } ?: ""
                            busy = false
                        }
                    },
                    enabled = !busy && sb.backend != SandboxBackend.NONE
                ) { Text("执行") }
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val n = app.sandbox.cleanWorkDir()
                    outcome = CommandOutcome(0, "清理了 $n 个文件（只删本工具创建工作目录里的东西）", false, "本机")
                }) { Text("清空工作目录") }
                if (busy) Spinner(Modifier.size(16.dp))
            }
            outcome?.let {
                SectionSpace(10)
                KvRow("宿主", it.how)
                KvRow("退出码", "${it.exit}${if (it.timedOut) "（超时）" else ""}")
                MonoBlock(it.output, maxHeightDp = 260)
            }
            if (runNote.isNotBlank()) { SectionSpace(6); Notice(runNote, Tone.ERROR) }
        }
    }
}

/* ══════════════ 日志 ══════════════ */

@Composable
fun LogsPage(app: AppState) {
    val lines by AppLogger.flow.collectAsState()
    var filter by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(Level.DEBUG) }
    val scope = rememberCoroutineScope()
    val shown = lines.asReversed().filter {
        it.level.ordinal >= level.ordinal &&
            (filter.isBlank() || it.message.contains(filter, true) || it.tag.contains(filter, true))
    }

    Column {
        PageHeader(
            title = "日志",
            subtitle = "所有行落盘前都过一遍脱敏：Cookie、Key、Bearer 一律打码。",
            actions = {
                PushButton(style = PushButtonStyle.Secondary, onClick = { openFolder(Paths2.logs) }) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开日志目录")
                }
            }
        )
        Section("过滤", "") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                TextFieldRow("关键词", filter, { filter = it }, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Level.entries.forEach { lv ->
                        if (lv == level) PushButton(style = PushButtonStyle.Accent, onClick = { level = lv }) { Text(lv.name) }
                        else PushButton(style = PushButtonStyle.Secondary, onClick = { level = lv }) { Text(lv.name) }
                    }
                }
                PushButton(style = PushButtonStyle.Secondary, onClick = { AppLogger.clearMemory() }) { Text("清空视图") }
            }
            SectionSpace(6)
            SwitchRow(
                "详细日志（写入配置并影响落盘级别）",
                AppLogger.minLevel == Level.DEBUG,
                { v -> app.setVerboseLogging(v) },
                help = "关掉后只保留 INFO 及以上；排查问题时再打开"
            )
        }
        Section("最近 ${shown.size} 行", "") {
            if (shown.isEmpty()) EmptyHint("还没有日志。")
            LogList(shown)
        }
    }
}

/* ══════════════ 设置 ══════════════ */

@Composable
fun SettingsPage(app: AppState, cfg: AppConfig) {
    var importPath by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val t = tokens()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "设置",
            subtitle = "外观、数据位置、配置导入导出、插件目录。策略项都在「策略配置」页。"
        )

        Section("外观", "主题色由品牌橙派生，深浅色可跟随系统或手动指定。") {
            ChoiceRow(
                label = "主题",
                options = listOf("system", "light", "dark"),
                selected = cfg.ui.theme,
                onSelected = { app.applyTheme(it) },
                render = { when (it) { "system" -> "跟随系统"; "light" -> "浅色"; else -> "深色" } }
            )
        }

        Section("首次引导", "装环境、扫码、配 Key 几步走完。换了机器想再看一遍，从这里重放。") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.replayOnboarding() }) { Text("重新走一遍引导") }
                Text("会立刻回到片头动画，已保存的配置和登录态不受影响", style = AppTheme.type.bodySmall, color = t.subtle)
            }
        }

        Section("数据都在哪", "卸载或搬家只要删这一个目录。") {
            KvRow("根目录", Paths2.root.toString())
            KvRow("配置文件", app.config.path())
            KvRow("插件目录", Paths2.plugins.toString())
            KvRow("导出目录", Paths2.exports.toString())
            KvRow("密钥保护", app.secrets.protectionDescription)
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PushButton(style = PushButtonStyle.Secondary, onClick = { openFolder(Paths2.root) }) { Text("打开数据目录") }
                PushButton(style = PushButtonStyle.Secondary, onClick = { openFolder(Paths2.plugins) }) { Text("打开插件目录") }
            }
            SectionSpace(10)
            Text(
                "已发现平台：" + app.platforms().joinToString("、") { "${it.displayName}（${it.id}）" },
                style = AppTheme.type.bodySmall
            )
            Text(
                "把带 META-INF/services/com.gaibang.replyagent.domain.port.PlatformAdapter 的 jar 放进插件目录，重启后自动出现在这里。",
                style = AppTheme.type.bodySmall, color = t.subtle
            )
        }

        Section("配置导入导出", "导出的 JSON 不含 Key 与 Cookie，密钥只留在本机密文里。") {
            PushButton(style = PushButtonStyle.Secondary, onClick = {
                val dest = Paths2.exports.resolve("config-${java.time.LocalDate.now()}.json")
                runCatching { app.config.exportTo(dest) }
                    .onSuccess { note = "已导出：$dest" }
                    .onFailure { note = "导出失败：${it.message}" }
            }) { Text("导出当前配置") }
            SectionSpace(8)
            TextFieldRow(
                "从文件导入", importPath, { importPath = it },
                help = "填绝对路径。含明文 SESSDATA 或 sk- 开头的文件会被直接拒绝。",
                placeholder = "C:\\Users\\你\\Desktop\\config-2026-10-31.json"
            )
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PushButton(style = PushButtonStyle.Accent, 
                    onClick = {
                        runCatching { app.config.importFrom(java.nio.file.Paths.get(importPath.trim())) }
                            .onSuccess { note = "导入成功，已生效" }
                            .onFailure { note = "导入失败：${it.message}" }
                    },
                    enabled = importPath.isNotBlank()
                ) { Text("导入") }
                PushButton(style = PushButtonStyle.Secondary, onClick = { openFolder(Paths2.exports) }) { Text("去导出目录取文件") }
            }
            if (note.isNotBlank()) { SectionSpace(10); Notice(note, if (note.contains("失败")) Tone.ERROR else Tone.OK) }
        }

        Section("危险操作", "", tone = t.error) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    app.ledger.clearAll()
                    note = "台账已清空。下一轮开始，所有评论都会重新判断一次。"
                }) { Text("清空台账") }
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    app.update { com.gaibang.replyagent.domain.model.AppConfig() }
                    note = "配置已恢复出厂。API Key 与登录态没有被删（在密文目录里）。"
                }) { Text("恢复出厂配置") }
            }
        }

    }
}

@Composable
private fun LogList(list: List<LogLine>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(list.take(600)) { line ->
            val t = tokens()
            val color = when (line.level) {
                Level.DEBUG -> t.subtle
                Level.INFO -> AppTheme.color.onSurface
                Level.WARN -> t.warn
                Level.ERROR -> t.error
            }
            Text(
                line.render(),
                style = AppTheme.type.bodySmall,
                color = color,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            )
        }
    }
}

private fun openFolder(dir: java.nio.file.Path) {
    runCatching {
        java.nio.file.Files.createDirectories(dir)
        Desktop.getDesktop().open(dir.toFile())
    }.onFailure {
        AppLogger.w("ui", "打开目录失败 $dir：${it.message}")
    }
}

private fun LedgerStatus.cn(): String = when (this) {
    LedgerStatus.SENT -> "已发送"
    LedgerStatus.SKIPPED -> "跳过"
    LedgerStatus.ESCALATED -> "转人工"
    LedgerStatus.FAILED -> "失败"
    LedgerStatus.DUP_SKIPPED -> "重复跳过"
    LedgerStatus.FILTERED -> "已过滤"
    LedgerStatus.COOLDOWN -> "冷却中"
}

private fun SandboxSettings.describeForUi(): String =
    "${backend.name} / distro=${distro.ifBlank { "默认" }} / run_command=${if (allowRunCommand) "开" else "关"}"
