package com.gaibang.replyagent.platform.bilibili

import com.gaibang.replyagent.domain.model.Comment
import com.gaibang.replyagent.domain.model.PlatformResult
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.HttpClientX
import com.gaibang.replyagent.util.Json
import com.gaibang.replyagent.util.arr
import com.gaibang.replyagent.util.bool
import com.gaibang.replyagent.util.num
import com.gaibang.replyagent.util.obj
import com.gaibang.replyagent.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.Duration

/** B 站风控/业务返回码。表里没列的原样透传，不猜含义。 */
object BiliCodes {
    const val OK = 0L
    const val NOT_LOGGED_IN = -101L
    const val FLOOD_CONTROL = -509L
    const val NO_PERMISSION = -403L
    const val PARAM_ERROR = -400L
    const val AUDIT_PENDING = 10008L
    const val SEND_LIMIT = 12014L
    const val DUPLICATE_COMMENT = 12051L
    const val RISK_CONTROL = 12052L
    const val RECALL_BY_UP = 12053L
    const val QR_EXPIRED = 86038L
    const val QR_SCANNED_WAIT = 86090L
    const val QR_NOT_SCANNED = 86101L

    fun describe(code: Long, message: String = ""): String = when (code) {
        OK -> "成功"
        NOT_LOGGED_IN -> "登录态失效（-101），需要重新登录"
        FLOOD_CONTROL -> "请求过于频繁，被风控（-509），本轮已暂停"
        NO_PERMISSION -> "没有权限（-403），通常是签名过期或Cookie不完整"
        PARAM_ERROR -> "参数不合法（-400）"
        SEND_LIMIT -> "今日发送量达到上限（12014），明天再来或调低配额"
        DUPLICATE_COMMENT -> "内容重复，B 站拒绝（12051）"
        RISK_CONTROL -> "触发风控（12052），建议放慢节奏"
        RECALL_BY_UP -> "评论被 UP 主删除（12053）"
        AUDIT_PENDING -> "内容待审核（10008）"
        else -> message.ifBlank { "B 站返回 code=$code" }
    }

    /** 需要冷却的码 */
    fun shouldCooldown(code: Long) = code == SEND_LIMIT || code == FLOOD_CONTROL || code == RISK_CONTROL
    fun isFatalForRun(code: Long) = code == NOT_LOGGED_IN
}

data class NavInfo(val loggedIn: Boolean, val uid: String, val name: String, val vip: Boolean = false, val avatar: String = "")

data class QrTicket(val url: String, val key: String)

data class PollResult(val code: Long, val message: String, val redirectUrl: String = "")

