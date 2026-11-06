package com.gaibang.replyagent.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.domain.model.ApiProfile
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.model.PromptProfile
import com.gaibang.replyagent.domain.model.PromptExample
import com.gaibang.replyagent.domain.port.Capability

/* ══════════════ 登录 ══════════════ */

@Composable
fun LoginPage(app: AppState) {
    val t = tokens()
    val adapter = app.adapter()
    val loggedInNow = app.loginStatus as? LoginStatus.LoggedIn
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "登录",
            subtitle = "工具没有任何接收密码的入口：要么你自己扫码，要么自己粘 Cookie。",
            actions = {
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.refreshLogin() }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新检查")
                }
            }
        )

        Section("当前状态", "") {
            when (val s = app.loginStatus) {
                is LoginStatus.LoggedIn -> Notice("已登录：${s.name}（UID ${s.uid}）。Cookie 存在本机密文里，保护级别：${app.secrets.protectionDescription}", Tone.OK)
                is LoginStatus.Expired -> Notice("本机存着登录态，但平台说已经失效。重新扫码即可，旧 Cookie 会被覆盖。", Tone.ERROR)
                LoginStatus.NotLoggedIn -> Notice("未登录。跑一轮之前必须先登录。", Tone.WARN)
                else -> Text("正在检查…", style = AppTheme.type.bodyMedium)
            }
            SectionSpace(8)
            Label("平台", "演示平台用假数据，不需要账号，可以把整条链路跑通看看效果。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.platforms().forEach { p ->
                    if (p.id == app.platformId) PushButton(style = PushButtonStyle.Accent, onClick = {}) { Text(p.displayName) }
                    else PushButton(style = PushButtonStyle.Secondary, onClick = { app.selectPlatform(p.id) }) { Text(p.displayName) }
                }
            }
            if (adapter != null) {
                SectionSpace(6)
                KvRow("能力", adapter.capabilities.joinToString("、") { it.cn() }.ifBlank { "（无）" })
            }
        }

        Section("扫码登录", "手机 B 站 App → 我的 → 右上角扫码。二维码由本机 ZXing 绘制，不经过任何第三方服务。") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(
                    Modifier.size(268.dp).align(Alignment.CenterVertically),
                    contentAlignment = Alignment.Center
                ) {
                    val qr = app.qrImage
                    val loggedIn = loggedInNow
                    if (qr != null) {
                        Image(bitmap = qr, contentDescription = "登录二维码", modifier = Modifier.size(260.dp))
                    } else if (!app.loginBusy && loggedIn != null) {
                        SurfaceForCard {
                            Column(
                                Modifier.fillMaxWidth().height(260.dp).padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    Modifier.size(56.dp).background(t.accentSoft, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Check, null, Modifier.size(28.dp), tint = AppTheme.color.primary)
                                }
                                SectionSpace(14)
                                Text(loggedIn.name, style = AppTheme.type.titleSmall)
                                SectionSpace(4)
                                Text("UID ${loggedIn.uid}", style = AppTheme.type.bodySmall, color = t.subtle)
                                SectionSpace(12)
                                Text(
                                    "要换账号就点右边「重新获取」，现用登录态不受影响。",
                                    style = AppTheme.type.labelSmall,
                                    color = t.subtle,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        SurfaceForCard {
                            Column(
                                Modifier.fillMaxWidth().height(260.dp).padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (app.loginBusy) Spinner(Modifier.size(22.dp))
                                Text(
                                    if (app.loginNote.isNotBlank()) app.loginNote else "点右边「获取二维码」",
                                    style = AppTheme.type.bodySmall,
                                    color = AppTheme.color.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    PushButton(style = PushButtonStyle.Accent, 
                        onClick = { app.startQrLogin() },
                        enabled = adapter?.capabilities?.contains(Capability.LOGIN_QR) == true &&
                            (!app.loginBusy || app.qrImage != null)
                    ) { Text(if (app.qrImage == null) "获取二维码" else "重新获取") }
                    SectionSpace(8)
                    PushButton(style = PushButtonStyle.Secondary, onClick = { app.cancelQrLogin() }, enabled = app.loginBusy) { Text("取消等待") }
                    SectionSpace(10)
                    Text(
                        when {
                            app.loginBusy -> app.loginNote.ifBlank { "正在获取二维码…" }
                            loggedInNow != null -> "登录态可用，不需要再扫一次。"
                            else -> app.loginNote.ifBlank { "还没有发起登录。" }
                        },
                        style = AppTheme.type.bodySmall,
                        color = t.subtle
                    )
                    SectionSpace(10)
                    Notice(
                        "Agent 能看到的登录相关能力只有三个：打开登录、查状态、提示重新登录。" +
                            "代填密码、代收验证码这类能力从接口上就不存在，不是「默认关掉」。",
                        Tone.INFO
                    )
                }
            }
        }

        Section("兜底：手动粘贴 Cookie", "扫码不方便时（比如手机不在身边），从浏览器开发者工具里复制 Cookie 串粘进来。") {
            TextFieldRow(
                "Cookie 字符串",
                app.cookieDraft,
                { app.cookieDraft = it },
                help = "至少要有 SESSDATA、bili_jct、DedeUserID。粘贴的内容只本机加密保存，日志里一律打码。",
                singleLine = false,
                lines = 4,
                placeholder = "SESSDATA=…; bili_jct=…; DedeUserID=…"
            )
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(style = PushButtonStyle.Accent, 
                    onClick = { app.submitCookieImport() },
                    enabled = app.cookieDraft.isNotBlank() && adapter?.capabilities?.contains(Capability.LOGIN_COOKIE_IMPORT) == true
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("校验并保存")
                }
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.forgetLogin() }, enabled = app.loginStatus is LoginStatus.LoggedIn) { Text("清除本机登录态") }
            }
            app.importNote?.let { SectionSpace(10); Notice(it, if (it.startsWith("导入成功")) Tone.OK else Tone.WARN) }
        }
    }
}

