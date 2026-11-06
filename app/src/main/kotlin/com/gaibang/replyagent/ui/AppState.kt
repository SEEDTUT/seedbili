package com.gaibang.replyagent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.gaibang.replyagent.app.ledger.LedgerService
import com.gaibang.replyagent.app.policy.FilterEngine
import com.gaibang.replyagent.app.sandbox.SandboxProbe
import com.gaibang.replyagent.app.sandbox.SandboxService
import com.gaibang.replyagent.app.workflow.WorkflowEngine
import com.gaibang.replyagent.agent.PromptRenderer
import com.gaibang.replyagent.config.ConfigStore
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.port.LoginEvent
import com.gaibang.replyagent.domain.port.LoginSession
import com.gaibang.replyagent.domain.port.PlatformAdapter
import com.gaibang.replyagent.llm.OpenAiClient
import com.gaibang.replyagent.llm.ProbeReport
import com.gaibang.replyagent.llm.toDisplay
import com.gaibang.replyagent.llm.toHint
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.platform.AdapterRegistry
import com.gaibang.replyagent.secure.SecretStoreFactory
import com.gaibang.replyagent.util.Paths2
import com.gaibang.replyagent.util.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/**
 * 组合根：所有后端对象在这里装配一次，页面只认 AppState。
 * 这样 UI 层不碰 new，也保证限速器/台账只有一个实例。
 */
class AppState {
    val config = ConfigStore()
    val secrets = SecretStoreFactory.get()
    val registry = AdapterRegistry(secrets)
    val ledger = LedgerService()
    val filter = FilterEngine()
    val renderer = PromptRenderer()
    val sandbox = SandboxService()

