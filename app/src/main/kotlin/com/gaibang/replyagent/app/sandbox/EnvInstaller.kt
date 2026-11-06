package com.gaibang.replyagent.app.sandbox

import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.ProcessUtils
import com.gaibang.replyagent.util.ProcResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class EnvInstallStage { IDLE, RUNNING, DONE, FAILED, NEED_REBOOT, CANCELLED }

enum class StepStatus { PENDING, RUNNING, OK, SKIPPED, FAILED }

data class EnvStep(val id: String, val title: String, val detail: String) {
    companion object {
        fun plan() = listOf(
            EnvStep("probe", "检查 WSL", "看系统里有没有可用的 WSL 和发行版，已有就跳过前两步"),
            EnvStep("feature", "启用 WSL 功能", "wsl --install --no-distribution，会弹一次 UAC 管理员确认"),
            EnvStep("distro", "安装 Debian", "wsl --install -d Debian --no-launch，下载约 200–400MB"),
            EnvStep("toolchain", "补齐工具链", "apt-get 装 bash python3 curl jq git 等最小集合"),
            EnvStep("verify", "验证环境", "在发行版里跑 uname 与 python3，确认能执行命令")
        )
    }
}

data class EnvInstallResult(val stage: EnvInstallStage, val distro: String, val message: String)

/**
 * 自动化环境安装。
 *
 * 与原「只给命令不代劳」的设计不同：引导页点「开始安装」后这里会真的按顺序执行命令。
 * 边界仍然明确 —— 只做三件事：开 WSL 功能、装一个发行版、装最小工具集；
 * 需要管理员的那几步通过 UAC 由用户本人确认，不静默提权，也不碰任何账号密码。
 */
class EnvInstaller(private val sandbox: SandboxService) {

    private val wslEnv = mapOf("WSL_UTF8" to "1")

    suspend fun install(
        onStage: (EnvInstallStage) -> Unit,
        onStep: (index: Int, status: StepStatus, note: String) -> Unit,
        onLine: (String) -> Unit
    ): EnvInstallResult = withContext(Dispatchers.IO) {
        if (!ProcessUtils.isWindows()) {
            onStage(EnvInstallStage.FAILED)
            return@withContext EnvInstallResult(EnvInstallStage.FAILED, "", "当前系统不是 Windows，WSL 路线不可用")
        }

        val steps = EnvStep.plan()
        onStage(EnvInstallStage.RUNNING)
        AppLogger.i("env", "开始自动安装 Linux 环境")

        /* 0. 探测：已有可用发行版就直接收工 */
        onStep(0, StepStatus.RUNNING, "正在检查…")
        val probe = runCatching { sandbox.probe(com.gaibang.replyagent.domain.model.SandboxSettings()) }
            .getOrNull()
        var distro = when (probe) {
            is SandboxProbe.Ready -> probe.distros.firstOrNull { it.default }?.name
                ?: probe.distros.first().name
            else -> ""
        }
        val needFeature = probe !is SandboxProbe.Ready && probe !is SandboxProbe.WslNoDistro
        val needDistro = probe !is SandboxProbe.Ready

        if (probe is SandboxProbe.Ready) {
            onStep(0, StepStatus.OK, "已经可用：$distro")
            onStep(1, StepStatus.SKIPPED, "已有 WSL，跳过")
            onStep(2, StepStatus.SKIPPED, "已有发行版，跳过")
        } else if (probe is SandboxProbe.WslNoDistro) {
            onStep(0, StepStatus.OK, "WSL 已启用，但没有发行版")
            onStep(1, StepStatus.SKIPPED, "已有 WSL，跳过")
        } else {
            onStep(0, StepStatus.OK, "还没有 WSL，往下装")
        }

        /* 1. 启用 WSL 功能（UAC） */
        if (needFeature) {
            onStep(1, StepStatus.RUNNING, "等待管理员确认…")
            val r = elevated(
                "wsl.exe", listOf("--install", "--no-distribution"),
                timeoutMs = 900_000, onLine = onLine
            )
            if (!r.ok) {
                val cancelled = r.stdout.contains("取消", true) || r.stdout.contains("canceled", true) ||
                    r.stdout.contains("cancelled", true)
                val stage = if (cancelled) EnvInstallStage.CANCELLED else EnvInstallStage.FAILED
                onStep(1, StepStatus.FAILED, if (cancelled) "已取消" else brief(r))
                onStage(stage)
                return@withContext EnvInstallResult(stage, "", if (cancelled) "你取消了管理员确认" else brief(r))
            }
            onStep(1, StepStatus.OK, "WSL 功能已启用")
            if (looksLikeRebootRequired(r.stdout)) {
                onStep(2, StepStatus.FAILED, "需要重启电脑")
                onStage(EnvInstallStage.NEED_REBOOT)
                return@withContext EnvInstallResult(
                    EnvInstallStage.NEED_REBOOT, "",
                    "WSL 功能已开启，但系统要求重启。重启后回到这里点「继续安装」。"
                )
            }
        }

        /* 2. 安装 Debian */
        if (needDistro && distro.isBlank()) {
            onStep(2, StepStatus.RUNNING, "正在下载 Debian…")
            var r = wslStream(listOf("--install", "-d", "Debian", "--no-launch"), 1_800_000, onLine)
            if (!r.ok && needsAdmin(r.stdout)) {
                onLine("需要管理员权限，弹一次 UAC…")
                r = elevated(
                    "wsl.exe", listOf("--install", "-d", "Debian", "--no-launch"),
                    timeoutMs = 1_800_000, onLine = onLine
                )
            }
            if (!r.ok) {
                val reboot = looksLikeRebootRequired(r.stdout)
                onStep(2, StepStatus.FAILED, if (reboot) "需要重启电脑" else brief(r))
                val stage = if (reboot) EnvInstallStage.NEED_REBOOT else EnvInstallStage.FAILED
                onStage(stage)
                return@withContext EnvInstallResult(stage, "", brief(r))
            }
            distro = detectDistro() ?: "Debian"
            onStep(2, StepStatus.OK, "已安装 $distro")
        } else if (distro.isNotBlank()) {
            onStep(2, StepStatus.OK, "使用已有发行版 $distro")
        }

        /* 3. 工具链 */
        onStep(3, StepStatus.RUNNING, "apt-get 安装中…")
        val apt = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update -qq && apt-get install -y -qq --no-install-recommends " +
            "bash coreutils curl wget ca-certificates openssl python3 python3-pip jq sed gawk grep git tar gzip unzip procps less"
        val tc = runInDistro(distro, apt, 1_800_000, onLine)
        if (!tc.ok) {
            onStep(3, StepStatus.FAILED, brief(tc))
            onStage(EnvInstallStage.FAILED)
            return@withContext EnvInstallResult(EnvInstallStage.FAILED, distro, brief(tc))
        }
        onStep(3, StepStatus.OK, "工具链就绪")

        /* 4. 验证 */
        onStep(4, StepStatus.RUNNING, "验证中…")
        val v = runInDistro(distro, "uname -srm && python3 -V && echo OK-ENV", 120_000, onLine)
        if (!v.ok || !v.stdout.contains("OK-ENV")) {
            onStep(4, StepStatus.FAILED, brief(v))
            onStage(EnvInstallStage.FAILED)
            return@withContext EnvInstallResult(EnvInstallStage.FAILED, distro, brief(v))
        }
        val uname = v.stdout.lines().firstOrNull { it.contains("Linux") }?.trim().orEmpty()
        onStep(4, StepStatus.OK, uname.ifBlank { "验证通过" })

        onStage(EnvInstallStage.DONE)
        AppLogger.i("env", "Linux 环境安装完成：$distro")
        EnvInstallResult(EnvInstallStage.DONE, distro, "环境已就绪：$distro")
    }

