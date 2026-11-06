package com.gaibang.replyagent.domain.model

import kotlinx.serialization.Serializable

/* ────────────── 模型 API 配置 ────────────── */

@Serializable
data class ApiProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.7,
    val maxTokens: Int = 800,
    val topP: Double? = null,
    val timeoutSeconds: Int = 60,
    val stream: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Key 不进配置，只留密文句柄 */
    val keyRef: String = "apikey.$id",
    val keyFingerprint: String = "(未设置)"
) {
    fun chatUrl(): String {
        val b = baseUrl.trim().removeSuffix("/")
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    companion object {
        const val DEFAULT_ID = "default"
        fun default() = ApiProfile(id = DEFAULT_ID, name = "默认配置")
    }
}

/* ────────────── 过滤规则引擎 ────────────── */

@Serializable
enum class RuleField { AUTHOR_UID, AUTHOR_NAME, CONTENT, IS_TOP_LEVEL, LIKE_COUNT, PARENT_ID }

@Serializable
enum class RuleOp { EQ, NE, CONTAINS, REGEX, IN, NOT_IN, GT, LT }

@Serializable
enum class RuleAction { SKIP, ESCALATE, FORCE_REPLY }

@Serializable
data class CustomRule(
    val id: String,
    val name: String = "",
    val field: RuleField = RuleField.CONTENT,
    val op: RuleOp = RuleOp.CONTAINS,
    val value: String = "",
    val action: RuleAction = RuleAction.SKIP,
    val enabled: Boolean = true,
    val note: String = ""
)

@Serializable
data class ReplyScope(
    val topLevelOnly: Boolean = true,
    val skipAlreadyAnswered: Boolean = true,
    val minContentLength: Int = 2,
    val maxContentLength: Int = 500,
    val onlyIfContains: List<String> = emptyList(),
    val neverIfContains: List<String> = emptyList(),
    val customRules: List<CustomRule> = emptyList()
)

/** 身份条目：自己 / 主 UP / 黑名单。名字与 UID 都留着，UID 优先精确匹配。 */
@Serializable
data class Identity(
    val id: String,
    val kind: IdentityKind,
    val uid: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val note: String = ""
)

@Serializable
enum class IdentityKind { SELF, MAIN_UP, BLACKLIST }

@Serializable
data class RateLimit(
    val minIntervalMs: Long = 20_000,
    val jitterMs: Long = 25_000,
    val perRunCap: Int = 10,
    val perDayCap: Int = 20,
    val cooldownOnLimitMs: Long = 600_000,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 60_000,
    val maxConsecutiveFailures: Int = 3
)

@Serializable
data class LedgerPolicy(
    val retentionDays: Int = 180,
    val recheckBeforeSend: Boolean = true,
    /** 台账主键形态：整条评论 id；或 内容指纹（防 B 站折叠后 id 变化） */
    val dedupeBy: DedupeKey = DedupeKey.COMMENT_ID
)

@Serializable
enum class DedupeKey { COMMENT_ID, CONTENT_FINGERPRINT }

@Serializable
enum class FallbackMode { REPORT_ONLY, AUTO_REPLY, SKIP }

@Serializable
data class StrategyProfile(
    val id: String,
    val name: String,
    val promptId: String = PromptProfile.DEFAULT_ID,
    val scope: ReplyScope = ReplyScope(),
    val identities: List<Identity> = StrategyDefaults.identities(),
    val rate: RateLimit = RateLimit(),
    val ledger: LedgerPolicy = LedgerPolicy(),
    val fallback: FallbackMode = FallbackMode.REPORT_ONLY,
    val minConfidenceForAutoReply: Double = 0.6,
    val allowImageReply: Boolean = false,
    val maxReplyChars: Int = 140,
    val forbiddenReplySubstrings: List<String> = emptyList(),
    val allowedLinkHosts: List<String> = emptyList(),
    val extraNotesForPrompt: String = ""
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

/* ────────────── 提示词 ────────────── */

@Serializable
data class PromptExample(val input: String, val output: String)

@Serializable
data class PromptProfile(
    val id: String,
    val name: String,
    val persona: String = "你是一个 B 站 UP 主的评论区助理，替主 UP 回复观众提问。",
    val boundaries: String = "不承诺具体时间与收益；不讨论账号交易、刷量、绕过平台规则；不透露隐私；涉及争议话题一律转人工。",
    val style: String = "口语化、简短、友善，不用敬语堆砌，不强行玩梗。",
    val examples: List<PromptExample> = emptyList(),
    val version: Int = 1,
    val updatedAt: String = ""
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

/* ────────────── 沙箱 ────────────── */

@Serializable
enum class SandboxBackend { NONE, WSL, LOCAL }

@Serializable
data class SandboxSettings(
    val backend: SandboxBackend = SandboxBackend.NONE,
    val distro: String = "",
    val allowRunCommand: Boolean = false,
    val confirmEachCommand: Boolean = true,
    val commandTimeoutSeconds: Int = 60
)

@Serializable
data class UiSettings(
    val theme: String = "system", // system | light | dark
    val verboseLogging: Boolean = false,
    /** 首次使用引导是否走完；false 时启动先放片头动画再进向导 */
    val onboarded: Boolean = false
)

@Serializable
data class AccountSettings(
    val platformId: String = "bilibili",
    val activeUid: String = ""
)

/* ────────────── 根配置 ────────────── */

@Serializable
data class AppConfig(
    val schemaVersion: Int = CURRENT_VERSION,
    val apiProfiles: List<ApiProfile> = listOf(ApiProfile.default()),
    val activeApiProfileId: String = ApiProfile.DEFAULT_ID,
    val strategies: List<StrategyProfile> = listOf(StrategyDefaults.strategy()),
    val activeStrategyId: String = StrategyProfile.DEFAULT_ID,
    val prompts: List<PromptProfile> = listOf(StrategyDefaults.prompt()),
    val activePromptId: String = PromptProfile.DEFAULT_ID,
    val sandbox: SandboxSettings = SandboxSettings(),
    val ui: UiSettings = UiSettings(),
    val account: AccountSettings = AccountSettings(),
    val targets: List<String> = emptyList(),
    val recentTargets: List<String> = emptyList()
) {
    fun apiProfile(): ApiProfile = apiProfiles.firstOrNull { it.id == activeApiProfileId } ?: apiProfiles.first()
    fun strategy(): StrategyProfile = strategies.firstOrNull { it.id == activeStrategyId } ?: strategies.first()
    fun prompt(): PromptProfile = prompts.firstOrNull { it.id == activePromptId } ?: prompts.firstOrNull() ?: PromptProfile(DEFAULT_PROMPT_ID, "内置默认")

    companion object {
        const val CURRENT_VERSION = 1
        private const val DEFAULT_PROMPT_ID = "default"
    }
}

/** 出厂默认值——全部可在界面编辑，代码里不写死具体人名与阈值（需求书第七章） */
object StrategyDefaults {
    fun identity(id: String, kind: IdentityKind, name: String, note: String = "") =
        Identity(id = id, kind = kind, name = name, enabled = true, note = note)

    fun identities() = listOf(
        identity("id-self", IdentityKind.SELF, "我自己（登录中的账号）", "运行时自动用当前登录 UID 比对，名称仅备注"),
        identity("id-mainup", IdentityKind.MAIN_UP, "主 UP", "占位名，请在设置里改成你的实际昵称或 UID"),
        identity("id-blk-1", IdentityKind.BLACKLIST, "小猫咪的小烦恼", "示例黑名单项，抓取阶段与发送前各校验一次")
    )

    fun prompt() = PromptProfile(
        id = PromptProfile.DEFAULT_ID,
        name = "内置默认",
        updatedAt = java.time.LocalDateTime.now().toString()
    )

    fun strategy() = StrategyProfile(
        id = StrategyProfile.DEFAULT_ID,
        name = "出厂默认",
        identities = identities()
    )
}
