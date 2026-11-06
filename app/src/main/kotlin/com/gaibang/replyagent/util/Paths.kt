package com.gaibang.replyagent.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 应用数据目录。全部集中在这里，卸载/清理只要删一个目录。 */
object Paths2 {
    val appName = "ReplyAgent"

    val root: Path by lazy {
        val appdata = System.getenv("APPDATA")
            ?: System.getProperty("user.home") + "/.config"
        Paths.get(appdata, appName)
    }

    val config: Path get() = root.resolve("config")
    val data: Path get() = root.resolve("data")
    val logs: Path get() = root.resolve("logs")
    val secrets: Path get() = root.resolve("secrets")
    val plugins: Path get() = root.resolve("plugins")
    val sandboxWork: Path get() = root.resolve("sandbox").resolve("work")
    val exports: Path get() = root.resolve("exports")

    fun ensureAll() {
        listOf(root, config, data, logs, secrets, plugins, sandboxWork, exports).forEach {
            Files.createDirectories(it)
        }
    }

    fun resolveUnderRoot(p: String): Path = root.resolve(p).normalize().also {
        require(it.startsWith(root)) { "路径越界: $p" }
    }

    /** 供沙箱/WSL 侧访问时给出的说明文本 */
    fun describe(): String = "数据目录: $root"
}