    private fun detectDistro(): String? {
        val r = ProcessUtils.run(listOf("wsl.exe", "-l", "-q"), env = wslEnv, timeoutMs = 20_000)
        return r.stdout.lines()
            .map { it.replace("\u0000", "").trim().removePrefix("*").trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun runInDistro(distro: String, script: String, timeoutMs: Long, onLine: (String) -> Unit): ProcResult {
        val cmd = if (distro.isBlank())
            listOf("wsl.exe", "--", "bash", "-lc", script)
        else listOf("wsl.exe", "-d", distro, "-u", "root", "--", "bash", "-lc", script)
        return ProcessUtils.runStreaming(cmd, env = wslEnv, timeoutMs = timeoutMs, onLine = onLine)
    }

    private fun wslStream(args: List<String>, timeoutMs: Long, onLine: (String) -> Unit): ProcResult =
        ProcessUtils.runStreaming(listOf("wsl.exe") + args, env = wslEnv, timeoutMs = timeoutMs, onLine = onLine)

    /**
     * 需要管理员的那几步：走 UAC 让用户本人点「是」。
     * 子进程会开自己的控制台，输出拿不到，只回收退出码；拿不到退出码就当失败。
     */
    private fun elevated(exe: String, args: List<String>, timeoutMs: Long, onLine: (String) -> Unit): ProcResult {
        val argList = args.joinToString(",") { psQuote(it) }
        val ps = buildString {
            append("[Console]::OutputEncoding=[Text.Encoding]::UTF8; ")
            append("\$ErrorActionPreference='Stop'; ")
            append("try { ")
            append("\$p = Start-Process -FilePath ${psQuote(exe)} -ArgumentList $argList -Verb RunAs -Wait -PassThru; ")
            append("Write-Output ('EXITCODE=' + \$p.ExitCode) ")
            append("} catch { Write-Output ('LAUNCH-ERROR=' + \$_.Exception.Message); exit 9 }")
        }
        val r = ProcessUtils.runStreaming(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps),
            cwd = File(System.getProperty("java.io.tmpdir")),
            timeoutMs = timeoutMs,
            onLine = onLine
        )
        val code = Regex("EXITCODE=(\\d+)").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull()
        if (code == null) {
            val msg = Regex("LAUNCH-ERROR=(.+)").find(r.stdout)?.groupValues?.get(1)?.trim()
            return ProcResult(r.exit, r.stdout, msg ?: "没有拿到退出码", r.timedOut)
        }
        return ProcResult(code, r.stdout, r.stderr, r.timedOut)
    }

    private fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

    private fun needsAdmin(text: String) =
        listOf("管理员", "elevat", "administrator", "拒绝访问", "Access is denied", "0x80070005")
            .any { text.contains(it, true) } && !text.contains("已安装", true)

    private fun looksLikeRebootRequired(text: String) =
        listOf("重启", "restart", "reboot", "重新启动", "3010").any { text.contains(it, true) }

    private fun brief(r: ProcResult): String {
        val text = r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            .filterNot { it.startsWith("EXITCODE=") }
            .takeLast(3).joinToString(" / ")
        return text.ifBlank { "退出码 ${r.exit}" }.take(220)
    }
}