    val engine: WorkflowEngine by lazy {
        WorkflowEngine(
            config = config,
            secrets = secrets,
            adapterProvider = { adapter() },
            filter = filter,
            renderer = renderer,
            sandbox = sandbox,
            ledger = ledger
        )
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /* ── 启动 ── */
    var booted by androidx.compose.runtime.mutableStateOf(false); private set
    var bootError by androidx.compose.runtime.mutableStateOf<String?>(null); private set

    fun boot() {
        if (booted) return
        runCatching {
            Paths2.ensureAll()
            config.load()
            AppLogger.minLevel = if (config.current.ui.verboseLogging)
                com.gaibang.replyagent.logging.Level.DEBUG else com.gaibang.replyagent.logging.Level.INFO
            AppLogger.pruneOldFiles(14)
            ledger.load()
            registry.discover()
            platformId = config.current.account.platformId
            if (adapter() == null) platformId = registry.primary()?.id ?: ""
        }.onFailure {
            bootError = it.toDisplay()
            AppLogger.e("boot", "启动失败", it)
        }
        booted = true
        refreshLogin()
    }

    fun shutdown() {
        runCatching { engine.close() }
        runCatching { registry.closeAll() }
        runCatching { scope.cancel() }
    }

    /* ── 平台 ── */

    var platformId by androidx.compose.runtime.mutableStateOf("bilibili")
        private set

    fun platforms(): List<PlatformAdapter> = registry.all()

    fun adapter(): PlatformAdapter? = registry.byId(platformId) ?: registry.primary()

    fun selectPlatform(id: String) {
        platformId = id
        config.update { it.copy(account = it.account.copy(platformId = id)) }
        refreshLogin()
    }

    /* ── 登录 ── */

    var loginStatus by androidx.compose.runtime.mutableStateOf<LoginStatus>(LoginStatus.Unknown); private set
    var loginBusy by androidx.compose.runtime.mutableStateOf(false); private set
    var loginNote by androidx.compose.runtime.mutableStateOf(""); private set
    var qrImage by androidx.compose.runtime.mutableStateOf<ImageBitmap?>(null); private set
    var qrUrl by androidx.compose.runtime.mutableStateOf(""); private set
    var cookieDraft by androidx.compose.runtime.mutableStateOf("")
    var importNote by androidx.compose.runtime.mutableStateOf<String?>(null); private set
    private var loginSession: LoginSession? = null
    private var loginJob: Job? = null

    fun refreshLogin() {
        val a = adapter() ?: return
        loginBusy = true
        scope.launch {
            val s = runCatching { a.loginStatus() }
                .getOrElse {
                    AppLogger.w("ui", "查询登录态失败：${it.message}")
                    LoginStatus.NotLoggedIn
                }
            loginStatus = s
            loginBusy = false
            if (s is LoginStatus.LoggedIn) {
                loginNote = "已登录：${s.name}"
                qrImage = null
                config.update { it.copy(account = it.account.copy(activeUid = s.uid)) }
            }
        }
    }

    /** 扫码登录：二维码在本地画，链接不外传任何第三方 */
    fun startQrLogin() {
        val a = adapter() ?: run { loginNote = "没有可用平台"; return }
        if (com.gaibang.replyagent.domain.port.Capability.LOGIN_QR !in a.capabilities) {
            loginNote = "${a.displayName} 没有实现扫码登录能力"
            return
        }
        cancelQrLogin()
        loginBusy = true
        loginNote = "正在获取二维码…"
        qrImage = null
        loginJob = scope.launch {
            val session = runCatching { a.startLogin() }.getOrElse {
                loginBusy = false
                loginNote = "获取二维码失败：${it.toDisplay()}"
                return@launch
            }
            loginSession = session
            for (ev in session.events) {
                when (ev) {
                    is LoginEvent.QrReady -> {
                        qrImage = ev.pngRgba.toBitmap(ev.size)
                        qrUrl = ev.url
                        loginNote = "用手机 B 站 App 扫下面的二维码，并在手机上点确认"
                    }
                    LoginEvent.Waiting -> loginNote = "等待扫码…"
                    LoginEvent.ScannedConfirming -> loginNote = "已扫码，请在手机上点确认登录"
                    is LoginEvent.Success -> {
                        loginNote = "登录成功：${ev.name}（${ev.uid}）"
                        loginStatus = LoginStatus.LoggedIn(ev.uid, ev.name, avatar = ev.avatar)
                        qrImage = null
                        loginBusy = false
                        AppLogger.i("ui", "登录成功 ${Redactor.fingerprint(ev.uid)}")
                    }
                    is LoginEvent.Expired -> {
                        loginNote = "二维码已过期，点「重新获取」再来一次"
                        qrImage = null
                        loginBusy = false
                    }
                    is LoginEvent.Failed -> {
                        loginNote = "登录失败：${ev.message}${if (ev.hint.isNotBlank()) "（提示：${ev.hint}）" else ""}"
                        qrImage = null
                        loginBusy = false
                    }
                }
            }
            loginBusy = false
        }
    }

    fun cancelQrLogin() {
        loginSession?.cancel()
        loginSession = null
        loginJob?.cancel()
        loginJob = null
        loginBusy = false
    }

    fun submitCookieImport(onDone: (Boolean) -> Unit = {}) {
        val a = adapter() ?: return
        val text = cookieDraft
        if (text.isBlank()) {
            importNote = "先粘贴 Cookie 字符串"
            return
        }
        importNote = "校验中…"
        scope.launch {
            val r = runCatching { a.importCookie(text) }
            importNote = r.getOrNull()?.let {
                if (it.ok) "导入成功：${it.message}（UID ${it.replyId}）" else "导入失败：${it.message}"
            } ?: "导入异常：${r.exceptionOrNull()?.toDisplay()}"
            if (r.getOrNull()?.ok == true) {
                cookieDraft = ""
                refreshLogin()
            }
            onDone(r.getOrNull()?.ok == true)
        }
    }

    fun forgetLogin() {
        val a = adapter() ?: return
        scope.launch {
            runCatching { a.logout() }
            loginStatus = LoginStatus.NotLoggedIn
            loginNote = "已清除本地登录态"
            qrImage = null
            AppLogger.i("ui", "已清除本地登录态")
        }
    }

    /* ── 模型 API ── */

    var apiKeyDraft by androidx.compose.runtime.mutableStateOf("")
    var probeRunning by androidx.compose.runtime.mutableStateOf(false); private set
    var probeReport by androidx.compose.runtime.mutableStateOf<ProbeReport?>(null); private set
    var keyNote by androidx.compose.runtime.mutableStateOf<String?>(null); private set

    fun hasSavedKey(): Boolean {
        val ref = config.current.apiProfile().keyRef
        return ref.isNotBlank() && secrets.exists(ref)
    }

    fun saveApiKey() {
        val profile = config.current.apiProfile()
        val value = apiKeyDraft.trim()
        if (value.isBlank()) {
            keyNote = "Key 是空的，没保存"
            return
        }
        runCatching {
            secrets.put(profile.keyRef, value.toByteArray())
            config.update { cfg ->
                val p = cfg.apiProfile()
                cfg.copy(apiProfiles = cfg.apiProfiles.map {
                    if (it.id == p.id) it.copy(keyFingerprint = Redactor.fingerprint(value)) else it
                })
            }
            keyNote = "已加密保存（${secrets.protectionDescription}）"
            apiKeyDraft = ""
            AppLogger.i("ui", "API Key 已写入密文存储：${Redactor.fingerprint(value)}")
        }.onFailure {
            keyNote = "保存失败：${it.toDisplay()}"
            AppLogger.e("ui", "API Key 保存失败", it)
        }
    }

    fun deleteApiKey() {
        val profile = config.current.apiProfile()
        secrets.delete(profile.keyRef)
        config.update { cfg ->
            cfg.copy(apiProfiles = cfg.apiProfiles.map {
                if (it.id == cfg.activeApiProfileId) it.copy(keyFingerprint = "(未设置)") else it
            })
        }
        keyNote = "已删除本机保存的 Key"
        AppLogger.i("ui", "API Key 已从密文存储删除")
    }

    fun runProbe() {
        val profile = config.current.apiProfile()
        val key = runCatching { secrets.get(profile.keyRef)?.let { String(it) } }.getOrNull() ?: ""
        probeRunning = true
        probeReport = null
        scope.launch {
            probeReport = runCatching { OpenAiClient(profile, key).probe() }
                .getOrElse { ProbeReport(failed = true, error = it.toDisplay(), hint = it.toHint()) }
            probeRunning = false
        }
    }

    /* ── 沙箱探测 ── */

    var sandboxProbe by androidx.compose.runtime.mutableStateOf<SandboxProbe?>(null); private set
    var sandboxProbing by androidx.compose.runtime.mutableStateOf(false); private set

    fun reprobeSandbox() {
        sandboxProbing = true
        scope.launch {
            sandboxProbe = runCatching { sandbox.probe(config.current.sandbox) }.getOrNull()
            sandboxProbing = false
        }
    }

    /* ── 首次引导 ── */

    /** false = 还没看片头动画；看完进向导 */
    var introDone by androidx.compose.runtime.mutableStateOf(false)
    /** 0 环境 · 1 登录 · 2 模型 · 3 完成 */
    var onboardingStep by androidx.compose.runtime.mutableStateOf(0)
    var envSkipped by androidx.compose.runtime.mutableStateOf(false)
    /** null = 还没选；cmd = 本机执行；wsl = 走自动安装 */
    var envChoice by androidx.compose.runtime.mutableStateOf<String?>(null)

    val envInstaller by lazy { com.gaibang.replyagent.app.sandbox.EnvInstaller(sandbox) }
    var installStage by androidx.compose.runtime.mutableStateOf(com.gaibang.replyagent.app.sandbox.EnvInstallStage.IDLE)
        ; private set
    var installStatus by androidx.compose.runtime.mutableStateOf(
        com.gaibang.replyagent.app.sandbox.EnvStep.plan().map {
            com.gaibang.replyagent.app.sandbox.StepStatus.PENDING
        }
    ); private set
    var installNotes by androidx.compose.runtime.mutableStateOf(mapOf<Int, String>()); private set
    var installLog by androidx.compose.runtime.mutableStateOf(""); private set
    var installResult by androidx.compose.runtime.mutableStateOf<String?>(null); private set
    var installDistro by androidx.compose.runtime.mutableStateOf(""); private set
    private var installJob: Job? = null

    fun startEnvInstall() {
        if (installJob?.isActive == true) return
        envSkipped = false
        envChoice = "wsl"
        installStage = com.gaibang.replyagent.app.sandbox.EnvInstallStage.RUNNING
        installStatus = com.gaibang.replyagent.app.sandbox.EnvStep.plan().map {
            com.gaibang.replyagent.app.sandbox.StepStatus.PENDING
        }
        installNotes = emptyMap()
        installLog = ""
        installResult = null
        val buffer = StringBuilder()
        var lastPush = 0L
        installJob = scope.launch {
            val result = runCatching {
                envInstaller.install(
                    onStage = { installStage = it },
                    onStep = { index, status, note ->
                        installStatus = installStatus.toMutableList().also { it[index] = status }
                        if (note.isNotBlank()) installNotes = installNotes + (index to note)
                    },
                    onLine = { line ->
                        buffer.append(line).append('\n')
                        val now = System.currentTimeMillis()
                        if (now - lastPush > 120) {
                            lastPush = now
                            installLog = buffer.toString()
                        }
                    }
                )
            }.getOrElse {
                AppLogger.e("env", "环境安装异常", it)
                com.gaibang.replyagent.app.sandbox.EnvInstallResult(
                    com.gaibang.replyagent.app.sandbox.EnvInstallStage.FAILED, "", it.toDisplay()
                )
            }
            installLog = buffer.toString()
            installStage = result.stage
            installDistro = result.distro
            installResult = result.message
            if (result.stage == com.gaibang.replyagent.app.sandbox.EnvInstallStage.DONE) {
                config.update {
                    it.copy(
                        sandbox = it.sandbox.copy(
                            backend = com.gaibang.replyagent.domain.model.SandboxBackend.WSL,
                            distro = result.distro,
                            allowRunCommand = true
                        )
                    )
                }
                sandboxProbe = sandbox.probe(config.current.sandbox)
            }
        }
    }

    fun cancelEnvInstall() {
        installJob?.cancel()
        installJob = null
        installStage = com.gaibang.replyagent.app.sandbox.EnvInstallStage.CANCELLED
    }

    fun skipEnvInstall() {
        envSkipped = true
        envChoice = null
        config.update { it.copy(sandbox = it.sandbox.copy(backend = com.gaibang.replyagent.domain.model.SandboxBackend.NONE)) }
    }

    /** 引导里选「本机 cmd」：不装任何东西，命令直接在本机跑（无隔离）。 */
    fun chooseLocalEnv() {
        if (installJob?.isActive == true) cancelEnvInstall()
        envSkipped = true
        envChoice = "cmd"
        config.update {
            it.copy(
                sandbox = it.sandbox.copy(
                    backend = com.gaibang.replyagent.domain.model.SandboxBackend.LOCAL,
                    distro = "",
                    allowRunCommand = false
                )
            )
        }
        AppLogger.i("env", "引导选择本机 cmd（无文件系统隔离）")
        scope.launch { sandboxProbe = sandbox.probe(config.current.sandbox) }
    }

    /** 引导里选「WSL + Debian」：只做选择，真正安装要点面板上的按钮。 */
    fun chooseWslEnv() {
        envSkipped = false
        envChoice = "wsl"
    }

    fun finishOnboarding() {
        config.update { it.copy(ui = it.ui.copy(onboarded = true)) }
        onboardingStep = 0
        AppLogger.i("ui", "首次引导完成")
    }

    fun replayOnboarding() {
        config.update { it.copy(ui = it.ui.copy(onboarded = false)) }
        introDone = false
        onboardingStep = 0
        envSkipped = false
        envChoice = null
    }

    /* ── 主题 ── */

    var themeMode by androidx.compose.runtime.mutableStateOf("system")
    var systemDark by androidx.compose.runtime.mutableStateOf(false)

    val isDark: Boolean
        get() = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

    fun applyTheme(mode: String) {
        themeMode = mode
        config.update { it.copy(ui = it.ui.copy(theme = mode)) }
    }

    fun setVerboseLogging(on: Boolean) {
        AppLogger.minLevel = if (on) com.gaibang.replyagent.logging.Level.DEBUG else com.gaibang.replyagent.logging.Level.INFO
        config.update { it.copy(ui = it.ui.copy(verboseLogging = on)) }
    }

    /* ── 配置读写快捷方法 ── */

    fun update(transform: (com.gaibang.replyagent.domain.model.AppConfig) -> com.gaibang.replyagent.domain.model.AppConfig) {
        config.update(transform)
    }
}

private fun IntArray.toBitmap(size: Int): ImageBitmap {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, size, size, this, 0, size)
    return img.toComposeImageBitmap()
}
