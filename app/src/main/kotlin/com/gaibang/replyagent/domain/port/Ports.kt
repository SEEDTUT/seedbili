package com.gaibang.replyagent.domain.port

import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.model.PlatformResult
import com.gaibang.replyagent.domain.model.Target
import kotlinx.coroutines.channels.Channel

/* ────────────── 平台适配层端口 ────────────── */

enum class Capability {
    LOGIN_QR, LOGIN_COOKIE_IMPORT, FETCH_COMMENTS, POST_REPLY, RESOLVE_TARGET, UPLOAD_IMAGE,
    /** 能否按需取某条顶层评论的子回复（用来判断「是否已回过」） */
    LIST_REPLIES
}

/** 登录过程事件。UI 订阅后渲染二维码/进度。 */
sealed class LoginEvent {
    data class QrReady(val url: String, val pngRgba: IntArray, val size: Int) : LoginEvent()
    data object Waiting : LoginEvent()
    data object ScannedConfirming : LoginEvent()
    data class Success(val uid: String, val name: String, val avatar: String = "") : LoginEvent()
    data class Expired(val message: String) : LoginEvent()
    data class Failed(val message: String, val hint: String = "") : LoginEvent()
}

class LoginSession(val events: Channel<LoginEvent>, val cancel: () -> Unit)

/**
 * 平台适配器：只做协议翻译，不写台账、不限速、不碰 UI。
 * 新增平台 = 实现本接口 + 注册为服务，业务代码零改动。
 */
interface PlatformAdapter {
    val id: String
    val displayName: String
    val homepage: String
    val capabilities: Set<Capability>

    suspend fun loginStatus(): LoginStatus
    /** 唤起登录（扫码）。工具与 Agent 全程不接触密码。 */
    suspend fun startLogin(): LoginSession
    /** 兜底：用户自行从浏览器导出的 cookie 文本 */
    suspend fun importCookie(cookieText: String): PlatformResult
    suspend fun logout()

    suspend fun resolveTarget(ref: String): Result<Target>
    suspend fun fetchComments(target: Target, page: Int, pageSize: Int): Result<List<Comment>>

    /** 某条顶层评论的子回复。平台不支持时返回 Failure，引擎会退化成「只看抓取时带的线索」 */
    suspend fun childReplies(target: Target, commentId: String): Result<List<Comment>> =
        Result.failure(UnsupportedOperationException("${id} 不支持读取子回复"))

    /** orequid: 顶层评论 rpid；parentId 为空表示回复主评论 */
    suspend fun postReply(target: Target, parentCommentId: String, text: String, imageDataUrl: String? = null): Result<PlatformResult>

    fun close() {}
}

/* ────────────── 敏感存储端口 ────────────── */

interface SecretStore {
    /** 人类可读的保护级别说明，直接显示在设置页 */
    val protectionDescription: String
    val strongProtection: Boolean

    fun put(ref: String, value: ByteArray)
    fun get(ref: String): ByteArray?
    fun delete(ref: String)
    fun exists(ref: String): Boolean
    fun refs(): List<String>
}

/* ────────────── 其它端口 ────────────── */

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
