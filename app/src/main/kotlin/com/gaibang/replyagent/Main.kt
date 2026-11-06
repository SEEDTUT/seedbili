package com.gaibang.replyagent

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gaibang.replyagent.ui.AppIcon
import com.gaibang.replyagent.ui.AppRoot
import com.gaibang.replyagent.ui.AppState
import com.gaibang.replyagent.ui.Page
import com.gaibang.replyagent.ui.WINDOW_TITLE
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.gaibang.replyagent.logging.AppLogger

fun main(args: Array<String>) = application {
    val app = remember { AppState() }
    val state = rememberWindowState(width = 1220.dp, height = 820.dp)
    // --page=api 之类的直达参数：截图取证、报 bug 时不用手点六次
    val startPage = args.asSequence()
        .mapNotNull { it.removePrefix("--page=").takeIf { v -> v != it } }
        .firstOrNull()?.let { name -> Page.entries.firstOrNull { it.name.equals(name, true) } }

    Window(
        onCloseRequest = {
            app.shutdown()
            AppLogger.i("ui", "窗口关闭，进程退出")
            exitApplication()
        },
        title = WINDOW_TITLE,
        state = state,
        icon = remember { AppIcon.painter(64) }
    ) {
        LaunchedEffect(Unit) { app.boot() }
        AppRoot(app, startPage)
    }
}