/* ══════════════ 模型 API ══════════════ */

@Composable
fun ApiPage(app: AppState, cfg: AppConfig) {
    val t = tokens()
    val profile = cfg.apiProfile()
    var nameDraft by remember(profile.id) { mutableStateOf(profile.name) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "模型 API",
            subtitle = "OpenAI 兼容的 chat/completions 就行，自建中转、DeepSeek、Moonshot、Ollama 均可。",
            actions = {
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.runProbe() }, enabled = !app.probeRunning && app.hasSavedKey()) {
                    if (app.probeRunning) Spinner(Modifier.size(15.dp))
                    Text("连通性与函数调用自检")
                }
            }
        )

        if (cfg.apiProfiles.size > 1) {
            Section("多套预设", "换线路不用重填 Key；跑之前在这里选一套。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cfg.apiProfiles.forEach { p ->
                        val active = p.id == cfg.activeApiProfileId
                        if (active) PushButton(style = PushButtonStyle.Accent, onClick = {}) { Text(p.name) }
                        else PushButton(style = PushButtonStyle.Secondary, onClick = { app.update { c -> c.copy(activeApiProfileId = p.id) } }) { Text(p.name) }
                    }
                    PushButton(style = PushButtonStyle.Secondary, onClick = {
                        app.update { c ->
                            val id = "p${c.apiProfiles.size + 1}-${System.currentTimeMillis() % 1000}"
                            c.copy(
                                apiProfiles = c.apiProfiles + ApiProfile(
                                    id = id, name = "新预设 ${c.apiProfiles.size + 1}",
                                    baseUrl = c.apiProfile().baseUrl, model = c.apiProfile().model,
                                    keyRef = "apikey.$id"
                                ),
                                activeApiProfileId = id
                            )
                        }
                    }) { Text("＋ 新建一套") }
                }
            }
        }

        Section("接入点", "Base URL 填到 /v1 这一层；工具会自己接 /chat/completions。") {
            TextFieldRow(
                "名称", nameDraft,
                { nameDraft = it; app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(name = it) else p }) } },
                singleLine = true
            )
            SectionSpace(10)
            TextFieldRow(
                "Base URL", profile.baseUrl,
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(baseUrl = v) else p }) } },
                placeholder = "https://api.openai.com/v1"
            )
            SectionSpace(4)
            KvRow("实际请求地址", profile.chatUrl())
            SectionSpace(10)
            TextFieldRow(
                "模型名", profile.model,
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(model = v) else p }) } },
                help = "必须和服务端 /v1/models 里的名字一模一样",
                placeholder = "gpt-4o-mini / deepseek-chat / qwen-plus …"
            )
        }

        Section("API Key", "只以密文存在本机，配置文件和日志里都不会出现明文。") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                TextFieldRow(
                    "新的 Key", app.apiKeyDraft, { app.apiKeyDraft = it },
                    help = "填一次就够了；界面不回显已保存的 Key，只显示指纹。",
                    secret = true,
                    modifier = Modifier.weight(1f)
                )
                Column {
                    Spacer(Modifier.height(2.dp))
                    PushButton(style = PushButtonStyle.Accent, onClick = { app.saveApiKey() }, enabled = app.apiKeyDraft.isNotBlank()) { Text("加密保存") }
                }
            }
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (app.hasSavedKey()) "已保存 ${profile.keyFingerprint}" else "尚未设置 Key",
                    if (app.hasSavedKey()) Tone.OK else Tone.WARN)
                if (app.hasSavedKey()) {
                    GhostButton("删除本机 Key", onClick = { app.deleteApiKey() })
                }
            }
            app.keyNote?.let { SectionSpace(8); Notice(it, Tone.INFO) }
            SectionSpace(8)
            KvRow("保护级别", app.secrets.protectionDescription)
            if (!app.secrets.strongProtection) {
                SectionSpace(6)
                Notice("DPAPI 探测没通过，当前用的是机器绑定 AES-GCM。能用，但比 DPAPI 弱一档，日志与界面上都会写明。", Tone.WARN)
            }
        }

        Section("生成参数", "评论回复不需要太发散，温度建议 0.5~0.9。") {
            SliderRow(
                "temperature", profile.temperature,
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(temperature = v) else p }) } },
                range = 0.0..2.0, steps = 39, help = "越高越会发挥，也越容易跑偏"
            )
            SliderRow(
                "top_p", profile.topP ?: 1.0,
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(topP = v) else p }) } },
                range = 0.0..1.0, steps = 19, fmt = { if (profile.topP == null && it == 1.0) "不传" else String.format("%.2f", it) }
            )
            IntRow(
                "max_tokens", profile.maxTokens.toLong(),
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(maxTokens = v.toInt()) else p }) } },
                help = "一条回复的上限，别太大，省钱", unit = "token", range = 64L..8192L
            )
            IntRow(
                "超时", profile.timeoutSeconds.toLong(),
                { v -> app.update { c -> c.copy(apiProfiles = c.apiProfiles.map { p -> if (p.id == profile.id) p.copy(timeoutSeconds = v.toInt()) else p }) } },
                help = "模型慢就调大；限速比它更重要", unit = "秒", range = 10L..600L
            )
        }

        Section("自检", "会真的让模型调用一个假工具，确认函数调用可用。整条链路都靠这一步。") {
            app.probeReport?.let { r ->
                if (r.failed) {
                    Notice("失败：${r.error}\n建议：${r.hint}", Tone.ERROR)
                } else {
                    Notice(
                        "可达：是｜模型回显：${r.modelEcho.ifBlank { profile.model }}｜函数调用：${if (r.toolsOk) "可用" else "不可用"}\n${r.toolNote}",
                        if (r.toolsOk) Tone.OK else Tone.WARN
                    )
                    if (!r.toolsOk) {
                        SectionSpace(6)
                        Notice(
                            "函数调用不可用时，Agent 会退化成「读模型输出的 JSON 文本」。能用，但稳定性差一截，" +
                                "建议换一条支持 tools 的线路。",
                            Tone.WARN
                        )
                    }
                }
            } ?: Text(
                app.probeRunning.let { if (it) "自检中，可能要等十几秒…" else "还没自检过。填好 Key 后点右上角。" },
                style = AppTheme.type.bodySmall,
                color = t.subtle
            )
        }

        if (cfg.apiProfiles.size > 1) {
            Section("删除预设", "") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cfg.apiProfiles.filter { it.id != cfg.activeApiProfileId }.forEach { p ->
                        PushButton(style = PushButtonStyle.Secondary, onClick = {
                            app.update { c -> c.copy(apiProfiles = c.apiProfiles.filter { it.id != p.id }) }
                        }) { Text("删除「${p.name}」") }
                    }
                }
            }
        }
    }
}

