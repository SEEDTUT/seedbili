package com.gaibang.replyagent

import com.gaibang.replyagent.config.ConfigStore
import com.gaibang.replyagent.domain.model.ApiProfile
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.CustomRule
import com.gaibang.replyagent.domain.model.Identity
import com.gaibang.replyagent.domain.model.IdentityKind
import com.gaibang.replyagent.domain.model.LedgerPolicy
import com.gaibang.replyagent.domain.model.PromptProfile
import com.gaibang.replyagent.domain.model.RateLimit
import com.gaibang.replyagent.domain.model.ReplyScope
import com.gaibang.replyagent.domain.model.RuleAction
import com.gaibang.replyagent.domain.model.RuleField
import com.gaibang.replyagent.domain.model.RuleOp
import com.gaibang.replyagent.domain.model.StrategyProfile
import com.gaibang.replyagent.domain.model.DedupeKey
import com.gaibang.replyagent.secure.AesGcmSecretStore
import com.gaibang.replyagent.secure.DpapiSecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigStoreTest {

    private fun dir() = Files.createTempDirectory("cfg-test")

    @Test
    fun firstRunWritesAUsableFactoryConfig() {
        val store = ConfigStore(dir().resolve("app.json"))
        val cfg = store.load()
        assertEquals(1, cfg.apiProfiles.size)
        assertEquals(1, cfg.strategies.size)
        assertEquals(1, cfg.prompts.size)
        assertTrue(Files.exists(store.path().let { java.nio.file.Path.of(it) }))
    }

    @Test
    fun exportThenImportRoundTripsEveryEditableField() {
        val custom = AppConfig(
            apiProfiles = listOf(
                ApiProfile(id = "a", name = "中转", baseUrl = "https://relay.example/v1", model = "glm-4",
                    temperature = 0.31, maxTokens = 512, topP = 0.9, timeoutSeconds = 45, stream = true,
                    extraHeaders = mapOf("X-Tenant" to "gb")),
                ApiProfile(id = "b", name = "本地", baseUrl = "http://127.0.0.1:11434/v1", model = "qwen")
            ),
            activeApiProfileId = "b",
            strategies = listOf(
                StrategyProfile(
                    id = "s1", name = "保守", promptId = "p1",
                    scope = ReplyScope(
                        topLevelOnly = true, skipAlreadyAnswered = true,
                        minContentLength = 5, maxContentLength = 200,
                        onlyIfContains = listOf("教程", "怎么"),
                        neverIfContains = listOf("微信"),
                        customRules = listOf(CustomRule("r", "高赞", RuleField.LIKE_COUNT, RuleOp.GT, "50", RuleAction.ESCALATE))
                    ),
                    identities = listOf(Identity("i", IdentityKind.BLACKLIST, uid = "123", name = "广告机")),
                    rate = RateLimit(minIntervalMs = 30_000, jitterMs = 10, perRunCap = 3, perDayCap = 9,
                        cooldownOnLimitMs = 120_000, backoffBaseMs = 2_000, backoffMaxMs = 30_000, maxConsecutiveFailures = 2),
                    ledger = LedgerPolicy(retentionDays = 30, recheckBeforeSend = false, dedupeBy = DedupeKey.CONTENT_FINGERPRINT),
                    minConfidenceForAutoReply = 0.77, maxReplyChars = 88,
                    forbiddenReplySubstrings = listOf("包过"), allowedLinkHosts = listOf("bilibili.com"),
                    extraNotesForPrompt = "别用感叹号"
                )
            ),
            activeStrategyId = "s1",
            prompts = listOf(PromptProfile(id = "p1", name = "我的口吻", persona = "老同学聊天", version = 3)),
            activePromptId = "p1",
            recentTargets = listOf("BV1xx411c7mu")
        )

        val a = ConfigStore(dir().resolve("app.json"))
        a.load()
        a.replace(custom)
        val out = a.exportTo(a.path().let { java.nio.file.Path.of(it) }.resolveSibling("export.json"))

        val b = ConfigStore(dir().resolve("app2.json"))
        b.load()
        val imported = b.importFrom(out).getOrThrow()
        assertEquals(custom.strategies, imported.strategies)
        assertEquals(custom.prompts, imported.prompts)
        assertEquals("b", imported.activeApiProfileId)
        assertEquals(custom.apiProfile().model, "qwen".let { imported.apiProfile().model })
        assertEquals(0.77, imported.strategy().minConfidenceForAutoReply)
        assertEquals(DedupeKey.CONTENT_FINGERPRINT, imported.strategy().ledger.dedupeBy)
        assertEquals("高赞", imported.strategy().scope.customRules.single().name)
    }

    @Test
    fun importRefusesFilesThatCarryPlaintextCredentials() {
        val store = ConfigStore(dir().resolve("app.json"))
        store.load()
        val f = java.nio.file.Path.of(store.path()).resolveSibling("bad.json")
        Files.writeString(f, """{"schemaVersion":1,"note":"sk-abcdefghijklmnopqrstuvwxyz012345"}""")
        val r = store.importFrom(f)
        assertTrue(r.isFailure, "带明文密钥的文件必须拒绝")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("明文凭据"))

        val f2 = f.resolveSibling("bad2.json")
        Files.writeString(f2, """{"schemaVersion":1,"note":"buvid=1; SESSDATA=abcdefg"}""")
        assertTrue(store.importFrom(f2).isFailure)
        // 被拒之后原配置不能被动过
        assertEquals(1, store.current.strategies.size)
    }

    @Test
    fun corruptConfigIsBackedUpNotSilentlyOverwritten() {
        val path = dir().resolve("app.json")
        Files.writeString(path, "[\"不是对象，解不出来\"]")
        val store = ConfigStore(path)
        val cfg = store.load()
        assertEquals(AppConfig.CURRENT_VERSION, cfg.schemaVersion)
        val backups = path.parent.toFile().listFiles { _, name -> name.startsWith("app.corrupt-") }
        assertNotNull(backups)
        assertEquals(1, backups.size, "坏文件要留证据，不能直接盖掉")
    }

    @Test
    fun missingSectionsAreFilledWithDefaultsOnLoad() {
        val path = dir().resolve("app.json")
        Files.writeString(path, """{"schemaVersion":1,"strategies":[],"prompts":[]}""")
        val cfg = ConfigStore(path).load()
        assertEquals(1, cfg.strategies.size)
        assertEquals(1, cfg.prompts.size)
        assertEquals(cfg.strategies.first().id, cfg.activeStrategyId)
    }

    @Test
    fun secretsNeverLandInTheConfigFile() {
        val store = ConfigStore(dir().resolve("app.json"))
        store.update { it.copy(apiProfiles = listOf(ApiProfile(id = "k", name = "带 Key 的"))) }
        val text = Files.readString(java.nio.file.Path.of(store.path()))
        assertFalse(text.contains("sk-"))
        assertTrue(text.contains("apikey.k"), "配置里只留密文句柄")
    }
}

