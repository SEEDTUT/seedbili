package com.gaibang.replyagent.util

import kotlinx.serialization.json.Json

val Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}

/** 紧凑版，用于 JSONL / 请求体 */
val JsonCompact = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
