package com.gaibang.replyagent.app.policy

import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.CustomRule
import com.gaibang.replyagent.domain.model.Identity
import com.gaibang.replyagent.domain.model.IdentityKind
import com.gaibang.replyagent.domain.model.RuleAction
import com.gaibang.replyagent.domain.model.RuleField
import com.gaibang.replyagent.domain.model.RuleOp
import com.gaibang.replyagent.domain.model.StrategyProfile

/** 本地过滤的结论。不通过的一律不进模型，省钱也省钱。 */
sealed class Verdict {
    data object Pass : Verdict()
    data class Filtered(val reason: String, val rule: String = "") : Verdict()
    data class Escalate(val reason: String, val rule: String = "") : Verdict()
    data class ForceReply(val reason: String) : Verdict()

    val passes get() = this is Pass || this is ForceReply
}

/**
 * 需求书第七章的「默认策略」在这儿落地。
 * 所有阈值、名单、规则都从 StrategyProfile 取，代码里不出现具体人名/常量。
 */
class FilterEngine {

    fun evaluate(
        comment: Comment,
        strategy: StrategyProfile,
        selfUid: String = "",
        alreadyHandled: (String) -> Boolean = { false }
    ): Verdict {
        val scope = strategy.scope

        if (scope.topLevelOnly && !comment.isTopLevel)
            return Verdict.Filtered("非顶层评论（楼中楼）", "回复范围")

        if (isSelf(comment, selfUid, strategy))
            return Verdict.Filtered("是自己发的评论", "跳过名单")

        if (isInIdentities(comment, strategy, IdentityKind.MAIN_UP)) {
            // 跳过名单里的主 UP 本人发言不需要回
            return Verdict.Filtered("发帖人（主 UP）自己的评论", "跳过名单")
        }

        // 抓取阶段第一次黑名单校验
        blacklistHit(comment, strategy)?.let {
            return Verdict.Filtered("命中黑名单「${it.name.ifBlank { it.uid }}」", "用户黑名单")
        }

        if (scope.skipAlreadyAnswered && (comment.answeredBySelf || comment.answeredByMainUp))
            return Verdict.Filtered("本人与主 UP 都已回过", "回复范围")

        if (scope.skipAlreadyAnswered && alreadyHandled(comment.id))
            return Verdict.Filtered("台账里已处理过", "已处理台账")

        val len = comment.content.trim().length
        if (len < scope.minContentLength)
            return Verdict.Filtered("内容过短（$len < ${scope.minContentLength}）", "回复范围")
        if (scope.maxContentLength in 1..len && scope.maxContentLength > scope.minContentLength) {
            return Verdict.Filtered("内容超长（$len > ${scope.maxContentLength}）", "回复范围")
        }

        if (scope.onlyIfContains.isNotEmpty()) {
            val hit = scope.onlyIfContains.filter { it.isNotBlank() }.any { comment.content.contains(it, true) }
            if (!hit) return Verdict.Filtered("不含任何「只回这些词」的关键词", "回复范围")
        }
        scope.neverIfContains.filter { it.isNotBlank() }.firstOrNull { comment.content.contains(it, true) }?.let {
            return Verdict.Filtered("命中「绝不回」关键词「$it」", "回复范围")
        }

        for (rule in scope.customRules.filter { it.enabled }) {
            if (matches(comment, rule)) {
                return when (rule.action) {
                    RuleAction.SKIP -> Verdict.Filtered("自定义规则「${rule.name.ifBlank { rule.id }}」", rule.name)
                    RuleAction.ESCALATE -> Verdict.Escalate("自定义规则「${rule.name.ifBlank { rule.id }}」要求转人工", rule.name)
                    RuleAction.FORCE_REPLY -> Verdict.ForceReply("自定义规则「${rule.name.ifBlank { rule.id }}」强制回复")
                }
            }
        }
        return Verdict.Pass
    }

    /** 发送前的第二道校验：抓取之后状态可能已经变了（需求书要求的双重校验） */
    fun recheckBeforeSend(
        comment: Comment,
        strategy: StrategyProfile,
        selfUid: String = ""
    ): Verdict {
        blacklistHit(comment, strategy)?.let {
            return Verdict.Filtered("发送前复查：命中黑名单「${it.name.ifBlank { it.uid }}」", "用户黑名单")
        }
        if (isSelf(comment, selfUid, strategy))
            return Verdict.Filtered("发送前复查：这是自己的评论", "跳过名单")
        return Verdict.Pass
    }

    fun isSelf(comment: Comment, selfUid: String, strategy: StrategyProfile): Boolean {
        if (selfUid.isNotBlank() && comment.authorUid == selfUid) return true
        return isInIdentities(comment, strategy, IdentityKind.SELF)
    }

    fun blacklistHit(comment: Comment, strategy: StrategyProfile): Identity? =
        identitiesOf(comment, strategy, IdentityKind.BLACKLIST)

    private fun isInIdentities(comment: Comment, strategy: StrategyProfile, kind: IdentityKind): Boolean =
        identitiesOf(comment, strategy, kind) != null

    private fun identitiesOf(comment: Comment, strategy: StrategyProfile, kind: IdentityKind): Identity? =
        strategy.identities.firstOrNull { id ->
            if (!id.enabled || id.kind != kind) return@firstOrNull false
            val byUid = id.uid.isNotBlank() && comment.authorUid == id.uid
            val byName = id.name.isNotBlank() && comment.authorName.equals(id.name, ignoreCase = true)
            byUid || byName
        }

    fun matches(comment: Comment, rule: CustomRule): Boolean {
        val target: String = when (rule.field) {
            RuleField.AUTHOR_UID -> comment.authorUid
            RuleField.AUTHOR_NAME -> comment.authorName
            RuleField.CONTENT -> comment.content
            RuleField.IS_TOP_LEVEL -> comment.isTopLevel.toString()
            RuleField.LIKE_COUNT -> comment.likes.toString()
            RuleField.PARENT_ID -> comment.parentId
        }
        val value = rule.value
        return when (rule.op) {
            RuleOp.EQ -> target.equals(value, ignoreCase = true)
            RuleOp.NE -> !target.equals(value, ignoreCase = true)
            RuleOp.CONTAINS -> target.contains(value, ignoreCase = true)
            RuleOp.REGEX -> runCatching { Regex(value).containsMatchIn(target) }.getOrElse { false }
            RuleOp.IN -> value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                .any { it.equals(target, ignoreCase = true) || target.contains(it, ignoreCase = true) }
            RuleOp.NOT_IN -> value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                .none { it.equals(target, ignoreCase = true) }
            RuleOp.GT -> compareNumbers(target, value) { a, b -> a > b }
            RuleOp.LT -> compareNumbers(target, value) { a, b -> a < b }
        }
    }

    /** 操作数写坏（不是数字）时判不命中：宁可让这条规则失效，也不能让它变成「所有评论都命中」 */
    private fun compareNumbers(target: String, value: String, cmp: (Double, Double) -> Boolean): Boolean {
        val a = target.trim().toDoubleOrNull() ?: return false
        val b = value.trim().toDoubleOrNull() ?: return false
        return cmp(a, b)
    }
}