class SecretStoreTest {

    @Test
    fun aesGcmRoundTripAndCiphertextOnDisk() {
        val dir = Files.createTempDirectory("sec-test")
        val store = AesGcmSecretStore(dir)
        val secret = "sk-super-secret-value-1234567890".toByteArray()
        store.put("apikey.default", secret)
        assertArrayEquals(secret, store.get("apikey.default"))
        assertTrue(store.exists("apikey.default"))

        val raw = dir.toFile().listFiles()!!.map { Files.readAllBytes(it.toPath()) }
        assertTrue(raw.isNotEmpty())
        assertTrue(raw.none { bytes -> String(bytes).contains("super-secret") }, "盘上必须是密文")

        store.delete("apikey.default")
        assertFalse(store.exists("apikey.default"))
        assertEquals(null, store.get("apikey.default"))
    }

    @Test
    fun missingRefReadsAsNullInsteadOfThrowing() {
        val store = AesGcmSecretStore(Files.createTempDirectory("sec-empty"))
        assertEquals(null, store.get("nope"))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val dir = Files.createTempDirectory("sec-tamper")
        val store = AesGcmSecretStore(dir)
        store.put("apikey.default", "hello-world-value".toByteArray())
        val file = dir.toFile().listFiles()!!.first()
        val bytes = Files.readAllBytes(file.toPath()).also { it[last(it)] = (it[last(it)] + 1).toByte() }
        Files.write(file.toPath(), bytes)
        assertEquals(null, store.get("apikey.default"), "GCM 校验失败要当没有，不能返回半截明文")
    }

    private fun last(a: ByteArray) = a.size - 1
    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        assertTrue(actual != null && expected.contentEquals(actual), "密文解回来必须与原值一致")
    }

    @Test
    fun dpapiOnlyWorksOnWindowsAndSaysSo() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val dir = Files.createTempDirectory("sec-dpapi")
        val store = DpapiSecretStore(dir)
        assertEquals(true, store.strongProtection)
        if (isWindows) {
            store.put("k", "值".toByteArray())
            assertArrayEquals("值".toByteArray(), store.get("k"))
        } else {
            assertEquals(null, store.get("k"))
        }
    }
}
