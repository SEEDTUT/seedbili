package com.gaibang.replyagent

import com.gaibang.replyagent.platform.bilibili.Wbi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WBI 签名。重排表曾经少了几项，签名会整片 403，所以这里钉死一组公开文档给的已知答案。
 */
class WbiTest {

    @Test
    fun mixinKeyMatchesDocumentedVector() {
        val img = "7cd084941338484aae1ad9425b84077c"
        val sub = "4932caff0ff746eab6f01bf08b70ac45"
        assertEquals("ea1db124af3c7062474693fa704f4ff8", Wbi.mixinKey(img, sub))
    }

    @Test
    fun mixinKeyIsAlways32AlnumChars() {
        val key = Wbi.mixinKey("a".repeat(32), "b".repeat(32))
        assertEquals(32, key.length)
        assertTrue(key.all { it.isLetterOrDigit() })
    }

    @Test
    fun keyFromUrlStripsPathAndExtension() {
        assertEquals(
            "7cd084941338484aae1ad9425b84077c",
            Wbi.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png")
        )
    }

    @Test
    fun signAppendsWtsAndDeterministicWrid() {
        val key = Wbi.mixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45")
        val params = mapOf("oid" to "113692561083470336", "type" to "1", "pn" to "1", "ps" to "20")
        val signed = Wbi.sign(params, key, nowSeconds = 1_700_000_000L)

        assertEquals("1700000000", signed["wts"])
        // 同样的入参必须得到同样的签名，否则重试与缓存无从判断
        assertEquals(
            signed["w_rid"],
            Wbi.sign(params, key, nowSeconds = 1_700_000_000L)["w_rid"]
        )
        assertEquals(32, signed["w_rid"]?.length)

        val expected = Wbi.md5(
            "oid=113692561083470336&pn=1&ps=20&type=1&wts=1700000000" + key
        )
        assertEquals(expected, signed["w_rid"])
    }

    @Test
    fun signNormalizesCharsBilibiliDoesNotSign() {
        val key = "0123456789abcdef0123456789abcdef"
        val a = Wbi.sign(mapOf("q" to "it's(*)"), key, 1L)
        val b = Wbi.sign(mapOf("q" to "its"), key, 1L)
        assertEquals(a["w_rid"], b["w_rid"])
    }

    @Test
    fun encodeQueryFollowsBilibiliQuirks() {
        assertEquals("a%20b", Wbi.encodeQuery("a b"))
        assertEquals("%2A", Wbi.encodeQuery("*"))
        assertEquals("~", Wbi.encodeQuery("~"))
    }

    @Test
    fun emptyKeyDegradesToUnsignedRequestInsteadOfThrowing() {
        val signed = Wbi.sign(mapOf("a" to "1"), "", nowSeconds = 42L)
        assertEquals("1", signed["a"])
        assertEquals("42", signed["wts"])
        assertTrue(signed["w_rid"].isNullOrBlank())
    }
}
