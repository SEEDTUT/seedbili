package com.gaibang.replyagent.platform

import com.gaibang.replyagent.domain.port.PlatformAdapter
import com.gaibang.replyagent.domain.port.SecretStore
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.platform.bilibili.BilibiliAdapter
import com.gaibang.replyagent.platform.demo.DemoAdapter
import com.gaibang.replyagent.util.Paths2
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.ServiceLoader

/**
 * 适配器注册表。三级来源：
 *  1) 内置（B 站 + 演示平台）
 *  2) 主 classpath 上的 ServiceLoader 声明
 *  3) %APPDATA%\ReplyAgent\plugins\*.jar —— 外置插件，用子 cl 加载
 * 业务代码只从 registry.byId() 拿，不认识具体实现类。
 */
class AdapterRegistry(private val secrets: SecretStore) {

    private val adapters = LinkedHashMap<String, PlatformAdapter>()

    fun discover(): List<PlatformAdapter> {
        adapters.clear()
        builtIn().forEach { add(it, "内置") }

        runCatching {
            ServiceLoader.load(PlatformAdapter::class.java).forEach { add(it, "classpath 服务声明") }
        }.onFailure { AppLogger.w("plugin", "classpath ServiceLoader 失败：${it.message}") }

        externalJars().forEach { (adapter, origin) -> add(adapter, origin) }

        AppLogger.i("plugin", "可用平台：${adapters.keys.joinToString()}")
        return all()
    }

    private fun builtIn(): List<PlatformAdapter> = listOf(
        BilibiliAdapter(secrets),
        DemoAdapter()
    )

    private fun externalJars(): List<Pair<PlatformAdapter, String>> {
        val dir = Paths2.plugins
        if (!Files.isDirectory(dir)) return emptyList()
        val jars = dir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.toList() ?: emptyList()
        if (jars.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<PlatformAdapter, String>>()
        for (jar in jars) {
            runCatching {
                val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), this.javaClass.classLoader)
                ServiceLoader.load(PlatformAdapter::class.java, loader).forEach { svc ->
                    val instance = materialize(svc, jar.name)
                    if (instance != null) out += instance to "插件 ${jar.name}"
                }
            }.onFailure {
                AppLogger.e("plugin", "加载插件失败 ${jar.name}", it)
            }
        }
        return out
    }

    /** 插件类要么无参构造，要么接收 SecretStore —— 不允许插件自己碰磁盘存密钥 */
    private fun materialize(svc: PlatformAdapter, jarName: String): PlatformAdapter? {
        return runCatching {
            val ctor = svc.javaClass.getDeclaredConstructor(SecretStore::class.java)
            ctor.newInstance(secrets)
        }.getOrNull() ?: runCatching {
            svc.javaClass.getDeclaredConstructor().newInstance()
        }.getOrNull() ?: svc.also {
            AppLogger.w("plugin", "$jarName 里的 ${svc.javaClass.name} 走的是默认实例（未经密钥注入）")
        }
    }

    private fun add(adapter: PlatformAdapter, origin: String) {
        if (adapter.id.isBlank()) {
            AppLogger.w("plugin", "忽略无 id 的适配器 ${adapter.javaClass.name}")
            return
        }
        if (adapters.containsKey(adapter.id)) {
            AppLogger.w("plugin", "平台 id 重复：${adapter.id}（来自 $origin，已忽略后来的）")
            return
        }
        adapters[adapter.id] = adapter
        AppLogger.d("plugin", "注册平台 ${adapter.displayName} (${adapter.id}) ← $origin")
    }

    fun all(): List<PlatformAdapter> = adapters.values.toList()
    fun byId(id: String): PlatformAdapter? = adapters[id]
    fun primary(): PlatformAdapter? = byId("bilibili") ?: adapters.values.firstOrNull()

    fun closeAll() = adapters.values.toList().forEach { runCatching { it.close() } }
}