class BiliClient(private val http: HttpClientX = HttpClientX()) {

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
    private val baseHeaders = mapOf(
        "User-Agent" to ua,
        "Referer" to "https://www.bilibili.com/",
        "Origin" to "https://www.bilibili.com",
        "sec-ch-ua" to "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    @Volatile private var mixinKey: String = ""
    @Volatile private var keysFetchedAt: Long = 0
    @Volatile var csrf: String = ""
        private set
    @Volatile private var cachedUid: String = ""

    val cookieHeader: String get() = http.cookieHeader()

    fun loadCookies(map: Map<String, String>) {
        http.clearCookies()
        map.forEach { (k, v) -> http.setCookie(k, v) }
        csrf = map["bili_jct"] ?: ""
        cachedUid = map["DedeUserID"] ?: ""
        keysFetchedAt = 0
    }

    fun cookieSnapshot(): Map<String, String> = http.cookieSnapshot()
    fun clearCookies() {
        http.clearCookies()
        csrf = ""
        cachedUid = ""
    }

    /* ── WBI key ── */

    private suspend fun ensureKeys() {
        if (mixinKey.isNotBlank() && System.currentTimeMillis() - keysFetchedAt < 12 * 3600_000L) return
        val res = http.get("https://api.bilibili.com/x/web-interface/nav", baseHeaders)
        val data = res.asJsonObject()?.obj("data")
        val img = data?.obj("wbi_img")?.str("img_url").orEmpty()
        val sub = data?.obj("wbi_img")?.str("sub_url").orEmpty()
        if (img.isBlank() || sub.isBlank()) {
            AppLogger.w("bili", "拿不到 wbi_img，签名相关接口可能失败（HTTP ${res.status}）")
            return
        }
        mixinKey = Wbi.mixinKey(Wbi.keyFromUrl(img), Wbi.keyFromUrl(sub))
        keysFetchedAt = System.currentTimeMillis()
        AppLogger.d("bili", "wbi mixinKey 已刷新")
    }

    private suspend fun signed(params: Map<String, String>): Map<String, String> {
        ensureKeys()
        return Wbi.sign(params, mixinKey)
    }

    private fun com.gaibang.replyagent.util.HttpResult.asJsonObject(): JsonObject? =
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun envelope(res: com.gaibang.replyagent.util.HttpResult, what: String): JsonObject {
        val obj = res.asJsonObject()
            ?: throw BiliException(res.status, "接口返回不是 JSON：${res.body.take(160)}", what)
        val code = obj.num("code")
        if (code != BiliCodes.OK) {
            val msg = obj.str("message")
            throw BiliException(code.toInt(), "$what：${BiliCodes.describe(code, msg)}", what, code, msg)
        }
        return obj
    }

    /* ── 登录 ── */

    suspend fun requestQrTicket(): QrTicket = withContext(Dispatchers.IO) {
        val res = http.get(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header",
            baseHeaders
        )
        val data = envelope(res, "生成登录二维码").obj("data")
            ?: throw BiliException(-1, "二维码返回里没有 data", "生成登录二维码")
        QrTicket(url = data.str("url"), key = data.str("qrcode_key"))
    }

    suspend fun pollQr(key: String): PollResult = withContext(Dispatchers.IO) {
        val res = http.get(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?" +
                    "qrcode_key=${enc(key)}&source=main-fe-header",
            baseHeaders
        )
        val obj = res.asJsonObject() ?: throw BiliException(-1, "轮询返回不是 JSON", "登录轮询")
        val data = obj.obj("data")
        PollResult(
            code = data?.num("code").takeIf { it != 0L } ?: obj.num("code"),
            message = data?.str("message").orEmpty().ifBlank { obj.str("message") },
            redirectUrl = data?.str("url").orEmpty()
        )
    }

    /** 登录成功后必须调一次：确认 cookie 真的可用，并拿到 uid/昵称 */
    suspend fun nav(): NavInfo = withContext(Dispatchers.IO) {
        val res = http.get("https://api.bilibili.com/x/web-interface/nav", baseHeaders)
        val obj = res.asJsonObject() ?: throw BiliException(res.status, "nav 返回不是 JSON", "查询登录状态")
        val data = obj.obj("data") ?: JsonObject(emptyMap())
        val loggedIn = data.bool("isLogin")
        if (loggedIn) {
            cachedUid = data.num("mid").toString()
            csrf = http.cookieSnapshot()["bili_jct"] ?: csrf
        }
        NavInfo(
            loggedIn = loggedIn,
            uid = data.num("mid").takeIf { loggedIn }?.toString() ?: cachedUid,
            name = data.str("uname"),
            vip = data.obj("vip")?.num("type") != 0L,
            avatar = data.str("face")
        )
    }

    /* ── 投稿与评论 ── */

    suspend fun resolveAid(ref: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val cleaned = ref.trim()
        val bvid = Regex("(BV[0-9A-Za-z]{10})").find(cleaned)?.value
            ?: cleaned.takeIf { it.startsWith("BV") && it.length == 12 }
        val params = if (bvid != null) mapOf("bvid" to bvid)
        else mapOf("aid" to cleaned.filter { it.isDigit() }.ifBlank { "0" })
        val q = signed(params + mapOf("web_location" to "1550305"))
        val res = http.get("https://api.bilibili.com/x/web-interface/view?" + query(q), baseHeaders + mapOf(
            "Referer" to (bvid?.let { "https://www.bilibili.com/video/$it/" } ?: "https://www.bilibili.com/")
        ))
        val data = envelope(res, "解析投稿").obj("data")
            ?: throw BiliException(-1, "解析投稿失败：没有 data", "解析投稿")
        data.num("aid").toString() to data.str("title")
    }

    suspend fun fetchTopLevel(aid: String, page: Int, mode: Int = 2): Pair<List<Comment>, Int> =
        withContext(Dispatchers.IO) {
            val params = mapOf(
                "type" to "1",
                "oid" to aid,
                "mode" to mode.toString(),
                "next" to page.toString(),
                "ps" to "20",
                "web_location" to "333.976",
                "mobi_app" to "web"
            )
            val res = http.get("https://api.bilibili.com/x/v2/reply/wbi/main?" + query(signed(params)), baseHeaders)
            val data = envelope(res, "抓取评论").obj("data") ?: return@withContext emptyList<Comment>() to page
            val cursor = data.obj("cursor")
            val next = (cursor?.num("next") ?: (page + 1).toLong()).toInt()
            val list = data.arr("replies").mapNotNull { parseComment(it as? JsonObject, aid) }
            AppLogger.d("bili", "第 $page 页顶层评论 ${list.size} 条，下一页 $next，isEnd=${cursor?.bool("is_end")}")
            list to next
        }

    /** 用于判断「我和主 UP 是否回过」——只在候选上按需调用 */
    suspend fun childReplies(aid: String, rootRpid: String, maxPages: Int = 3): List<Comment> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Comment>()
            for (p in 1..maxPages) {
                val params = mapOf(
                    "type" to "1", "oid" to aid, "root" to rootRpid,
                    "ps" to "20", "pn" to p.toString(), "web_location" to "1310.125"
                )
                val res = runCatching {
                    http.get("https://api.bilibili.com/x/v2/reply/reply?" + query(signed(params)), baseHeaders)
                }.getOrElse { break }
                val data = res.asJsonObject()?.obj("data") ?: break
                val replies = data.arr("replies")
                if (replies.isEmpty()) break
                out += replies.mapNotNull { parseComment(it as? JsonObject, aid) }
                if (data.num("page").toInt() * 20 >= data.num("count").toInt()) break
            }
            out
        }