/* ══════════════ 提示词 ══════════════ */

@Composable
fun PromptPage(app: AppState, cfg: AppConfig) {
    val prompt = cfg.prompt()
    val strategy = cfg.strategy()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "提示词",
            subtitle = "人设、边界、说话风格、少样本示例。规则块在「策略配置」页，改完这里会实时反映。"
        )

        Section("预设", "多套人设可以并存，策略里指定用哪一套。") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cfg.prompts.forEach { p ->
                    if (p.id == cfg.activePromptId) PushButton(style = PushButtonStyle.Accent, onClick = {}) { Text(p.name) }
                    else PushButton(style = PushButtonStyle.Secondary, onClick = { app.update { c -> c.copy(activePromptId = p.id) } }) { Text(p.name) }
                }
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val id = "prompt-${System.currentTimeMillis() % 100000}"
                    app.update { c ->
                        c.copy(
                            prompts = c.prompts + PromptProfile(
                                id = id, name = "新人设 ${c.prompts.size + 1}",
                                persona = c.prompt().persona, boundaries = c.prompt().boundaries, style = c.prompt().style
                            ),
                            activePromptId = id
                        )
                    }
                }) { Text("＋ 复制一套") }
            }
        }

        Section("这套人设叫什么", "") {
            TextFieldRow(
                "名称", prompt.name,
                { v -> editPrompt(app, prompt.id) { it.copy(name = v) } }
            )
        }

        Section("人设", "你是谁、替谁说话。别写太长，规则交给规则块。") {
            TextFieldRow("persona", prompt.persona, { v -> editPrompt(app, prompt.id) { it.copy(persona = v) } }, singleLine = false, lines = 3)
        }

        Section("边界", "明确说什么不行。这一段是防止越界承诺的主要手段。") {
            TextFieldRow("boundaries", prompt.boundaries, { v -> editPrompt(app, prompt.id) { it.copy(boundaries = v) } }, singleLine = false, lines = 4)
        }

        Section("风格", "语气、长度、口头禅。") {
            TextFieldRow("style", prompt.style, { v -> editPrompt(app, prompt.id) { it.copy(style = v) } }, singleLine = false, lines = 3)
        }

        Section("少样本示例", "一到三组「观众问 → 你怎么答」比写十句要求管用。") {
            prompt.examples.forEachIndexed { i, ex ->
                ExampleRow(app, prompt, i, ex)
            }
            SectionSpace(6)
            PushButton(style = PushButtonStyle.Secondary, onClick = {
                editPrompt(app, prompt.id) { it.copy(examples = it.examples + PromptExample("", "")) }
            }) { Text("＋ 加一组") }
        }

        Section("预览拼出来的样子", "这就是发给模型的三段内容（第三段每条不同）。") {
            val personaBlock = app.renderer.personaBlock(prompt)
            val rulesBlock = app.renderer.rulesBlock(strategy, null, "")
            MonoBlock(personaBlock, maxHeightDp = 320)
            SectionSpace(8)
            MonoBlock(rulesBlock, maxHeightDp = 320)
        }
    }
}

