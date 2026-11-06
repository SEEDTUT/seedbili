package com.gaibang.replyagent.llm

import com.gaibang.replyagent.domain.model.ApiProfile
import com.gaibang.replyagent.util.HttpClientX
import com.gaibang.replyagent.util.HttpResult
import com.gaibang.replyagent.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import java.time.Duration

data class ChatOutcome(
    val response: ChatResponse,
    val latencyMs: Long,
    val requestBodyChars: Int
) {
    val tokensIn get() = response.usage?.promptTokens ?: 0
    val tokensOut get() = response.usage?.completionTokens ?: 0
}

private class ToolCallAcc {
    var id = ""
    var name = ""
    val args = StringBuilder()
}

class OpenAiClient(
    private val profile: ApiProfile,
    private val apiKey: String,
    private val http: HttpClientX = HttpClientX()
) {

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        if (apiKey.isNotBlank()) h["Authorization"] = "Bearer $apiKey"
        h["User-Agent"] = "ReplyAgent/1.0"
        profile.extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) h[k] = v }
        return h
    }

    private val timeout: Duration = Duration.ofSeconds(profile.timeoutSeconds.coerceIn(5, 600).toLong())

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        toolChoice: String? = null
    ): ChatOutcome {
        val req = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens.takeIf { it > 0 },
            topP = profile.topP,
            tools = tools.ifEmpty { null },
            toolChoice = toolChoice?.takeIf { tools.isNotEmpty() },
            stream = false
        )
        val body = Json.encodeToString(ChatRequest.serializer(), req)
        val started = System.currentTimeMillis()
        val res = postJson(body)
        val latency = System.currentTimeMillis() - started
        val parsed = runCatching {
            Json.decodeFromString(ChatResponse.serializer(), res.body)
        }.getOrElse {
            throw ApiError.BadShape("HTTP ${res.status}, body=${res.body.take(300).replace("\n", "\\n")}")
        }
        if (parsed.choices.isEmpty()) {
            throw ApiError.BadShape("返回里没有 choices：${res.body.take(300)}")
        }
        return ChatOutcome(parsed, latency, body.length)
    }

    /** 流式：把文本增量吐给回调，同时把 tool_calls 增量拼完整 */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        onText: (String) -> Unit
    ): ChatResponse {
        val req = ChatRequest(
            model = profile.model,
            messages = messages,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens.takeIf { it > 0 },
            topP = profile.topP,
            tools = tools.ifEmpty { null },
            stream = true
        )
        val body = Json.encodeToString(ChatRequest.serializer(), req)
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, ToolCallAcc>()
        val finishReason = arrayOfNulls<String>(1)
        val usageBox = arrayOfNulls<Usage>(1)

        val outcome = withContext(Dispatchers.IO) {
            http.postJsonStream(profile.chatUrl(), body, headers(), timeout) { payload ->
                val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                    ?: return@postJsonStream true
                (obj["usage"] as? JsonObject)?.let { u ->
                    usageBox[0] = Usage(
                        promptTokens = u["prompt_tokens"].asInt(),
                        completionTokens = u["completion_tokens"].asInt()
                    )
                }
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return@postJsonStream true
                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNullSafe()?.let { finishReason[0] = it }
                val delta = choice["delta"] as? JsonObject ?: return@postJsonStream true
                (delta["content"] as? JsonPrimitive)?.contentOrNullSafe()?.let { piece ->
                    text.append(piece)
                    onText(piece)
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { item ->
                    val tc = item as? JsonObject ?: return@forEach
                    val idx = tc["index"].asIntOr(fallback = calls.size)
                    val acc = calls.getOrPut(idx) { ToolCallAcc() }
                    tc["id"].contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        fn["name"].contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                        fn["arguments"].contentOrNullSafe()?.let { acc.args.append(it) }
                    }
                }
                true
            }
        }
        outcome.getOrThrow()

        val toolCalls = calls.entries.sortedBy { it.key }.mapNotNull { (_, acc) ->
            if (acc.name.isBlank() && acc.id.isBlank()) null
            else ToolCall(
                id = acc.id.ifBlank { "call_${acc.name.hashCode()}_${acc.args.length}" },
                function = FunctionCall(acc.name, acc.args.toString().ifBlank { "{}" })
            )
        }
        val message = ChatMessage(
            role = "assistant",
            content = if (text.isEmpty()) JsonNull else JsonPrimitive(text.toString()),
            toolCalls = toolCalls.ifEmpty { null }
        )
        return ChatResponse(
            model = profile.model,
            choices = listOf(ChatResponse.Choice(0, message, finishReason[0])),
            usage = usageBox[0]
        )
    }

    /** 连通性 + 工具能力自检 */
    suspend fun probe(): ProbeReport {
        val report = ProbeReport()
        // 1) 基本可达 + 鉴权
        val ping = ChatMessage(role = "user", content = JsonPrimitive("只回复两个字：收到"))
        val basic = runCatching {
            chat(listOf(ping), emptyList())
        }
        basic.exceptionOrNull()?.let { err ->
            return report.copy(failed = true, error = err.toDisplay(), hint = err.toHint())
        }
        report.basicOk = true
        report.modelEcho = basic.getOrNull()?.response?.model ?: profile.model

        // 2) 工具调用是否可用
        val tool = ToolSpec(
            function = FunctionSpec(
                name = "echo_probe",
                description = "把输入原样返回，用于验证函数调用能力",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("value") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("value")) }
                }
            )
        )
        val toolMsgs = listOf(
            ChatMessage(role = "system", content = JsonPrimitive("你必须调用 echo_probe 工具，参数 value 填 hello。不要输出自然语言。")),
            ChatMessage(role = "user", content = JsonPrimitive("请调用工具"))
        )
        runCatching { chat(toolMsgs, listOf(tool), toolChoice = "required") }
            .onSuccess { out ->
                report.toolsOk = out.response.toolCalls().isNotEmpty()
                report.toolNote = if (report.toolsOk) "工具调用可用" else "接口接受了 tools 字段但没有产生 tool_calls，可能不支持函数调用"
            }
            .onFailure {
                report.toolsOk = false
                report.toolNote = "工具调用失败：${it.toDisplay()} —— ${it.toHint()}"
            }
        return report
    }

    private suspend fun postJson(body: String): HttpResult = withContext(Dispatchers.IO) {
        val res = http.postJson(profile.chatUrl(), body, headers(), timeout)
        if (res.status !in 200..299) throw HttpClientX.fromStatus(res.status, res.body)
        res
    }
}

private fun JsonElement?.contentOrNullSafe(): String? = (this as? JsonPrimitive)?.contentOrNull()
private fun JsonElement?.asInt(): Int = (this as? JsonPrimitive)?.contentOrNull()?.toIntOrNull() ?: 0
private fun JsonElement?.asIntOr(fallback: Int): Int = (this as? JsonPrimitive)?.contentOrNull()?.toIntOrNull() ?: fallback
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

data class ProbeReport(
    var basicOk: Boolean = false,
    var toolsOk: Boolean = false,
    var failed: Boolean = false,
    var error: String = "",
    var hint: String = "",
    var modelEcho: String = "",
    var toolNote: String = ""
)

fun Throwable.toDisplay(): String = when (this) {
    is ApiError -> message ?: "未知错误"
    else -> "${javaClass.simpleName}: ${message}"
}

fun Throwable.toHint(): String = when (this) {
    is ApiError -> hint
    else -> "看日志页定位原因"
}

/** 给非协程环境（测试/脚本）用的同步入口 */
fun OpenAiClient.chatBlocking(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList()): ChatResponse =
    runBlocking { chat(messages, tools).response }
