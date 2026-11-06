package com.gaibang.replyagent.app.ledger

import com.gaibang.replyagent.domain.model.LedgerEntry
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.JsonCompact
import com.gaibang.replyagent.util.Paths2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 已处理台账。JSONL 追加写 + 内存索引：
 * 崩溃后重启仍读得到，且「同一条评论不会被回两次」这件事靠它而不是靠模型记性。
 */
class LedgerService(private val file: Path = Paths2.data.resolve("ledger.jsonl")) {

    private val index = ConcurrentHashMap<String, LedgerEntry>()
    private val _entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()

    @Volatile private var dirtyCount = 0

    fun load(): Int {
        index.clear()
        if (!Files.exists(file)) {
            _entries.value = emptyList()
            return 0
        }
        var broken = 0
        Files.readAllLines(file, StandardCharsets.UTF_8).forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching {
                val e = JsonCompact.decodeFromString(LedgerEntry.serializer(), line)
                index[key(e)] = e
            }.onFailure { broken++ }
        }
        publish()
        if (broken > 0) AppLogger.w("ledger", "有 $broken 行台账解析失败（多半是被强制中断写坏的最后一行），已跳过")
        AppLogger.i("ledger", "台账载入 ${index.size} 条")
        return index.size
    }

    private fun key(e: LedgerEntry): String = e.commentId

    fun handled(commentId: String): Boolean = index.containsKey(commentId)

    fun entryFor(commentId: String): LedgerEntry? = index[commentId]

    fun record(entry: LedgerEntry) {
        index[entry.commentId] = entry
        appendLine(entry)
        publish()
    }

    private fun appendLine(entry: LedgerEntry) {
        try {
            Files.createDirectories(file.parent)
            RandomAccessFile(file.toFile(), "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((JsonCompact.encodeToString(LedgerEntry.serializer(), entry) + "\n")
                    .toByteArray(StandardCharsets.UTF_8))
                raf.fd.sync()
            }
        } catch (e: Exception) {
            AppLogger.e("ledger", "台账写入失败", e)
        }
    }

    /** 只失败的可以重试；SENT/SKIPPED 视为已处理，避免重复回 */
    fun retryable(commentId: String): Boolean =
        index[commentId]?.status == LedgerStatus.FAILED

    fun countsForToday(zone: ZoneId = ZoneId.systemDefault()): Map<LedgerStatus, Int> {
        val today = LocalDate.now(zone)
        return index.values.filter {
            Instant.ofEpochMilli(it.atEpochMs).atZone(zone).toLocalDate() == today
        }.groupingBy { it.status }.eachCount()
    }

    fun all(): List<LedgerEntry> = index.values.sortedByDescending { it.atEpochMs }

    private fun publish() {
        _entries.value = all()
    }

    fun prune(retentionDays: Int): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        val doomed = index.values.filter { it.atEpochMs < cutoff }.map { it.commentId }
        doomed.forEach { index.remove(it) }
        if (doomed.isEmpty()) return 0
        compact()
        publish()
        AppLogger.i("ledger", "按保留期清理 ${doomed.size} 条（${retentionDays} 天）")
        return doomed.size
    }

    /** 重写整个文件，去掉过期与重复键 */
    fun compact() {
        val snapshot = index.values.sortedBy { it.atEpochMs }
        val tmp = file.resolveSibling("ledger.tmp")
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { w ->
            snapshot.forEach { w.write(JsonCompact.encodeToString(LedgerEntry.serializer(), it)); w.newLine() }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        dirtyCount = snapshot.size
    }

    fun exportCsv(dest: Path): Path {
        val sb = StringBuilder("\uFEFF") // BOM，Excel 直接双击不乱码
        sb.append("评论id,目标,动作,状态,平台码,评论原文,回复内容,理由,模型,耗时ms,时间\n")
        all().forEach { e ->
            sb.append(listOf(
                e.commentId, e.targetId, e.action, e.status.name, e.platformCode.toString(),
                csv(e.commentContent), csv(e.replyContent), csv(e.reason), e.model,
                e.latencyMs.toString(),
                Instant.ofEpochMilli(e.atEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
            ).joinToString(",")).append("\n")
        }
        Files.write(dest, sb.toString().toByteArray(StandardCharsets.UTF_8))
        return dest
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""

    fun clearAll() {
        index.clear()
        runCatching { Files.deleteIfExists(file) }
        publish()
    }

    fun size(): Int = index.size
}
