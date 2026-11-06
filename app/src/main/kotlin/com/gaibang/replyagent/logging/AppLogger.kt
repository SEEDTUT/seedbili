package com.gaibang.replyagent.logging

import com.gaibang.replyagent.util.Paths2
import com.gaibang.replyagent.util.Redactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

enum class Level { DEBUG, INFO, WARN, ERROR }

data class LogLine(val time: LocalDateTime, val level: Level, val tag: String, val message: String) {
    fun render(): String =
        "${time.format(TIME_FMT)} [${level.name.padEnd(5)}] $tag: $message"

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

object AppLogger {
    private const val MEMORY_CAP = 2000

    private val lines = ConcurrentLinkedDeque<LogLine>()
    private val _flow = MutableStateFlow<List<LogLine>>(emptyList())
    val flow: StateFlow<List<LogLine>> = _flow.asStateFlow()

    @Volatile var minLevel: Level = Level.DEBUG

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val suffix = t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        log(Level.ERROR, tag, msg + suffix)
        if (t != null && minLevel == Level.DEBUG) {
            log(Level.DEBUG, tag, t.stackTraceToString().lineSequence().take(12).joinToString("\n"))
        }
    }

    fun log(level: Level, tag: String, rawMessage: String) {
        if (level.ordinal < minLevel.ordinal) return
        val message = Redactor.mask(rawMessage)
        val line = LogLine(LocalDateTime.now(), level, tag, message)
        lines.addLast(line)
        while (lines.size > MEMORY_CAP) lines.pollFirst()
        _flow.value = lines.toList()
        writeToDisk(line)
    }

    private fun writeToDisk(line: LogLine) {
        runCatching {
            val file = Paths2.logs.resolve("app-${LocalDate.now()}.log")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                line.render() + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    fun pruneOldFiles(keepDays: Int = 14) {
        runCatching {
            val cutoff = LocalDate.now().minusDays(keepDays.toLong())
            Paths2.logs.toFile().listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".log") }
                ?.forEach { f ->
                    val day = f.name.removePrefix("app-").removeSuffix(".log")
                    runCatching { if (LocalDate.parse(day).isBefore(cutoff)) f.delete() }
                }
        }
    }

    fun clearMemory() {
        lines.clear()
        _flow.value = emptyList()
    }
}
