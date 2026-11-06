package com.gaibang.replyagent.app.sandbox

import com.gaibang.replyagent.domain.model.SandboxBackend
import com.gaibang.replyagent.domain.model.SandboxSettings
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.Paths2
import com.gaibang.replyagent.util.ProcessUtils
import com.gaibang.replyagent.util.ProcResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

data class WslDistro(val name: String, val version: Int, val default: Boolean, val state: String)

sealed class SandboxProbe {
    data object NotWindows : SandboxProbe()
    data class WslMissing(val detail: String) : SandboxProbe()
    data class WslNoDistro(val detail: String) : SandboxProbe()
    data class Ready(val distros: List<WslDistro>) : SandboxProbe()
    /** 用本机进程当「沙箱」—— 没有任何隔离，界面上必须这样写明 */
    data class LocalOnly(val warning: String) : SandboxProbe()
}

data class CommandOutcome(
    val exit: Int,
    val output: String,
    val timedOut: Boolean,
    val how: String,
    val usedWsl: Boolean = false,
)

/**
 * 虚拟 Linux 环境管理。
 * 原则：探测可以自动做，改动用户系统（装 WSL / 装包）一律只给命令，由用户自己执行。
 */
class SandboxService {

    val workDir = Paths2.sandboxWork

    suspend fun probe(settings: SandboxSettings): SandboxProbe = withContext(Dispatchers.IO) {
        Files.createDirectories(workDir)
        when (settings.backend) {
            SandboxBackend.NONE -> {
                if (!ProcessUtils.isWindows()) SandboxProbe.NotWindows
                else {
                    val distros = wslList()
                    when {
                        distros == null -> SandboxProbe.WslMissing("未检测到 wsl.exe，或 WSL 功能未启用")
                        distros.isEmpty() -> SandboxProbe.WslNoDistro("WSL 已装，但里面还没有发行版")
                        else -> SandboxProbe.Ready(distros)
                    }
                }
            }
            SandboxBackend.WSL -> {
                val distros = wslList()
                when {
                    distros == null -> SandboxProbe.WslMissing("wsl.exe 调用失败，可能刚开启功能还没重启")
                    distros.isEmpty() -> SandboxProbe.WslNoDistro("没有发行版，先执行下面的安装命令")
                    else -> SandboxProbe.Ready(distros)
                }
            }
            SandboxBackend.LOCAL -> SandboxProbe.LocalOnly(
                "当前用本机进程执行命令，没有文件系统隔离。模型让你执行的每一条都会真的落在你自己电脑上。"
            )
        }
    }

    /** 返回 null = WSL 不存在；空列表 = 有 WSL 但没发行版 */
    private fun wslList(): List<WslDistro>? {
        val r = ProcessUtils.run(listOf("wsl.exe", "-l", "-v"), timeoutMs = 12_000)
        if (!r.ok && r.stdout.isBlank()) return null
        val text = r.stdout
        if (text.contains("没有已安装的发行版") || text.contains("no installed", true) ||
            text.contains("\u0000\u0000")
        ) {
            if (text.isBlank()) return emptyList()
        }
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val rows = lines.dropWhile { it.contains("NAME", true) || it.contains("发行版", true) }
        val out = rows.mapNotNull { line ->
            val cols = line.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            if (cols.isEmpty()) return@mapNotNull null
            val name = cols[0].removePrefix("*").trim().ifBlank { return@mapNotNull null }
            WslDistro(
                name = name,
                version = cols.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 2,
                default = line.trimStart().startsWith("*"),
                state = cols.getOrNull(1) ?: ""
            )
        }
        return if (out.isEmpty() && !r.ok) null else out
    }

    /** 给界面的「一键初始化」文案。工具自己不提权、不静默装。 */
    fun initGuide(): List<Pair<String, String>> = listOf(
        "1. 以管理员身份打开 PowerShell，装 WSL（会要求重启）" to
                "wsl --install --no-distribution",
        "2. 重启后，安装一个轻量发行版（Debian 约 200–400MB，Alpine 约 50MB）" to
                "wsl --install -d Debian",
        "3. 首次进入发行版会要求创建 Linux 用户，然后补齐最小工具集" to
                "sudo apt-get update && sudo apt-get install -y bash coreutils curl wget ca-certificates openssl python3 python3-pip jq sed gawk grep git tar gzip unzip",
        "4. 回到本工具的「Linux 环境」页，点重新探测，选中这个发行版" to "",
        "可选：只要一个几十 MB 的最小环境，用 Alpine 代替 Debian" to
                "wsl --install -d Alpine && apk add --no-cache bash coreutils curl wget ca-certificates openssl python3 py3-pip jq sed grep git tar gzip unzip"
    )

    suspend fun runCommand(command: String, distro: String, timeoutSeconds: Int): CommandOutcome =
        withContext(Dispatchers.IO) {
            val r: ProcResult = when {
                distro.isNotBlank() -> ProcessUtils.run(
                    listOf("wsl.exe", "-d", distro, "--cd", wslPathOfWorkDir(), "--", "sh", "-lc", command),
                    timeoutMs = timeoutSeconds * 1000L
                )
                else -> ProcessUtils.run(
                    listOf("wsl.exe", "--cd", wslPathOfWorkDir(), "--", "sh", "-lc", command),
                    timeoutMs = timeoutSeconds * 1000L
                )
            }
            CommandOutcome(
                exit = r.exit,
                output = r.merged.ifBlank { "(无输出)" },
                timedOut = r.timedOut,
                how = if (distro.isNotBlank()) "WSL($distro)" else "WSL(默认发行版)"
            )
        }

    suspend fun runLocal(command: String, timeoutSeconds: Int): CommandOutcome = withContext(Dispatchers.IO) {
        val r = ProcessUtils.run(
            listOf("cmd", "/c", command),
            cwd = workDir.toFile(),
            timeoutMs = timeoutSeconds * 1000L
        )
        CommandOutcome(r.exit, r.merged.ifBlank { "(无输出)" }, r.timedOut, "本机 cmd（无隔离）")
    }

    /** D:\...\sandbox\work → /mnt/d/.../sandbox/work */
    fun wslPathOfWorkDir(): String {
        val abs = workDir.toAbsolutePath().normalize().toString().replace('\\', '/')
        val drive = abs.take(1).lowercase()
        return if (abs.length > 2 && abs[1] == ':') "/mnt/$drive${abs.substring(2)}" else abs
    }

    fun writeFileInWorkDir(name: String, content: String): Result<String> = runCatching {
        Files.createDirectories(workDir)
        val safe = name.replace("..", "_").trimStart('/', '\\')
        val target = workDir.resolve(safe).normalize()
        require(target.startsWith(workDir)) { "写入路径越界" }
        Files.writeString(target, content)
        AppLogger.i("sandbox", "工作目录写入 $safe (${content.length} 字)")
        target.toString()
    }

    /** 一键清理：只删自己创建的工作目录内容，不动用户机器上的任何别的东西 */
    fun cleanWorkDir(): Int {
        if (!Files.exists(workDir)) return 0
        var n = 0
        Files.walk(workDir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                if (p != workDir) {
                    runCatching { Files.delete(p) }.onSuccess { n++ }
                }
            }
        }
        AppLogger.i("sandbox", "清理工作目录，删除 $n 个文件/目录")
        return n
    }

    fun workDirPath(): String = workDir.toString()
}
