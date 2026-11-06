package com.gaibang.replyagent.platform.bilibili

import com.gaibang.replyagent.logging.AppLogger
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 web 端 WBI 签名。
 * 算法来源是 web 前端公开的实现：nav 拿两个 key → 按固定位置表取值 → 截 32 位 → 参数排序编码后 md5。
 * 这张表随对方改版可能变化，所以整块集中在这里，改版只动一个文件。
 */
object Wbi {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    fun keyFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('.')

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString {
            for (i in MIXIN_KEY_ENC_TAB) {
                val c = raw.getOrNull(i) ?: continue
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') append(c)
                if (length >= 32) break
            }
        }.take(32)
    }

    /** 过滤掉 B 站侧不做签名的字符 */
    private fun normalize(v: String): String = v.filterNot { it in "!'()*" }

    fun encodeQuery(v: String): String = URLEncoder.encode(v, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    /**
     * 返回带 wts / w_rid 的完整参数表（不改动入参）。
     * @param key 由 nav 的 img_key+sub_key 算出的 mixinKey
     */
    fun sign(params: Map<String, String>, key: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        if (key.isBlank()) {
            AppLogger.w("wbi", "mixinKey 为空，跳过签名（接口可能返回 -403）")
            return params + ("wts" to nowSeconds.toString())
        }
        val withTs = params + ("wts" to nowSeconds.toString())
        val query = withTs.toSortedMap()
            .entries.joinToString("&") { (k, v) ->
                "${encodeQuery(k)}=${encodeQuery(normalize(v))}"
            }
        val wrid = md5(query + key)
        return withTs + ("w_rid" to wrid)
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
