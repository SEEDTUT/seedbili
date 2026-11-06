package com.gaibang.replyagent

import com.gaibang.replyagent.app.policy.FilterEngine
import com.gaibang.replyagent.app.policy.Verdict
import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.CustomRule
import com.gaibang.replyagent.domain.model.Identity
import com.gaibang.replyagent.domain.model.IdentityKind
import com.gaibang.replyagent.domain.model.ReplyScope
import com.gaibang.replyagent.domain.model.RuleAction
import com.gaibang.replyagent.domain.model.RuleField
import com.gaibang.replyagent.domain.model.RuleOp
import com.gaibang.replyagent.domain.model.StrategyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterEngineTest {

    private val engine = FilterEngine()

    private fun strategy(
        scope: ReplyScope = ReplyScope(),
        identities: List<Identity> = emptyList()
    ) = StrategyProfile(id = "t", name = "测试", scope = scope, identities = identities)

    private fun comment(
        id: String = "c1",
        content: String = "这个编辑器叫什么名字？",
        parentId: String = "",
        rootId: String = "",
        uid: String = "900",
        name: String = "路人",
        likes: Int = 0,
        answeredBySelf: Boolean = false,
        answeredByMainUp: Boolean = false
    ) = Comment(
        id = id, targetId = "v1", parentId = parentId, rootId = rootId,
        authorUid = uid, authorName = name, content = content, likes = likes,
        answeredBySelf = answeredBySelf, answeredByMainUp = answeredByMainUp
    )

    @Test
    fun topLevelOnlyDropsFloorReplies() {
        val v = engine.evaluate(comment(parentId = "c0", rootId = "c0"), strategy())
        assertTrue(v is Verdict.Filtered)
        assertEquals("回复范围", (v as Verdict.Filtered).rule)
    }

    @Test
    fun topLevelOnlyOffLetsFloorRepliesThrough() {
        val v = engine.evaluate(
            comment(parentId = "c0", rootId = "c0"),
            strategy(ReplyScope(topLevelOnly = false))
        )
        assertTrue(v.passes)
    }

    @Test
    fun ownCommentsAreNeverAnswered() {
        val s = strategy(identities = listOf(Identity("i", IdentityKind.SELF, uid = "", name = "我自己")))
        assertTrue(engine.evaluate(comment(uid = "900"), s, selfUid = "900") is Verdict.Filtered)
        // 名称命中也算，UID 拿不到的场景要有兜底
        assertTrue(engine.evaluate(comment(name = "我自己", uid = ""), s) is Verdict.Filtered)
    }

    @Test
    fun mainUpOwnSpeechIsSkippedButOthersAreNot() {
        val s = strategy(identities = listOf(Identity("i", IdentityKind.MAIN_UP, uid = "777", name = "主UP")))
        assertTrue(engine.evaluate(comment(uid = "777"), s) is Verdict.Filtered)
        assertTrue(engine.evaluate(comment(uid = "778"), s).passes)
    }

    @Test
    fun blacklistHitsByUidOrNameAndDisabledEntryIsIgnored() {
        val byUid = strategy(identities = listOf(Identity("i", IdentityKind.BLACKLIST, uid = "666", name = "广告机")))
        assertTrue(engine.evaluate(comment(uid = "666"), byUid) is Verdict.Filtered)
        val byName = strategy(identities = listOf(Identity("i", IdentityKind.BLACKLIST, uid = "", name = "广告机")))
        assertTrue(engine.evaluate(comment(name = "广告机", uid = "1"), byName) is Verdict.Filtered)
        val off = strategy(identities = listOf(Identity("i", IdentityKind.BLACKLIST, uid = "666", enabled = false)))
        assertTrue(engine.evaluate(comment(uid = "666"), off).passes)
    }

    @Test
    fun alreadyAnsweredRespectsTheSwitch() {
        val on = strategy(ReplyScope(skipAlreadyAnswered = true))
        assertTrue(engine.evaluate(comment(answeredBySelf = true), on) is Verdict.Filtered)
        assertTrue(engine.evaluate(comment(answeredByMainUp = true), on) is Verdict.Filtered)
        val off = strategy(ReplyScope(skipAlreadyAnswered = false))
        assertTrue(engine.evaluate(comment(answeredBySelf = true), off).passes)
    }

    @Test
    fun ledgerAlreadyHandledCountsAsHandled() {
        val s = strategy()
        // 台账说没处理过 → 放行
        assertTrue(engine.evaluate(comment(), s) { false }.passes)
        // 台账说处理过了 → 本地就拦掉，一次模型都不调
        assertTrue(engine.evaluate(comment(), s) { true } is Verdict.Filtered)
    }

    @Test
    fun lengthWindowIsInclusiveBelowAndExclusiveAbove() {
        val s = strategy(ReplyScope(minContentLength = 4, maxContentLength = 10))
        assertTrue(engine.evaluate(comment(content = "三个字"), s) is Verdict.Filtered)
        assertTrue(engine.evaluate(comment(content = "四个字呀"), s).passes)
        assertTrue(engine.evaluate(comment(content = "一二三四五六七八九十一"), s) is Verdict.Filtered)
    }

    @Test
    fun keywordWhitelistAndBlacklist() {
        val s = strategy(ReplyScope(onlyIfContains = listOf("教程", "怎么"), neverIfContains = listOf("微信")))
        assertTrue(engine.evaluate(comment(content = "这个教程第几步？"), s).passes)
        assertTrue(engine.evaluate(comment(content = "写得不错"), s) is Verdict.Filtered)
        assertTrue(engine.evaluate(comment(content = "教程不错，加个微信"), s) is Verdict.Filtered)
    }

    @Test
    fun customRulesCoverAllThreeActions() {
        val s = strategy(ReplyScope(customRules = listOf(
            CustomRule("r1", "高赞转人工", RuleField.LIKE_COUNT, RuleOp.GT, "100", RuleAction.ESCALATE),
            CustomRule("r2", "抽奖跳过", RuleField.CONTENT, RuleOp.CONTAINS, "抽奖", RuleAction.SKIP),
            CustomRule("r3", "提问强制回", RuleField.CONTENT, RuleOp.REGEX, "[?？]$", RuleAction.FORCE_REPLY)
        )))
        assertTrue(engine.evaluate(comment(content = "求个链接", likes = 500), s) is Verdict.Escalate)
        assertTrue(engine.evaluate(comment(content = "抽奖来一个"), s) is Verdict.Filtered)
        val forced = engine.evaluate(comment(content = "用什么编辑器？"), s)
        assertTrue(forced is Verdict.ForceReply)
        assertTrue(forced.passes)
        // 规则按顺序命中，先匹配的赢
        assertTrue(engine.evaluate(comment(content = "抽奖吗？", likes = 500), s) is Verdict.Escalate)
    }

    @Test
    fun disabledCustomRuleDoesNotApply() {
        val s = strategy(ReplyScope(customRules = listOf(
            CustomRule("r", "关掉的", RuleField.CONTENT, RuleOp.CONTAINS, "抽奖", RuleAction.SKIP, enabled = false)
        )))
        assertTrue(engine.evaluate(comment(content = "抽奖来一个"), s).passes)
    }

    @Test
    fun ruleOperatorsHandleMalformedInputWithoutThrowing() {
        val like = comment(content = "x", likes = 5)
        // 值不是数字时按 0 处理，比较不成立也不许抛
        assertFalse(engine.matches(like, CustomRule("a", field = RuleField.LIKE_COUNT, op = RuleOp.GT, value = "abc")))
        assertTrue(engine.matches(like, CustomRule("a2", field = RuleField.LIKE_COUNT, op = RuleOp.GT, value = "2")))
        // 坏正则一律判不命中：宁可放过也不能让一轮 run 崩在这里
        assertFalse(engine.matches(like, CustomRule("b", field = RuleField.CONTENT, op = RuleOp.REGEX, value = "([")))
        assertTrue(engine.matches(like, CustomRule("c", field = RuleField.AUTHOR_UID, op = RuleOp.IN, value = "900，1")))
    }

    @Test
    fun recheckBeforeSendCatchesLateBlacklistAndSelf() {
        val s = strategy(identities = listOf(Identity("i", IdentityKind.BLACKLIST, uid = "666")))
        assertTrue(engine.recheckBeforeSend(comment(uid = "666"), s) is Verdict.Filtered)
        assertTrue(engine.recheckBeforeSend(comment(uid = "900"), s, selfUid = "900") is Verdict.Filtered)
        assertTrue(engine.recheckBeforeSend(comment(uid = "1"), s).passes)
    }

    @Test
    fun fingerprintIgnoresWhitespaceButNotAuthor() {
        assertEquals(
            comment(content = " 你好 呀 ").fingerprint(),
            comment(content = "你好呀").fingerprint()
        )
        assertFalse(comment(content = "你好").fingerprint() == comment(content = "你好", uid = "2").fingerprint())
    }
}
