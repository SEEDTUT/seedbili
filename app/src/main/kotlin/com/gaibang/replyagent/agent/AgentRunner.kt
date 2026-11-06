package com.gaibang.replyagent.agent

import com.gaibang.replyagent.domain.model.Action
import com.gaibang.replyagent.domain.model.Decision
import com.gaibang.replyagent.domain.model.DecisionSource
import com.gaibang.replyagent.domain.model.FallbackMode
import com.gaibang.replyagent.domain.model.StrategyProfile
import com.gaibang.replyagent.llm.ChatMessage
import com.gaibang.replyagent.llm.FunctionSpec
import com.gaibang.replyagent.llm.OpenAiClient
import com.gaibang.replyagent.llm.ToolSpec
import com.gaibang.replyagent.llm.toDisplay
import com.gaibang.replyagent.llm.unwrapText
import com.gaibang.replyagent.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AgentResult(
    val decision: Decision,
    val iterations: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val notes: List<String> = emptyList(),
    val modelText: String = ""
)

/**
 * 单条判断的 Agent 循环。
 * 结论只从 submit_decision 工具拿；模型输出自然语言时做一次宽松兜底解析。
 */
class AgentRunner(
    private val llm: OpenAiClient,
    private val registry: ToolRegistry,
    private val maxIterations: Int = 4,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    private val submitName = "submit_decision"

    private val submitSpec = ToolSpec(
        function = FunctionSpec(
            name = submitName,
            description = "给出本条评论的最终处理结论。整轮判断只能调用一次。",
            parameters = objSchema(
                "action" to "string|reply=回复 / skip=不回 / escalate=拿不准转人工",
                "reply_text" to "string|action=reply 时必填，可直接发送的成品文本",
                "confidence" to "number|0~1 把握度",
                "reason" to "string|一句话说明为什么这么决定，给人看",
                required = listOf("action", "confidence", "reason")
            )
        )
    )

    suspend fun decide(
        messages: List<ChatMessage>,
        strategy: StrategyProfile,
        includeSubmitInTools: Boolean = true
    ): AgentResult {
        val convo = messages.toMutableList()
        val tools = registry.executable() + if (includeSubmitInTools) listOf(submitSpec) else emptyList()
        var submitted: Decision? = null
        var iter = 0
        var tin = 0
        var tout = 0
        var totalLatency = 0L
        val notes = mutableListOf<String>()
        val started = System.currentTimeMillis()
        var modelText = ""

        while (iter < maxIterations && submitted == null) {
            iter++
            val outcome = try {
                llm.chat(convo, tools, toolChoice = if (includeSubmitInTools) "auto" else null)
            } catch (e: Exception) {
                notes += "模型调用失败：${e.toDisplay()}"
                AppLogger.e("agent", "第 $iter 轮模型调用失败", e)
                return degrade(
                    Decision(Action.ESCALATE, "", 0.0, "模型不可用：${e.toDisplay()}", DecisionSource.ERROR),
                    strategy, iter, tin, tout, started, notes
                )
            }
            tin += outcome.tokensIn
            tout += outcome.tokensOut

            val msg = outcome.response.firstMessage() ?: run {
                notes += "返回里没有 message"
                break
            }
            convo.add(msg)
            modelText = msg.content?.let { unwrapText(it) } ?: modelText

            val calls = outcome.response.toolCalls()
            if (calls.isEmpty()) {
                val parsed = parseDecisionFromText(modelText)
                if (parsed != null) {
                    submitted = parsed
                    notes += "模型没走工具，按文本里的 JSON 兜底解析成功"
                } else {
                    notes += "第 $iter 轮没给结论（返回纯文本），已提醒改用工具"
                    convo.add(ChatMessage("user", JsonPrimitive(
                        "不要用自然语言。请调用 $submitName 工具输出结论。"
                    )))
                }
            } else {
                calls.forEach { call ->
                    val args = parseArgs(call.function.arguments) ?: JsonObject(emptyMap())
                    if (call.function.name == submitName) {
                        submitted = decisionFromArgs(args)
                        convo.add(ChatMessage("tool", JsonPrimitive("结论已记录，本轮结束。"), toolCallId = call.id))
                    } else {
                        val res = registry.invoke(call.function.name, args)
                        AppLogger.d("agent", "工具 ${call.function.name} → ok=${res.ok} ${res.content.take(160)}")
                        convo.add(ChatMessage("tool", JsonPrimitive(res.content), toolCallId = call.id))
                    }
                }
            }
        }

        val raw = submitted
            ?: Decision(Action.ESCALATE, "", 0.0, "超过 $maxIterations 轮仍未给出结论", DecisionSource.GUARDRAIL)
            .also { notes += "循环耗尽，降级转人工" }

        return degrade(raw, strategy, iter, tin, tout, started, notes, modelText)
    }

    /**
     * 策略收口：把握度不足 → 按兜底模式改写动作；回复文本不合格 → 强制转人工。
     * 这一步不依赖模型自觉，是代码层的最后防线。
     */
    private fun degrade(
        raw: Decision,
        strategy: StrategyProfile,
        iter: Int,
        tin: Int,
        tout: Int,
        startedAt: Long,
        notes: MutableList<String>,
        modelText: String = ""
    ): AgentResult {
        var decision = raw

        if (decision.action == Action.REPLY) {
            val check = Guardrails.validateReply(decision.text, strategy)
            if (check is ReplyError) {
                notes += "回复文本被护栏拦下：${check.reason}"
                decision = decision.copy(action = Action.ESCALATE, reason = "${decision.reason}（护栏：${check.reason}）")
            } else if (check is ReplyOk && check.text != decision.text) {
                decision = decision.copy(text = check.text)
            }
        }

        if (decision.action == Action.REPLY && decision.confidence < strategy.minConfidenceForAutoReply) {
            notes += "把握度 ${decision.confidence} < 阈值 ${strategy.minConfidenceForAutoReply}，按兜底模式 ${strategy.fallback} 处理"
            decision = when (strategy.fallback) {
                FallbackMode.REPORT_ONLY -> decision.copy(action = Action.ESCALATE, source = DecisionSource.GUARDRAIL)
                FallbackMode.SKIP -> decision.copy(action = Action.SKIP, source = DecisionSource.GUARDRAIL)
                FallbackMode.AUTO_REPLY -> decision
            }
        }

        if (Guardrails.looksLikeInjection(modelText)) {
            notes += "模型输出里出现疑似被评论内容诱导的痕迹，已记录供复盘"
        }

        return AgentResult(
            decision = decision,
            iterations = iter,
            tokensIn = tin,
            tokensOut = tout,
            latencyMs = System.currentTimeMillis() - startedAt,
            notes = notes,
            modelText = modelText
        )
    }

    private fun decisionFromArgs(args: JsonObject?): Decision {
        if (args == null) return Decision(Action.ESCALATE, "", 0.0, "工具参数不是 JSON 对象", DecisionSource.GUARDRAIL)
        val action = (args["action"]?.asText() ?: "").lowercase().let {
            when {
                it.startsWith("reply") -> Action.REPLY
                it.startsWith("skip") -> Action.SKIP
                else -> Action.ESCALATE
            }
        }
        val text = args["reply_text"]?.asText() ?: args["text"]?.asText() ?: ""
        val confidence = args["confidence"]?.asText()?.toDoubleOrNull() ?: (if (action == Action.REPLY) 1.0 else 0.0)
        val reason = args["reason"]?.asText() ?: "模型未说明理由"
        return Decision(action, text, confidence.coerceIn(0.0, 1.0), reason, DecisionSource.MODEL)
    }

    private fun parseArgs(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject }.getOrNull()

    private fun parseDecisionFromText(text: String): Decision? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        val obj = parseArgs(candidate) ?: return null
        if (obj["action"] == null) return null
        return decisionFromArgs(obj)
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }
}
