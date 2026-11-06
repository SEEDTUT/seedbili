package com.gaibang.replyagent.agent

import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.StrategyProfile
import java.net.URI

/**
 * 安全底线。需求书第七章里唯一不允许用户在界面关闭的东西就在这里 —— 所以它写成 object，
 * 不提供任何开关参数；策略里也没有对应字段。
 */
object Guardrails {

    const val UNTRUSTED_TAG = "untrusted_comment_data"
    const val REPLY_MAX_HARD_LIMIT = 800

    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00AD]")
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
    private val ROLE_MARKERS = Regex("(?im)^\\s*((?:system|assistant|user|developer|tool)\\s*[:：]|【\\s*(系统|管理员|开发者)\\s*】|</?\\w+_data>)")
    // 聊天模板的定界符不只在行首：[/INST] 出现在句子中间同样是语法，所以单独一刀、不限位置
    private val TEMPLATE_MARKERS = Regex("(?i)\\[/?(?:INST|SYS)]|<\\|(?:(?:im_(?:start|end))|(?:sys|end|user|assistant|tool))\\|>")

    /**
     * 把不可信的评论正文变成「只是文本」：
     * 1) 去零宽/控制字符（防用同形字符绕过关键词过滤，也防伪造换行指令）
     * 2) 把看起来像角色切换的行首标记做全角化，使其失去语法意义
     * 3) 截断长度
     * 4) 外层由 PromptRenderer 用 <untrusted_comment_data> 包裹
     */
    fun sanitizeUntrusted(text: String, maxChars: Int = 800): String {
        var out = ZERO_WIDTH.replace(text, "")
        out = CONTROL_CHARS.replace(out, "")
        out = out.replace("\r\n", "\n")
        out = TEMPLATE_MARKERS.replace(out) { "［模板标记］" }
        out = ROLE_MARKERS.replace(out) { m ->
            "［文本］" + m.value.replace(':', '：')
        }
        out = out.trim()
        return if (out.length > maxChars) out.take(maxChars) + "…（内容过长已截断）" else out
    }

    fun sanitizeUntrusted(comment: Comment, maxChars: Int = 800): String =
        sanitizeUntrusted(comment.content, maxChars)

    private val INTERNAL_MARKERS = listOf("<$UNTRUSTED_TAG", "</$UNTRUSTED_TAG", "<system_rules>", "</system_rules>")

    /** 模型产出的回复文本，发出去之前必须过这里 */
    fun validateReply(raw: String, strategy: StrategyProfile): ReplyCheck {
        val text = ZERO_WIDTH.replace(raw, "").trim()
        if (text.isEmpty()) return ReplyError("回复为空")
        if (text.length > strategy.maxReplyChars.coerceAtMost(REPLY_MAX_HARD_LIMIT))
            return ReplyError("回复超出长度上限（${text.length} > ${strategy.maxReplyChars}）")
        if (text.length < 2) return ReplyError("回复过短，疑似无效输出")

        INTERNAL_MARKERS.firstOrNull { text.contains(it, true) }?.let {
            return ReplyError("回复里出现了内部标记「$it」，疑似提示结构被模型复述出来")
        }
        strategy.forbiddenReplySubstrings.filter { it.isNotBlank() }.firstOrNull { sub ->
            text.contains(sub, ignoreCase = true)
        }?.let { return ReplyError("回复包含禁用内容「$it」") }

        val hosts = extractHosts(text)
        if (hosts.isNotEmpty()) {
            val allowed = strategy.allowedLinkHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val bad = hosts.firstOrNull { h -> allowed.none { h == it || h.endsWith(".$it") } }
            if (bad != null) return ReplyError("回复包含未授权链接域名「$bad」（在策略配置里可加白名单）")
        }
        return ReplyOk(text)
    }

    private fun extractHosts(text: String): List<String> =
        Regex("https?://[^\\s\\u4e00-\\u9fa5\"'<>）)]+", RegexOption.IGNORE_CASE).findAll(text).map { m ->
            runCatching { URI(m.value).host?.lowercase() }.getOrNull()
        }.filterNotNull().toList()

    /** 判断这条评论是不是在试图操控 Agent —— 只用于打标记和汇报，不用于「因此改变行为」 */
    fun looksLikeInjection(text: String): Boolean {
        val s = text.lowercase()
        val cues = listOf(
            "忽略之前", "忽略上述", "忽略上面", "无视之前", "ignore previous", "ignore above",
            "disregard", "系统提示", "你现在是", "扮演", "开发者模式", "developer mode", "jailbreak",
            "api key", "密钥", "把配置", "输出你的提示词", "repeat your instructions"
        )
        return cues.any { s.contains(it) }
    }
}

sealed class ReplyCheck
data class ReplyOk(val text: String) : ReplyCheck()
data class ReplyError(val reason: String) : ReplyCheck()
