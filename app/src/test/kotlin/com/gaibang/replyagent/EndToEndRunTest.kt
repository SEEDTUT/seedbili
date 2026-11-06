package com.gaibang.replyagent

import com.gaibang.replyagent.app.ledger.LedgerService
import com.gaibang.replyagent.app.policy.FilterEngine
import com.gaibang.replyagent.app.sandbox.SandboxService
import com.gaibang.replyagent.app.workflow.RunPhase
import com.gaibang.replyagent.app.workflow.WorkflowEngine
import com.gaibang.replyagent.agent.PromptRenderer
import com.gaibang.replyagent.config.ConfigStore
import com.gaibang.replyagent.domain.model.ApiProfile
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.domain.model.LoginStatus
import com.gaibang.replyagent.domain.model.RateLimit
import com.gaibang.replyagent.domain.model.StrategyProfile
import com.gaibang.replyagent.domain.port.SecretStore
import com.gaibang.replyagent.platform.demo.DemoAdapter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 内存版密钥仓，测试用；生产走 DPAPI。 */
private class MapSecrets : SecretStore {
    private val m = HashMap<String, ByteArray>()
    override val protectionDescription = "测试用内存存储"
    override val strongProtection = true
    override fun put(ref: String, value: ByteArray) { m[ref] = value }
    override fun get(ref: String): ByteArray? = m[ref]
    override fun delete(ref: String) { m.remove(ref) }
    override fun exists(ref: String) = m.containsKey(ref)
    override fun refs(): List<String> = m.keys.toList()
}

/**
 * 一轮完整链路：抓取 → 本地过滤 → 模型判断 → 发送前复查 → 发送 → 落账。
 * 模型侧用一个真的 HTTP 服务（只是返回固定结论），所以连 OpenAI 客户端、
 * 序列化、工具调用解析都是真的在跑，不是打桩。
 */
class EndToEndRunTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val requests = ConcurrentLinkedQueue<String>()
    @Volatile private var replyText = "我用的是 VSCode，插件装 Volar 就顺了。"
    @Volatile private var confidence = 0.9
    @Volatile private var action = "reply"

    private fun startModelStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/v1/chat/completions") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            requests.add(body)
            val args = buildJsonObject {
                put("action", action)
                put("reply_text", replyText)
                put("confidence", confidence)
                put("reason", "测试固定结论")
            }.toString()
            val payload = buildJsonObject {
                put("id", "stub")
                put("model", "stub-1")
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        put("finish_reason", "tool_calls")
                        putJsonObject("message") {
                            put("role", "assistant")
                            putJsonArray("tool_calls") {
                                addJsonObject {
                                    put("id", "call_1")
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", "submit_decision")
                                        put("arguments", args)
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 120)
                    put("completion_tokens", 30)
                    put("total_tokens", 150)
                }
            }.toString().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @BeforeTest
    fun setUp() = startModelStub()

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.stop(0)
    }

    private fun dir() = Files.createTempDirectory("e2e")

    /** 演示平台默认未登录（这是它该有的样子），跑链路前先走一次它的假登录 */
    private fun loginDemo(demo: DemoAdapter) = runBlocking {
        val session = demo.startLogin()
        while (session.events.tryReceive().isSuccess) Unit
        assertTrue(demo.loginStatus() is LoginStatus.LoggedIn, "演示平台的假登录要能走通")
    }

    private class Harness(
        val engine: WorkflowEngine,
        val ledger: LedgerService,
        val ledgerFile: java.nio.file.Path,
        val config: ConfigStore,
        val demo: DemoAdapter
    )

    private fun harness(strategy: StrategyProfile): Harness {
        val ledgerFile = dir().resolve("ledger.jsonl")
        val config = ConfigStore(dir().resolve("app.json"))
        config.load()
        config.update { c ->
            c.copy(
                apiProfiles = listOf(
                    ApiProfile(
                        id = "stub", name = "本地假模型",
                        baseUrl = "http://127.0.0.1:$port/v1",
                        model = "stub-1", timeoutSeconds = 10, maxTokens = 200
                    )
                ),
                activeApiProfileId = "stub",
                strategies = listOf(strategy),
                activeStrategyId = strategy.id
            )
        }
        val secrets = MapSecrets()
        secrets.put("apikey.stub", "test-key-please-ignore".toByteArray())
        val ledger = LedgerService(ledgerFile)
        ledger.load()
        val demo = DemoAdapter()
        loginDemo(demo)
        val engine = WorkflowEngine(
            config, secrets, { demo }, FilterEngine(), PromptRenderer(), SandboxService(), ledger
        )
        return Harness(engine, ledger, ledgerFile, config, demo)
    }

    /** 演示数据里有 10 条，出厂策略会先本地滤掉 3 条 */
    private fun fastStrategy() = StrategyProfile(
        id = "e2e", name = "e2e",
        rate = RateLimit(minIntervalMs = 1, jitterMs = 1, perRunCap = 99, perDayCap = 99,
            maxConsecutiveFailures = 99)
    )

    private fun runRound(h: Harness, dryRun: Boolean) = runBlocking {
        h.engine.start("about:blank#demo", dryRun = dryRun).join()
    }

    @Test
    fun oneFullCycleFiltersLocallyThenSendsThenBooksIt() {
        val h = harness(fastStrategy())
        runRound(h, dryRun = false)

        val p = h.engine.progress.value
        assertEquals(RunPhase.DONE, p.phase, "本轮应当正常收尾：${p.note}")
        assertEquals(10, p.scanned)
        // 楼中楼 / 黑名单 / 已回过 各一条，全部在进模型之前就被拦下
        assertEquals(3, p.filteredOut)
        assertEquals(7, p.decided)
        assertEquals(7, requests.size, "被本地过滤掉的评论不该产生任何 API 调用")

        // 9009 是演示平台故意给的「内容重复」，不该算成功，也不该重试
        assertEquals(6, p.sent)
        assertEquals(1, p.failed)
        assertEquals(LedgerStatus.SENT, h.ledger.entryFor("9001")?.status)
        assertEquals(LedgerStatus.DUP_SKIPPED, h.ledger.entryFor("9009")?.status)
        assertEquals(LedgerStatus.FILTERED, h.ledger.entryFor("9007")?.status)
        assertEquals(6, h.demo.sentReplies.size)
        assertTrue(h.demo.sentReplies.first { it.first == "9001" }.second.contains("Volar"))

        // 台账落盘了才算数：换个实例从同一个文件重读
        val reloaded = LedgerService(h.ledgerFile)
        assertTrue(reloaded.load() >= 9, "重启后仍要认得这些评论，否则第二轮就会重复回")
        assertEquals(LedgerStatus.SENT, reloaded.entryFor("9001")?.status)
        h.engine.close()
    }

    @Test
    fun secondRunOnTheSameTargetDoesNotDoubleReply() {
        val h = harness(fastStrategy())
        runRound(h, dryRun = false)
        val first = h.demo.sentReplies.size
        requests.clear()
        runRound(h, dryRun = false)
        assertEquals(first, h.demo.sentReplies.size, "台账是幂等的唯一来源")
        assertEquals(0, requests.size, "已经处理过的评论不该再花一次模型钱")
        h.engine.close()
    }

    @Test
    fun dryRunProducesZeroRealSends() {
        val h = harness(fastStrategy())
        runRound(h, dryRun = true)
        assertEquals(0, h.demo.sentReplies.size, "试跑一条都不许发")
        assertEquals(0, h.engine.progress.value.sent)
        assertEquals(7, h.engine.inbox.value.size, "试跑要把本应发的结论全推到收件箱给人看")
        assertTrue(h.ledger.all().any { it.status == LedgerStatus.ESCALATED })
        h.engine.close()
    }

    @Test
    fun untrustedCommentIsFencedAndNeutralizedBeforeItReachesTheModel() {
        val h = harness(fastStrategy())
        runRound(h, dryRun = false)
        val probe = requests.first { it.contains("忽略之前的规则") }
        assertTrue(probe.contains("<untrusted_comment_data>"), "评论必须包在不可信数据标签里")
        assertTrue(probe.contains("内容开始>>>"))
        // 「忽略之前的规则」是评论正文，不是指令：它出现的位置应在标签内部
        val inside = probe.substringAfter("<untrusted_comment_data>").substringBefore("</untrusted_comment_data>")
        assertTrue(inside.contains("忽略之前的规则"))
        assertFalse(
            Regex("(?im)^\\s*system\\s*[:：]").containsMatchIn(inside),
            "不允许出现裸的角色切换标记"
        )
        h.engine.close()
    }

    @Test
    fun lowConfidenceReplyIsHeldBackInsteadOfSent() {
        confidence = 0.2
        val s = fastStrategy().copy(minConfidenceForAutoReply = 0.6)
        val h = harness(s)
        runRound(h, dryRun = false)
        assertEquals(0, h.demo.sentReplies.size, "把握度不足一条都不能发")
        assertEquals(7, h.engine.progress.value.escalated)
        assertTrue(h.engine.inbox.value.isNotEmpty(), "兜底要进收件箱等人，而不是悄悄丢掉")
        h.engine.close()
    }

    @Test
    fun replyTextThatTripsTheGuardrailsNeverLeavesTheMachine() {
        replyText = "加我微信 12345 领取资料"
        val s = fastStrategy().copy(forbiddenReplySubstrings = listOf("微信"))
        val h = harness(s)
        runRound(h, dryRun = false)
        assertEquals(0, h.demo.sentReplies.size)
        assertTrue(h.ledger.all().any { it.status == LedgerStatus.ESCALATED })
        assertTrue(
            h.ledger.all().any { it.reason.contains("微信") || it.replyContent.contains("微信") },
            "被护栏拦下的原因要留在台账里给人看"
        )
        h.engine.close()
    }

    @Test
    fun missingApiKeyStopsTheRunBeforeAnyNetworkCall() {
        val config = ConfigStore(dir().resolve("app.json"))
        config.load()
        config.update { c ->
            c.copy(
                apiProfiles = listOf(ApiProfile(id = "stub", name = "没密钥", baseUrl = "http://127.0.0.1:$port/v1")),
                activeApiProfileId = "stub",
                strategies = listOf(fastStrategy()),
                activeStrategyId = "e2e"
            )
        }
        val ledger = LedgerService(dir().resolve("ledger.jsonl"))
        ledger.load()
        val demo = DemoAdapter()
        // 登录态先立起来，让「缺 Key」成为唯一的拦路石
        loginDemo(demo)
        val engine = WorkflowEngine(
            config, MapSecrets(), { demo }, FilterEngine(), PromptRenderer(), SandboxService(), ledger
        )
        runBlocking { engine.start("about:blank#demo").join() }
        assertEquals(0, requests.size)
        assertEquals(0, demo.sentReplies.size)
        assertEquals(RunPhase.FAILED, engine.progress.value.phase)
        assertTrue(engine.progress.value.note.contains("Key"))
        engine.close()
    }

    @Test
    fun notLoggedInRefusesToTouchThePlatform() {
        val h = harness(fastStrategy())
        runBlocking { h.demo.logout() }
        runRound(h, dryRun = false)
        assertEquals(RunPhase.FAILED, h.engine.progress.value.phase)
        assertTrue(h.engine.progress.value.note.contains("登录"))
        assertEquals(0, requests.size)
        h.engine.close()
    }
}
