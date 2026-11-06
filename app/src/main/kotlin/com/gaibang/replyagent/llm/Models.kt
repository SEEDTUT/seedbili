package com.gaibang.replyagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class FunctionSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class ToolSpec(val type: String = "function", val function: FunctionSpec)

@Serializable
data class FunctionCall(val name: String, val arguments: String = "{}")

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

/**
 * content 允许是 string 或 null；用 JsonElement 承载以免序列化空串被某些中转判为非法。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    val name: String? = null
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean = false
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: ChatMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    fun firstMessage(): ChatMessage? = choices.firstOrNull()?.message
    fun text(): String = firstMessage()?.content?.let { unwrapText(it) } ?: ""
    fun toolCalls(): List<ToolCall> = firstMessage()?.toolCalls ?: emptyList()
}

/** 有些中转把 content 返回成 [{"type":"text","text":"..."}] 结构，这里统一拍平成纯文本 */
fun unwrapText(element: JsonElement): String = when (element) {
    is kotlinx.serialization.json.JsonPrimitive -> element.content
    is kotlinx.serialization.json.JsonArray -> element.joinToString("") { item ->
        (item as? JsonObject)?.get("text")?.let { unwrapText(it) } ?: ""
    }
    else -> element.toString()
}

/** 分类过的 API 错误，界面据此说人话 */
sealed class ApiError(message: String, val hint: String = "", cause: Throwable? = null) : Exception(message, cause) {
    class Dns(msg: String) : ApiError("域名解析失败：$msg", "检查 Base URL 拼写与本机网络；用了中转就把域名整段贴全")
    class Connect(msg: String, cause: Throwable?) : ApiError("连不上服务器", "确认服务在跑、端口对、没被防火墙/代理拦住。原始信息：$msg", cause)
    class Tls(msg: String) : ApiError(
        "TLS 握手失败（$msg）",
        "常见于自签证书或证书过期；先把系统时间调对，或换 https 正常证书的服务"
    )
    class Auth(val status: Int) : ApiError("鉴权失败（HTTP $status）", "API Key 不对、已失效，或 Base URL 指向的不是这个 Key 所属的服务")
    class Model(val status: Int, val detail: String) : ApiError("模型名不被接受（HTTP $status）", "改「模型」一栏。服务侧原话：$detail")
    class Quota(val status: Int, val detail: String) : ApiError("配额或频率限制（HTTP $status）", "余额不足或触发限流，先把发送节奏调慢")
    class BadShape(val detail: String) : ApiError("返回结构不是 OpenAI 风格", "服务返回的东西没法按 chat/completions 解析。原文片段：$detail")
    class Timeout(detail: String) : ApiError("请求超时", "模型响应慢或网络抖动；把 API 配置里的超时调大。原始信息：$detail")
    class Server(val status: Int, val detail: String) : ApiError("服务端错误（HTTP $status）", "稍后重试；持续失败就换一条线路")
    class Unknown(msg: String, cause: Throwable?) : ApiError("请求失败：$msg", "看日志页最后一条记录定位", cause)
}
