package com.gaibang.replyagent.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 平台无关的评论对象。适配器负责把各家字段翻译成这里。 */
@Serializable
data class Comment(
    val id: String,
    val targetId: String,
    val parentId: String = "",
    val rootId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val content: String = "",
    val likes: Int = 0,
    val createdAtEpochSec: Long = 0,
    val answeredBySelf: Boolean = false,
    val answeredByMainUp: Boolean = false,
    val raw: JsonObject? = null
) {
    val isTopLevel: Boolean get() = parentId.isBlank() && rootId.isBlank()

    fun fingerprint(): String {
        val norm = content.trim().replace(Regex("\\s+"), "")
        return "${authorUid.ifBlank { authorName }}|$norm"
    }
}

@Serializable
data class Target(
    val platformId: String,
    val id: String,
    val title: String = "",
    /** 平台内部用于接口的数字 id，例如 B 站的 aid */
    val numericId: String = "",
    val kind: TargetKind = TargetKind.VIDEO,
    val url: String = ""
)

@Serializable
enum class TargetKind { VIDEO, ARTICLE, DYNAMIC }

@Serializable
sealed class LoginStatus {
    @Serializable object Unknown : LoginStatus()
    @Serializable object NotLoggedIn : LoginStatus()
    @Serializable data class LoggedIn(val uid: String, val name: String, val mid: String = uid, val avatar: String = "") : LoginStatus()
    @Serializable data class Expired(val lastUid: String = "") : LoginStatus()
}

@Serializable
data class PlatformResult(
    val ok: Boolean,
    val code: Long = 0,
    val message: String = "",
    val replyId: String = "",
    val raw: JsonObject? = null
) {
    companion object {
        fun ok(replyId: String = "", message: String = "") = PlatformResult(true, 0, message, replyId)
        fun fail(code: Long, message: String, raw: JsonObject? = null) = PlatformResult(false, code, message, "", raw)
    }
}

@Serializable
enum class Action { REPLY, SKIP, ESCALATE }

@Serializable
data class Decision(
    val action: Action,
    val text: String = "",
    val confidence: Double = 0.0,
    val reason: String = "",
    val source: DecisionSource = DecisionSource.MODEL
)

@Serializable
enum class DecisionSource { MODEL, LOCAL_RULE, GUARDRAIL, USER_OVERRIDE, ERROR }

@Serializable
data class LedgerEntry(
    val commentId: String,
    val targetId: String,
    val action: String,
    val status: LedgerStatus,
    val platformCode: Long = 0,
    val commentContent: String = "",
    val replyContent: String = "",
    val reason: String = "",
    val model: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val latencyMs: Long = 0,
    val error: String = "",
    val atEpochMs: Long = System.currentTimeMillis()
)

@Serializable
enum class LedgerStatus { SENT, SKIPPED, ESCALATED, FAILED, DUP_SKIPPED, FILTERED, COOLDOWN }
