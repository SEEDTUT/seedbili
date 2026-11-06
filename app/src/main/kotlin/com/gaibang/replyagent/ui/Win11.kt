package com.gaibang.replyagent.ui

import com.gaibang.replyagent.logging.AppLogger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.delay

/** dwmapi 只暴露我们要的一件事：把标题栏染成深色 */
private interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer?, attr: Int, value: IntArray?, size: Int): Int

    companion object {
        val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19

/** Win11 细节：标题栏跟随主题、暗色模式读注册表 */
object Win11 {

    private val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)

    /** 系统是否给应用开了深色（AppsUseLightTheme = 0） */
    fun systemUsesDarkTitleBars(): Boolean = runCatching {
        if (!isWindows) false
        else {
            val v = Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme"
            )
            v == 0
        }
    }.getOrElse { false }

    fun applyTitleBarDark(windowTitle: String, dark: Boolean): Boolean {
        if (!isWindows) return false
        return runCatching {
            val hwnd = User32.INSTANCE.FindWindow(null, windowTitle) ?: return@runCatching false
            val value = intArrayOf(if (dark) 1 else 0)
            val attrSize = 4
            var r = DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd.pointer, DWMWA_USE_IMMERSIVE_DARK_MODE, value, attrSize)
            if (r != 0) r = DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd.pointer, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, value, attrSize)
            r == 0
        }.onFailure { AppLogger.d("ui", "标题栏深色设置跳过：${it.message}") }.getOrDefault(false)
    }

    suspend fun applyTitleBarDarkWithRetry(windowTitle: String, dark: Boolean, attempts: Int = 6) {
        repeat(attempts) {
            if (applyTitleBarDark(windowTitle, dark)) return
            delay(150)
        }
    }
}
