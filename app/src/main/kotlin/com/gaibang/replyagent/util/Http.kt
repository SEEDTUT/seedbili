package com.gaibang.replyagent.util

import com.gaibang.replyagent.llm.ApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLException

data class HttpResult(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap()
) {
    fun asJson(parser: Json = Json): JsonObject? = runCatching {
        parser.parseToJsonElement(body.takeLast(2_000_000)) as? JsonObject
    }.getOrNull()

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/**
 * 极简 JSON/表单 HTTP 客户端，基于 JDK java.net.http。
 * 自己带 cookie jar（B 站扫码流程需要）、自己处理 gzip、自己把异常归类成 ApiError。
 */
class HttpClientX(private val connectTimeout: Duration = Duration.ofSeconds(15)) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cookies = LinkedHashMap<String, String>()

    fun setCookie(name: String, value: String) {
        if (name.isBlank() || value.isBlank()) return
        cookies[name] = value
    }

    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    fun clearCookies() = cookies.clear()
    fun cookieSnapshot(): Map<String, String> = LinkedHashMap(cookies)

    private fun buildCookie(req: HttpRequest.Builder): HttpRequest.Builder {
        val jar = cookieHeader()
        return if (jar.isBlank()) req else req.header("Cookie", jar)
    }

    private fun absorbSetCookie(res: HttpResponse<ByteArray>) {
        res.headers().allValues("set-cookie").forEach { raw ->
            val pair = raw.split(";").firstOrNull() ?: return@forEach
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val n = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim()
                if (n.isNotBlank() && v.isNotBlank()) cookies[n] = v
            }
        }
    }

    private fun send(req: HttpRequest.Builder, timeout: Duration): HttpResult {
        val built = buildCookie(req).timeout(timeout).build()
        val res = client.send(built, HttpResponse.BodyHandlers.ofByteArray())
        absorbSetCookie(res)
        val decoded = decode(res.body(), res.headers().firstValue("content-encoding").orElse(""))
        return HttpResult(res.statusCode(), decoded, res.headers().map())
    }

    private fun decode(bytes: ByteArray, encoding: String): String {
        val stream = when {
            encoding.contains("gzip", true) -> GZIPInputStream(ByteArrayInputStream(bytes))
            encoding.contains("deflate", true) -> InflaterInputStream(ByteArrayInputStream(bytes))
            else -> return String(bytes, Charsets.UTF_8)
        }
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofSeconds(20)): HttpResult {
        val b = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", "application/json, text/plain, */*")
            .header("Accept-Encoding", "gzip, deflate")
        headers.forEach { (k, v) -> b.header(k, v) }
        return classified("GET $url") { send(b, timeout) }
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(20)
    ): HttpResult {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Encoding", "gzip, deflate")
        headers.forEach { (k, v) -> b.header(k, v) }
        return classified("POST $url") { send(b, timeout) }
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(60),
        accept: String = "application/json"
    ): HttpResult {
        val b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(jsonBody, Charsets.UTF_8))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", accept)
            .header("Accept-Encoding", "gzip, deflate")
        headers.forEach { (k, v) -> b.header(k, v) }
        return classified("POST $url") { send(b, timeout) }
    }

    /** multipart 上传用（评论附图）；body 由调用方拼好 */
    fun postBinary(
        url: String,
        body: ByteArray,
        boundary: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(30)
    ): HttpResult {
        val b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> if (!k.equals("Content-Type", true)) b.header(k, v) }
        return classified("POST(binary) $url") { send(b, timeout) }
    }

    /**
     * SSE 流式读取。onDelta 收到每个 data: 行里的 JSON；[DONE] 自动跳过。
     */
    fun postJsonStream(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        timeout: Duration,
        onDelta: (String) -> Boolean
    ): Result<Unit> = runCatching {
        val b = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, Charsets.UTF_8))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
        headers.forEach { (k, v) -> b.header(k, v) }
        val res = client.send(buildCookie(b).timeout(timeout).build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() !in 200..299) {
            val msg = res.body().use { String(it.readAllBytes(), Charsets.UTF_8) }
            error("HTTP ${res.statusCode()}: ${msg.take(400)}")
        }
        res.body().bufferedReader(Charsets.UTF_8).use { reader ->
            var dataLine = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (dataLine.isNotEmpty()) {
                        val payload = dataLine.toString()
                        dataLine = StringBuilder()
                        if (payload.trim() == "[DONE]") break
                        if (!onDelta(payload)) break
                    }
                    continue
                }
                when {
                    line.startsWith("data:") -> dataLine.append(line.removePrefix("data:").trim())
                    line.startsWith(":") -> {}
                    else -> {}
                }
            }
            if (dataLine.isNotEmpty()) {
                val payload = dataLine.toString()
                if (payload.trim() != "[DONE]") onDelta(payload)
            }
        }
        Unit
    }

    private inline fun classified(what: String, block: () -> HttpResult): HttpResult =
        try {
            block()
        } catch (e: Exception) {
            throw classify(e, what)
        }

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        /** 把底层异常翻译成能给人看的分类 */
        fun classify(e: Throwable, what: String = ""): ApiError {
            var cur: Throwable? = e
            while (cur != null) {
                when (cur) {
                    is UnknownHostException -> return ApiError.Dns(cur.message ?: "未知主机")
                    is java.net.ConnectException -> return ApiError.Connect(cur.message ?: "", e)
                    is SSLException -> return ApiError.Tls(cur.message ?: "")
                    is java.net.http.HttpTimeoutException -> return ApiError.Timeout(cur.message ?: "")
                    is java.net.SocketTimeoutException -> return ApiError.Timeout(cur.message ?: "")
                    else -> {}
                }
                cur = cur.cause
            }
            return ApiError.Unknown("${e.javaClass.simpleName}: ${e.message} $what".trim(), e)
        }

        /** 按 HTTP 状态码分类 */
        fun fromStatus(status: Int, bodySnippet: String): ApiError = when (status) {
            401, 403 -> ApiError.Auth(status)
            404, 422 -> ApiError.Model(status, bodySnippet.take(200))
            429 -> ApiError.Quota(status, bodySnippet.take(200))
            in 500..599 -> ApiError.Server(status, bodySnippet.take(200))
            else -> ApiError.Unknown("HTTP $status", null)
        }

        const val RAW_UNAVAILABLE = -1
    }
}

/** 小工具：把 JsonObject 里某个 key 读成字符串 */
fun JsonObject.str(key: String): String = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
fun JsonObject.num(key: String): Long = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0
fun JsonObject.bool(key: String): Boolean = when (val v = this[key]) {
    is kotlinx.serialization.json.JsonPrimitive -> v.content == "true" || v.content == "1"
    else -> false
}
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
