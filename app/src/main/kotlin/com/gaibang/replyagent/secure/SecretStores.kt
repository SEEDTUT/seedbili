package com.gaibang.replyagent.secure

import com.gaibang.replyagent.domain.port.SecretStore
import com.gaibang.replyagent.logging.AppLogger
import com.gaibang.replyagent.util.Paths2
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 敏感值统一落成本地文件，值本身加密。文件头一个字节标明加密方式，
 * 这样同一份数据在 DPAPI 与退化模式之间仍可读出。
 */
private const val TAG_DPAPI: Byte = 1
private const val TAG_AESGCM: Byte = 2

abstract class FileSecretStore(protected val dir: Path) : SecretStore {

    protected fun fileFor(ref: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(ref.toByteArray()).take(16)
            .joinToString("") { "%02x".format(it) }
        return dir.resolve("$hash.sec")
    }

    override fun put(ref: String, value: ByteArray) {
        Files.createDirectories(dir)
        val wrapped = wrap(value)
        Files.write(fileFor(ref), wrapped)
        AppLogger.d("secret", "写入密文 $ref (${value.size}B, mode=${protectionDescription})")
    }

    override fun get(ref: String): ByteArray? {
        val f = fileFor(ref)
        if (!Files.exists(f)) return null
        return runCatching { unwrap(Files.readAllBytes(f)) }
            .onFailure { AppLogger.e("secret", "读取密文失败 $ref", it) }
            .getOrNull()
    }

    override fun delete(ref: String) {
        Files.deleteIfExists(fileFor(ref))
    }

    override fun exists(ref: String): Boolean = Files.exists(fileFor(ref))

    override fun refs(): List<String> =
        dir.toFile().listFiles { f -> f.name.endsWith(".sec") }?.map { it.name } ?: emptyList()

    protected abstract fun wrap(value: ByteArray): ByteArray
    protected abstract fun unwrap(blob: ByteArray): ByteArray
}

/** Windows 当前用户 DPAPI。同一台机器同一用户才能解开，密钥不落盘。 */
class DpapiSecretStore(dir: Path = Paths2.secrets) : FileSecretStore(dir) {
    override val protectionDescription = "Windows DPAPI（当前用户绑定，强保护）"
    override val strongProtection = true

    private val flags = com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN

    override fun wrap(value: ByteArray): ByteArray =
        byteArrayOf(TAG_DPAPI) + com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(value, flags)

    override fun unwrap(bytes: ByteArray): ByteArray {
        check(bytes.isNotEmpty()) { "空密文" }
        return when (bytes[0]) {
            TAG_DPAPI -> com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                bytes.copyOfRange(1, bytes.size), flags
            )
            TAG_AESGCM -> AesGcm.decrypt(bytes.copyOfRange(1, bytes.size))
            else -> error("未知密文头 0x%02x".format(bytes[0]))
        }
    }
}

/** 非 Windows / DPAPI 不可用时的退化模式：机器绑定密钥，界面必须标注为弱保护。 */
class AesGcmSecretStore(dir: Path = Paths2.secrets) : FileSecretStore(dir) {
    override val protectionDescription = "机器绑定 AES-GCM（弱保护：本机其他程序理论上可推导密钥）"
    override val strongProtection = false

    override fun wrap(value: ByteArray): ByteArray = byteArrayOf(TAG_AESGCM) + AesGcm.encrypt(value)
    override fun unwrap(bytes: ByteArray): ByteArray {
        check(bytes.isNotEmpty()) { "空密文" }
        return when (bytes[0]) {
            TAG_AESGCM -> AesGcm.decrypt(bytes.copyOfRange(1, bytes.size))
            else -> error("未知密文头 0x%02x".format(bytes[0]))
        }
    }
}

private object AesGcm {
    private val key: SecretKeySpec by lazy {
        val material = (System.getProperty("os.name") + "|" + System.getProperty("user.name") +
                "|" + System.getProperty("user.home") + "|ReplyAgent-v1")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        SecretKeySpec(digest, "AES")
    }

    fun encrypt(value: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(value)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "密文过短" }
        val iv = blob.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}

object SecretStoreFactory {
    @Volatile
    private var cached: SecretStore? = null

    fun get(): SecretStore = cached ?: synchronized(this) {
        cached ?: run {
            val isWindows = System.getProperty("os.name").contains("windows", true)
            val store = if (isWindows && dpapiWorks()) DpapiSecretStore() else AesGcmSecretStore()
            cached = store
            AppLogger.i("secret", "敏感存储模式: ${store.protectionDescription}")
            store
        }
    }

    private fun dpapiWorks(): Boolean = runCatching {
        val s = DpapiSecretStore(Files.createTempDirectory("replyagent-dpapi-probe"))
        s.put("probe", "ping".toByteArray())
        val ok = String(s.get("probe") ?: ByteArray(0)) == "ping"
        s.delete("probe")
        ok
    }.getOrElse {
        AppLogger.w("secret", "DPAPI 探测失败，退化为机器绑定 AES-GCM: ${it.message}")
        false
    }
}
