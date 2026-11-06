package com.gaibang.replyagent.config

import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.Json
import com.gaibang.replyagent.util.Paths2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ConfigStore(file: Path = Paths2.config.resolve("app.json")) {

    private val file = file
    private val lock = Any()
    private val _state = MutableStateFlow(AppConfig())
    val state: StateFlow<AppConfig> = _state.asStateFlow()

    val current: AppConfig get() = _state.value

    fun load(): AppConfig = synchronized(lock) {
        val loaded = if (Files.exists(file)) {
            runCatching {
                val cfg = Json.decodeFromString(AppConfig.serializer(), Files.readString(file))
                migrate(cfg)
            }.onFailure {
                AppLogger.e("config", "配置文件解析失败，使用出厂配置并保留坏文件备份", it)
                backupCorrupt()
            }.getOrNull()
        } else null
        _state.value = loaded ?: AppConfig()
        if (loaded == null) saveUnsafe(_state.value)
        AppLogger.i("config", "配置已加载: ${file} (api=${_state.value.apiProfiles.size}, strategy=${_state.value.strategies.size})")
        _state.value
    }

    /** 未来版本改结构时在这里补；当前只做「缺项补默认」。 */
    private fun migrate(cfg: AppConfig): AppConfig {
        var out = cfg
        if (out.apiProfiles.isEmpty()) out = out.copy(apiProfiles = listOf(com.gaibang.replyagent.domain.model.ApiProfile.default()))
        if (out.strategies.isEmpty()) out = out.copy(strategies = listOf(com.gaibang.replyagent.domain.model.StrategyDefaults.strategy()))
        if (out.prompts.isEmpty()) out = out.copy(prompts = listOf(com.gaibang.replyagent.domain.model.StrategyDefaults.prompt()))
        if (out.activeApiProfileId !in out.apiProfiles.map { it.id }) out = out.copy(activeApiProfileId = out.apiProfiles.first().id)
        if (out.activeStrategyId !in out.strategies.map { it.id }) out = out.copy(activeStrategyId = out.strategies.first().id)
        if (out.activePromptId !in out.prompts.map { it.id }) out = out.copy(activePromptId = out.prompts.first().id)
        if (out.schemaVersion != AppConfig.CURRENT_VERSION) {
            AppLogger.w("config", "配置版本 ${out.schemaVersion} → ${AppConfig.CURRENT_VERSION}")
            out = out.copy(schemaVersion = AppConfig.CURRENT_VERSION)
        }
        return out
    }

    private fun backupCorrupt() {
        runCatching {
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling("app.corrupt-${System.currentTimeMillis()}.json"),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun update(transform: (AppConfig) -> AppConfig) = synchronized(lock) {
        val next = transform(_state.value)
        _state.value = next
        saveUnsafe(next)
        next
    }

    /** 整体替换（导入配置时用） */
    fun replace(next: AppConfig) = synchronized(lock) {
        val migrated = migrate(next)
        _state.value = migrated
        saveUnsafe(migrated)
        migrated
    }

    private fun saveUnsafe(cfg: AppConfig) {
        synchronized(lock) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, Json.encodeToString(AppConfig.serializer(), cfg))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun exportTo(dest: Path): Path {
        Files.createDirectories(dest.parent)
        Files.writeString(dest, Json.encodeToString(AppConfig.serializer(), _state.value))
        AppLogger.i("config", "配置已导出: $dest")
        return dest
    }

    /** 导入：allowSecrets=false —— 配置文件里本来就没有密钥，这里只是防御性检查 */
    fun importFrom(src: Path): Result<AppConfig> = runCatching {
        val text = Files.readString(src)
        require(!text.contains("SESSDATA=") && !Regex("sk-[A-Za-z0-9]{20,}").containsMatchIn(text)) {
            "导入文件疑似包含明文凭据，已拒绝。请导出后再人工删除敏感字段。"
        }
        replace(Json.decodeFromString(AppConfig.serializer(), text))
    }.onFailure { AppLogger.e("config", "导入失败: ${src}", it) }

    fun path(): String = file.toString()
}
