package com.gaibang.replyagent.util

import com.gaibang.replyagent.logging.AppLogger
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class ProcResult(val exit: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
    val merged: String get() = (stdout + if (stderr.isBlank()) "" else "\n[stderr]\n$stderr").trim()
    val ok: Boolean get() = exit == 0 && !timedOut
}

object ProcessUtils {

    /**
     * 跑一条外部命令。Windows 上 wsl.exe 输出 UTF-16LE，原生命令行工具常是 GBK，
     * 这里按 BOM / 替换字符数量做编码嗅探，别把中文提示读成乱码。
     */
    fun run(
        command: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000,
        stdin: ByteArray? = null
    ): ProcResult {
        val pb = ProcessBuilder(command)
        cwd?.let { pb.directory(it) }
        env.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(false)

        val process = try {
            pb.start()
        } catch (e: Exception) {
            return ProcResult(-1, "", "启动失败：${e.message}（命令：${command.joinToString(" ")}）", false)
        }

        stdin?.let { process.outputStream.use { s -> s.write(it) } } ?: runCatching { process.outputStream.close() }

        val outCap = ByteArrayOutputStream()
        val errCap = ByteArrayOutputStream()
        val readers = listOf(
            Thread { process.inputStream.use { it.copyTo(outCap) } },
            Thread { process.errorStream.use { it.copyTo(errCap) } }
        ).onEach { it.isDaemon = true; it.start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            killTree(process)
            AppLogger.w("proc", "命令超时被终止：${command.joinToString(" ")}")
        }
        readers.forEach { runCatching { it.join(500) } }

        return ProcResult(
            exit = if (finished) process.exitValue() else -1,
            stdout = smartDecode(outCap.toByteArray()),
            stderr = smartDecode(errCap.toByteArray()),
            timedOut = !finished
        )
    }

    /**
     * 逐行回调版。装环境要跑好几分钟，界面得能实时看到输出，
     * 否则用户只会看到一个大按钮转圈然后怀疑卡死。
     * 调用方需保证命令输出是 UTF-8（wsl.exe 传 WSL_UTF8=1，powershell 先设 OutputEncoding）。
     */
    fun runStreaming(
        command: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000,
        onLine: (String) -> Unit
    ): ProcResult {
        val pb = ProcessBuilder(command)
        cwd?.let { pb.directory(it) }
        env.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)

        val process = try {
            pb.start()
        } catch (e: Exception) {
            val msg = "启动失败：${e.message}"
            onLine(msg)
            return ProcResult(-1, "", msg, false)
        }
        runCatching { process.outputStream.close() }

        val acc = StringBuilder()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { br ->
                    while (true) {
                        val raw = br.readLine() ?: break
                        val line = raw.replace("\u0000", "").trimEnd()
                        synchronized(acc) { acc.append(line).append('\n') }
                        runCatching { onLine(line) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            killTree(process)
            runCatching { onLine("… 超过 ${timeoutMs / 1000} 秒，已终止") }
            AppLogger.w("proc", "命令超时被终止：${command.joinToString(" ")}")
        }
        runCatching { reader.join(1_500) }

        val text = synchronized(acc) { acc.toString().trim() }
        return ProcResult(if (finished) process.exitValue() else -1, text, "", !finished)
    }

    /** 连带子进程一起杀，避免 wsl.exe 死了但里面的 bash 还活着 */    fun killTree(process: Process) {
        runCatching {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }.onFailure { process.destroy() }
    }

    fun smartDecode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-16LE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).replace("\u0000", "")
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad > 2) {
            val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
            if (gbk != null && gbk.count { it == '\uFFFD' } < bad) return gbk.replace("\u0000", "")
        }
        return utf8.replace("\u0000", "")
    }

    fun openInBrowser(url: String): ProcResult = when {
        System.getProperty("os.name").contains("Windows", true) ->
            run(listOf("cmd", "/c", "start", "", url), timeoutMs = 8_000)
        System.getProperty("os.name").contains("Mac", true) -> run(listOf("open", url))
        else -> run(listOf("xdg-open", url))
    }

    fun isWindows() = System.getProperty("os.name").contains("Windows", true)
}
