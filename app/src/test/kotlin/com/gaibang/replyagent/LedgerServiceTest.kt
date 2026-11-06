package com.gaibang.replyagent

import com.gaibang.replyagent.app.ledger.LedgerService
import com.gaibang.replyagent.domain.model.LedgerEntry
import com.gaibang.replyagent.domain.model.LedgerStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerServiceTest {

    private fun tmp(): java.nio.file.Path = Files.createTempDirectory("ledger-test").resolve("ledger.jsonl")

    private fun entry(id: String, status: LedgerStatus, at: Long = System.currentTimeMillis()) = LedgerEntry(
        commentId = id, targetId = "v1", action = "reply", status = status,
        commentContent = "评论 $id", replyContent = "回复 $id", atEpochMs = at
    )

    @Test
    fun survivesReloadBecauseThatIsTheWholePoint() {
        val file = tmp()
        val a = LedgerService(file)
        a.load()
        a.record(entry("1", LedgerStatus.SENT))
        a.record(entry("2", LedgerStatus.SKIPPED))

        val b = LedgerService(file)
        assertEquals(2, b.load())
        assertTrue(b.handled("1"))
        assertTrue(b.handled("2"))
        assertFalse(b.handled("3"))
    }

    @Test
    fun sameCommentIdOverwritesItselfInsteadOfDoubling() {
        val file = tmp()
        val l = LedgerService(file)
        l.load()
        l.record(entry("1", LedgerStatus.FAILED))
        l.record(entry("1", LedgerStatus.SENT))
        val reload = LedgerService(file)
        assertEquals(2, Files.readAllLines(file).size, "追加写要在文件里留两行历史")
        assertEquals(1, reload.load())   // 索引按 commentId 去重，只认最后一条
        assertEquals(1, reload.size())   // 索引里只有一条
        assertEquals(LedgerStatus.SENT, reload.entryFor("1")?.status)
    }

    @Test
    fun onlyFailedRowsAreRetryable() {
        val l = LedgerService(tmp())
        l.load()
        l.record(entry("f", LedgerStatus.FAILED))
        l.record(entry("s", LedgerStatus.SENT))
        l.record(entry("k", LedgerStatus.SKIPPED))
        l.record(entry("e", LedgerStatus.ESCALATED))
        assertTrue(l.retryable("f"))
        assertFalse(l.retryable("s"))
        assertFalse(l.retryable("k"))
        assertFalse(l.retryable("e"))
        assertFalse(l.retryable("never-seen"))
    }

    @Test
    fun tornLastLineFromACrashIsSkippedNotFatal() {
        val file = tmp()
        Files.createDirectories(file.parent)
        Files.write(
            file,
            ("""{"commentId":"1","targetId":"v","action":"reply","status":"SENT","atEpochMs":1}""" + "\n" +
                """{"commentId":"2","targ""").toByteArray()
        )
        val l = LedgerService(file)
        assertEquals(1, l.load())
        assertTrue(l.handled("1"))
    }

    @Test
    fun pruneDropsOnlyRowsOlderThanTheRetentionWindow() {
        val file = tmp()
        val l = LedgerService(file)
        l.load()
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        l.record(entry("old", LedgerStatus.SENT, at = now - 400 * day))
        l.record(entry("new", LedgerStatus.SENT, at = now - 10 * day))
        assertEquals(1, l.prune(180), "只该清掉超出保留期的那一条")
        assertFalse(l.handled("old"))
        assertTrue(l.handled("new"))
        // 清理要真的落到文件上，不然重启又回来了
        assertEquals(1, LedgerService(file).load())
    }

    @Test
    fun compactShrinksTheFileToTheLiveIndex() {
        val file = tmp()
        val l = LedgerService(file)
        l.load()
        repeat(50) { l.record(entry("$it", LedgerStatus.SENT)) }
        assertTrue(Files.readAllLines(file).size >= 50)
        l.compact()
        assertEquals(50, Files.readAllLines(file).size)
        assertEquals(50, LedgerService(file).load())
    }

    @Test
    fun csvExportQuotesAndCarriesBomForExcel() {
        val file = tmp()
        val l = LedgerService(file)
        l.load()
        l.record(
            LedgerEntry(
                commentId = "1", targetId = "v", action = "reply", status = LedgerStatus.SENT,
                commentContent = "有, 逗号 和 \"引号\"", replyContent = "第一行\n第二行"
            )
        )
        val dest = l.exportCsv(file.resolveSibling("out.csv"))
        val text = Files.readString(dest, Charsets.UTF_8)
        assertTrue(text.startsWith("﻿"), "没有 BOM 的话 Excel 双击会乱码")
        assertTrue(text.contains("\"有, 逗号 和 \"\"引号\"\"\""))
        assertTrue(text.contains("第一行 第二行"), "换行必须被压掉，否则 CSV 串列")
    }

    @Test
    fun countsForTodayIgnoresOlderDays() {
        val l = LedgerService(tmp())
        l.load()
        val day = 86_400_000L
        l.record(entry("today", LedgerStatus.SENT))
        l.record(entry("ago", LedgerStatus.SENT, at = System.currentTimeMillis() - 5 * day))
        assertEquals(1, l.countsForToday()[LedgerStatus.SENT])
    }

    @Test
    fun clearAllWipesMemoryAndDisk() {
        val file = tmp()
        val l = LedgerService(file)
        l.load()
        l.record(entry("1", LedgerStatus.SENT))
        l.clearAll()
        assertEquals(0, l.size())
        assertFalse(Files.exists(file))
    }
}
