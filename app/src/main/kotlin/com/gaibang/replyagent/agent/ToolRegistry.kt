package com.gaibang.replyagent.agent

import com.gaibang.replyagent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 工具执行结果。ok=false 时把 errorText 回灌给模型，让它有机会改主意而不是整轮崩掉。 */
data class ToolOutcome(val ok: Boolean, val content: String) {
    companion object {
        fun ok(content: String) = ToolOutcome(true, content)
        fun fail(reason: String) = ToolOutcome(false, "工具执行失败：$reason")
    }
}

class ToolDefinition(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val requiresApproval: Boolean = false,
    /** 需要人工确认时，由 UI/引擎决定是否放行 */
    val approvalPrompt: String = "",
    val handler: suspend (JsonObject) -> ToolOutcome
)

class ToolRegistry {
    private val tools = LinkedHashMap<String, ToolDefinition>()

    fun register(def: ToolDefinition): ToolRegistry = apply { tools[def.name] = def }
    fun unregister(name: String) { tools.remove(name) }
    fun has(name: String) = tools.containsKey(name)
    fun names(): List<String> = tools.keys.toList()
    fun all(): List<ToolDefinition> = tools.values.toList()
    fun executable(): List<ToolSpec> = tools.values.map {
        ToolSpec(function = com.gaibang.replyagent.llm.FunctionSpec(it.name, it.description, it.schema))
    }

    suspend fun invoke(name: String, args: JsonObject): ToolOutcome {
        val def = tools[name] ?: return ToolOutcome.fail("没有叫「$name」的工具，可用工具：${tools.keys.joinToString()}")
        return runCatching { def.handler(args) }
            .getOrElse { ToolOutcome.fail("${it.javaClass.simpleName}: ${it.message}") }
    }
}

/** 常用 schema 片段 */
fun objSchema(
    vararg props: Pair<String, String>,
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        props.forEach { (name, desc) ->
            putJsonObject(name) {
                val type = desc.substringBefore("|", "string")
                val help = desc.substringAfter("|", "")
                put("type", type)
                if (help.isNotBlank()) put("description", help)
            }
        }
    }
    if (required.isNotEmpty()) put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
}
