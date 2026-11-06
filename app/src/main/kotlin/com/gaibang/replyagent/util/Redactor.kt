package com.gaibang.replyagent.util

/**
 * 日志与异常信息脱敏。凡是要落盘/上屏的字符串都先过这里。
 * 规则集是白名单式匹配已知凭证形态，宁可多遮不可漏遮。
 */
object Redactor {
    private data class Rule(val name: String, val regex: Regex)

    private val rules = listOf(
        Rule("SESSDATA", Regex("""(?i)SESSDATA=([^;&\s"'\\]{4,})""")),
        Rule("bili_jct", Regex("""(?i)bili_jct=([^;&\s"'\\]{4,})""")),
        Rule("DedeUserID", Regex("""(?i)Dede(2)?UserID=([^;&\s"'\\]{2,})""")),
        Rule("buvid", Regex("""(?i)buvid3?=([^;&\s"'\\]{8,})""")),
        Rule("Bearer", Regex("""(?i)Bearer\s+([A-Za-z0-9._\-]{6,})""")),
        Rule("OpenAI-Key", Regex("""(sk-[A-Za-z0-9_\-]{8,})""")),
        Rule("CookieHdr", Regex("""(?i)(Cookie:\s*)([^\r\n"]{6,})""")),
        Rule("SetCookie", Regex("""(?i)(Set-Cookie:\s*)([^\r\n"]{6,})""")),
        Rule("authorization", Regex("""(?i)("authorization"\s*:\s*")([^"]{4,})""")),
        Rule("api_key", Regex("""(?i)("(?:api[_-]?key|apikey|access[_-]?token|secret)"\s*[:,=]\s*")([^"]{4,})""")),
    )

    fun mask(value: String): String {
        if (value.isBlank()) return value
        var out = value
        for (r in rules) {
            out = r.regex.replace(out) { m ->
                val keepPrefix = m.groupValues.dropLast(1).joinToString("")
                val secret = m.groupValues.last()
                val head = secret.take(4)
                "${keepPrefix}${head}***(len=${secret.length})"
            }
        }
        return out
    }

    /** 只保留长度信息的强遮罩，用于把 cookie 值写进配置说明时 */
    fun fingerprint(secret: String?): String {
        if (secret.isNullOrEmpty()) return "(空)"
        return "${secret.take(4)}***(len=${secret.length})"
    }

    fun looksLikeSecret(text: String): Boolean = rules.any { it.regex.containsMatchIn(text) }
}