    private fun parseComment(o: JsonObject?, aid: String): Comment? {
        if (o == null) return null
        val content = o.obj("content")
        val member = o.obj("member")
        val rpid = o.num("rpid").toString()
        if (rpid == "0") return null
        return Comment(
            id = rpid,
            targetId = aid,
            parentId = o.num("parent").toString().takeIf { it != "0" } ?: "",
            rootId = o.num("root").toString().takeIf { it != "0" } ?: "",
            authorUid = member?.num("mid")?.toString() ?: o.num("mid").toString(),
            authorName = member?.str("uname").orEmpty().ifBlank { member?.str("name") ?: "" },
            content = content?.str("message").orEmpty(),
            likes = o.num("like").toInt(),
            createdAtEpochSec = o.num("ctime"),
            answeredBySelf = false,
            answeredByMainUp = false,
            raw = o
        )
    }

    /* ── 发送 ── */

    suspend fun postReply(
        aid: String,
        parentRpid: String,
        message: String,
        imageUrls: List<String> = emptyList()
    ): PlatformResult = withContext(Dispatchers.IO) {
        if (csrf.isBlank()) {
            return@withContext PlatformResult.fail(-101, "缺少 bili_jct（登录态不完整），请重新登录")
        }
        val params = linkedMapOf(
            "oid" to aid,
            "type" to "1",
            "message" to message,
            "plat" to "1",
            "sensitive_rule_check" to "0",
            "csrf" to csrf
        )
        if (parentRpid.isNotBlank() && parentRpid != "0") {
            params["parent"] = parentRpid
            params["root"] = parentRpid
        }
        if (imageUrls.isNotEmpty()) params["images"] = imageUrls.joinToString(",")

        val signedParams = signed(params)
        val res = try {
            http.postForm("https://api.bilibili.com/x/v2/reply/add", signedParams, baseHeaders, Duration.ofSeconds(25))
        } catch (e: Exception) {
            AppLogger.e("bili", "发送评论请求异常", e)
            return@withContext PlatformResult.fail(-1, "网络异常：${e.message}")
        }
        val obj = res.asJsonObject()
            ?: return@withContext PlatformResult.fail(res.status.toLong(), "发送返回不是 JSON：${res.body.take(160)}")
        val code = obj.num("code")
        val data = obj.obj("data")
        val replyId = data?.num("rpid")?.toString() ?: ""
        AppLogger.i("bili", "发送结果 code=$code ${BiliCodes.describe(code, obj.str("message"))}")
        if (code == BiliCodes.OK) {
            PlatformResult.ok(replyId, data?.str("message") ?: obj.str("message"))
        } else {
            PlatformResult.fail(code, BiliCodes.describe(code, obj.str("message")), obj)
        }
    }

    /** 评论附图上传。开关默认关闭，但能力是真的。 */
    suspend fun uploadImage(aid: String, bytes: ByteArray, fileName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val boundary = "----ReplyAgent${System.nanoTime()}x"
                val body = buildMultipart(boundary, aid, bytes, fileName)
                val url = "https://api.bilibili.com/x/v2/reply/upload/img?type=1&oid=$aid&bvid="
                val res = http.postBinary(url, body, boundary, baseHeaders + mapOf(
                    "x-rpc-device_id" to ""
                ).filterValues { it.isNotBlank() })
                val obj = res.asJsonObject() ?: error("图片上传返回不是 JSON")
                if (obj.num("code") != BiliCodes.OK) error(BiliCodes.describe(obj.num("code"), obj.str("message")))
                obj.obj("data")?.arr("image_urls")?.firstOrNull()
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: error("上传成功但没有返回图片地址")
            }
        }

    private fun buildMultipart(boundary: String, oid: String, bytes: ByteArray, fileName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\n1\r\n")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"oid\"\r\n\r\n$oid\r\n")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"bvid\"\r\n\r\n\r\n")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n$csrf\r\n")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"upfile\"; filename=\"$fileName\"\r\n" +
                "Content-Type: image/png\r\n\r\n")
        out.write(bytes)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

class BiliException(
    statusOrCode: Int,
    message: String,
    val where: String,
    val biliCode: Long = statusOrCode.toLong(),
    val rawMessage: String = ""
) : Exception(message)
