package com.gaibang.replyagent.platform.bilibili

import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.model.PlatformResult
import com.gaibang.replyagent.domain.model.Target
import com.gaibang.replyagent.domain.model.TargetKind
import com.gaibang.replyagent.domain.port.Capability
import com.gaibang.replyagent.domain.port.LoginEvent
import com.gaibang.replyagent.domain.port.LoginSession
import com.gaibang.replyagent.domain.port.PlatformAdapter
import com.gaibang.replyagent.domain.port.SecretStore
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.Json
import com.gaibang.replyagent.util.Qr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * B 站适配。
 * 登录只做两件事：把官方二维码显示给用户、轮询官方接口取回登录态。
 * 代码路径里不存在任何接收密码的入口 —— 这是需求书第五章的硬约束。
 */
class BilibiliAdapter(
    private val secrets: SecretStore,
    private val client: BiliClient = BiliClient()
) : PlatformAdapter {

    override val id = "bilibili"
    override val displayName = "哔哩哔哩"
    override val homepage = "https://www.bilibili.com"
    override val capabilities = setOf(
        Capability.LOGIN_QR, Capability.LOGIN_COOKIE_IMPORT, Capability.FETCH_COMMENTS,
        Capability.POST_REPLY, Capability.RESOLVE_TARGET, Capability.UPLOAD_IMAGE,
        Capability.LIST_REPLIES
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cookieRef = "cookie.bilibili.active"
    @Volatile private var restored = false

    override suspend fun loginStatus(): LoginStatus {
        ensureRestored()
        return runCatching {
            val nav = client.nav()
            if (nav.loggedIn) {
                persistCookies()
                LoginStatus.LoggedIn(nav.uid, nav.name.ifBlank { "UID ${nav.uid}" }, avatar = nav.avatar)
            } else {
                if (secrets.exists(cookieRef)) LoginStatus.Expired(nav.uid) else LoginStatus.NotLoggedIn
            }
        }.getOrElse {
            AppLogger.w("bili", "nav 失败：${it.message}")
            if (secrets.exists(cookieRef)) LoginStatus.Expired() else LoginStatus.NotLoggedIn
        }
    }

    private fun ensureRestored() {
        if (restored) return
        restored = true
        runCatching {
            val raw = secrets.get(cookieRef) ?: return
            val map = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), String(raw))
            if (map.isNotEmpty()) {
                client.loadCookies(map)
                AppLogger.i("bili", "已恢复登录态（${map.size} 个 Cookie，保护级别：${secrets.protectionDescription}）")
            }
        }.onFailure { AppLogger.e("bili", "恢复登录态失败", it) }
    }

    private fun persistCookies() {
        val map = client.cookieSnapshot()
        if (map.isEmpty()) return
        secrets.put(cookieRef, Json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()), map
        ).toByteArray())
    }

    override suspend fun startLogin(): LoginSession {
        val events = Channel<LoginEvent>(Channel.BUFFERED)
        val job: Job = scope.launch { qrFlow(events) }
        return LoginSession(events) { job.cancel() }
    }

    private suspend fun qrFlow(events: Channel<LoginEvent>) {
        val ticket = runCatching { client.requestQrTicket() }.getOrElse {
            events.send(LoginEvent.Failed("拿不到登录二维码", "${it.message}"))
            events.close()
            return
        }
        val rendered = runCatching { Qr.render(ticket.url, 260) }.getOrElse {
            events.send(LoginEvent.Failed("本地生成二维码失败", it.message ?: ""))
            events.close()
            return
        }
        events.send(LoginEvent.QrReady(ticket.url, rendered.argb, rendered.size))
        AppLogger.i("bili", "已显示扫码登录二维码，等待用户在手机 B 站 App 上确认")

        val deadline = System.currentTimeMillis() + 180_000
        var waited = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(waited.coerceAtMost(3_000))
            waited += 1_500
            val r = try {
                client.pollQr(ticket.key)
            } catch (e: Exception) {
                AppLogger.d("bili", "轮询失败，继续重试：${e.message}")
                continue
            }
            when (r.code) {
                BiliCodes.OK -> {
                    val nav = try {
                        client.nav()
                    } catch (e: Exception) {
                        events.send(LoginEvent.Failed("扫码成功但校验登录态失败", e.message ?: ""))
                        break
                    }
                    if (!nav.loggedIn) {
                        events.send(LoginEvent.Failed("扫码成功但 Cookie 未生效", "可能被风控，试试手动粘贴 Cookie"))
                        break
                    }
                    persistCookies()
                    AppLogger.i("bili", "登录成功：${nav.name}（${nav.uid}）")
                    events.send(LoginEvent.Success(nav.uid, nav.name, nav.avatar))
                    break
                }

                BiliCodes.QR_SCANNED_WAIT -> events.send(LoginEvent.ScannedConfirming)
                BiliCodes.QR_NOT_SCANNED -> events.send(LoginEvent.Waiting)
                BiliCodes.QR_EXPIRED -> {
                    events.send(LoginEvent.Expired("二维码已过期，请重新获取"))
                    break
                }

                else -> {
                    events.send(LoginEvent.Failed("登录未完成（code=${r.code}）", r.message))
                    break
                }
            }
        }
        events.close()
    }

    override suspend fun importCookie(cookieText: String): PlatformResult {
        val map = parseCookieText(cookieText)
        if (map.isEmpty()) return PlatformResult.fail(-1, "没识别出任何 Cookie 键值对，请粘贴完整的 Cookie 字符串")
        client.loadCookies(map)
        val nav = runCatching { client.nav() }
            .getOrElse {
                client.clearCookies()
                return PlatformResult.fail(-1, "校验失败：${it.message}")
            }
        if (!nav.loggedIn) {
            client.clearCookies()
            ensureRestoredNow()
            return PlatformResult.fail(BiliCodes.NOT_LOGGED_IN, "这组 Cookie 不通（B 站说未登录）")
        }
        persistCookies()
        AppLogger.i("bili", "手动导入 Cookie 成功：${nav.name}（${nav.uid}）")
        return PlatformResult.ok(nav.uid, nav.name)
    }

    private fun ensureRestoredNow() {
        restored = false
        ensureRestored()
    }

    private fun parseCookieText(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("""([A-Za-z0-9_]+)=([^;\s,]+)""").findAll(text).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out.filter { it.key in BILI_COOKIE_KEYS || it.key.startsWith("buvid") }
    }

    override suspend fun logout() {
        client.clearCookies()
        secrets.delete(cookieRef)
        restored = true
        AppLogger.i("bili", "已退出登录并删除本地 Cookie")
    }

    /* ── 业务 ── */

    override suspend fun resolveTarget(ref: String): Result<Target> = runCatching {
        ensureRestored()
        val (aid, title) = client.resolveAid(ref)
        val bvid = Regex("(BV[0-9A-Za-z]{10})").find(ref)?.value ?: ""
        Target(
            platformId = id,
            id = bvid.ifBlank { aid },
            title = title,
            numericId = aid,
            url = if (bvid.isNotBlank()) "https://www.bilibili.com/video/$bvid" else "https://www.bilibili.com/video/av$aid"
        )
    }

    override suspend fun fetchComments(target: Target, page: Int, pageSize: Int): Result<List<Comment>> =
        runCatching {
            ensureRestored()
            client.fetchTopLevel(target.numericId.ifBlank { target.id }, page, mode = 2).first
        }

    override suspend fun childReplies(target: Target, commentId: String): Result<List<Comment>> =
        runCatching {
            ensureRestored()
            client.childReplies(target.numericId.ifBlank { target.id }, commentId)
        }

    override suspend fun postReply(
        target: Target,
        parentCommentId: String,
        text: String,
        imageDataUrl: String?
    ): Result<PlatformResult> = runCatching {
        ensureRestored()
        val images = if (!imageDataUrl.isNullOrBlank()) {
            val bytes = runCatching {
                Base64.getDecoder().decode(imageDataUrl.substringAfter(","))
            }.getOrElse { return@runCatching PlatformResult.fail(-1, "附图数据无法解码：${it.message}") }
            val aid = target.numericId.ifBlank { target.id }
            val uploaded = client.uploadImage(aid, bytes, "reply.png").getOrElse {
                return@runCatching PlatformResult.fail(-1, "附图上传失败：$it")
            }
            listOf(uploaded)
        } else emptyList()
        client.postReply(target.numericId.ifBlank { target.id }, parentCommentId, text, images)
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        private val BILI_COOKIE_KEYS = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut"
        )

        /** 供 Agent 的只读工具展示：这条评论的公开链接 */
        fun commentUrl(target: Target, commentId: String): String =
            "${target.url}?replyId=${commentId}&jump_from=web_aid"

        fun parseBvid(raw: String): String? = Regex("(BV[0-9A-Za-z]{10})").find(raw)?.value

        internal fun debugJson(o: JsonObject): String = Json.encodeToString(JsonObject.serializer(), o)
        internal fun debugPrim(v: String): JsonPrimitive = JsonPrimitive(v)
    }
}
