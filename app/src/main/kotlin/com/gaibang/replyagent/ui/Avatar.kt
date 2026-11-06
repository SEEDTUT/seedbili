package com.gaibang.replyagent.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.gaibang.replyagent.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.URL

/** 拉取并缓存平台头像（进程内缓存，不落盘）。 */
object AvatarStore {
    private val cache = HashMap<String, ImageBitmap>()
    private val mutex = Mutex()

    suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith("http")) return null
        mutex.withLock { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 seedbili/1.3")
                conn.inputStream.use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
            }.getOrElse {
                AppLogger.d("ui", "头像拉取失败：${it.message}")
                null
            }
        }?.also { bitmap -> mutex.withLock { cache[url] = bitmap } }
    }
}