@Composable
private fun ExampleRow(app: AppState, prompt: PromptProfile, index: Int, ex: PromptExample) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        TextFieldRow(
            "观众", ex.input,
            { v -> editPrompt(app, prompt.id) { p -> p.copy(examples = p.examples.mapIndexed { i, e -> if (i == index) e.copy(input = v) else e }) } },
            modifier = Modifier.weight(1f), singleLine = true
        )
        TextFieldRow(
            "你", ex.output,
            { v -> editPrompt(app, prompt.id) { p -> p.copy(examples = p.examples.mapIndexed { i, e -> if (i == index) e.copy(output = v) else e }) } },
            modifier = Modifier.weight(1.6f), singleLine = true
        )
        GhostButton("删", onClick = { editPrompt(app, prompt.id) { p -> p.copy(examples = p.examples.filterIndexed { i, _ -> i != index }) } })
    }
}

private fun editPrompt(app: AppState, id: String, transform: (PromptProfile) -> PromptProfile) {
    val stamp = java.time.LocalDateTime.now().toString()
    app.update { c ->
        c.copy(prompts = c.prompts.map { if (it.id == id) transform(it).copy(updatedAt = stamp, version = it.version + 1) else it })
    }
}

private fun Capability.cn(): String = when (this) {
    Capability.LOGIN_QR -> "扫码登录"
    Capability.LOGIN_COOKIE_IMPORT -> "手动 Cookie"
    Capability.FETCH_COMMENTS -> "抓取评论"
    Capability.POST_REPLY -> "发送回复"
    Capability.RESOLVE_TARGET -> "解析投稿"
    Capability.UPLOAD_IMAGE -> "回复带图"
    Capability.LIST_REPLIES -> "读子回复"
}
