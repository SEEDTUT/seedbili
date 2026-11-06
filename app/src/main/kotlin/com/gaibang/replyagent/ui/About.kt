package com.gaibang.replyagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.util.Paths2
import java.awt.Desktop
import java.net.URI

const val AUTHOR_HOME = "https://seedtut.xyz/"
const val APP_VERSION = "1.4.1"

@Composable
fun AboutPage() {
    val scheme = MaterialTheme.colorScheme
    val t = tokens()
    val logo = remember { authorLogo() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader("关于", "seedbili 的来历、作者，以及它不会做的事。")

        // 品牌卡：图标 + 名字 + 版本，一行看完
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            shape = AppShape.extraLarge,
            colors = SurfaceColors(scheme.surfaceContainerLow, scheme.onSurface),
            border = null
        ) {
            Row(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon.BrandMark(64)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "seedbili",
                        style = AppTheme.type.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "B 站评论区自动回复 · Windows 桌面版",
                        style = AppTheme.type.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("v$APP_VERSION", Tone.INFO)
                        Pill("Kotlin / Compose", Tone.INFO)
                        Pill("Material 3", Tone.INFO)
                    }
                }
            }
        }

        Section("作者", "一个人做的工具，有问题直接来主页说。") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标志是白底黑字，固定在浅色卡片里显示，深浅色主题下都不会糊
                Box(
                    Modifier
                        .size(width = 196.dp, height = 62.dp)
                        .clip(AppShape.medium)
                        .background(Color(0xFFFFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    if (logo != null) {
                        Image(
                            bitmap = logo,
                            contentDescription = "SEEDTUT LAB",
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        )
                    } else {
                        Text("SEEDTUT LAB", style = AppTheme.type.titleMedium, color = Color(0xFF1B1B1F))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("SEEDTUT", style = AppTheme.type.titleMedium)
                    Text(
                        "丐帮工作室 / SEEDTUT LAB",
                        style = AppTheme.type.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                PushButton(
                    text = "打开主页",
                    icon = Icons.Rounded.OpenInBrowser,
                    style = PushButtonStyle.Secondary,
                    onClick = { openInBrowser(AUTHOR_HOME) }
                )
            }
            SectionSpace(12)
            LinkLine(AUTHOR_HOME)
        }

        Section("它怎么工作", "一轮回复固定走这五步，顺序不会变。") {
            FlowStep("抓取", "只拉你自己投稿下的评论")
            FlowStep("本地过滤", "范围、黑名单、长度、关键词先筛一遍")
            FlowStep("模型判断", "评论内容包在不可信标签里送进去")
            FlowStep("发送前复查", "再查一次台账、黑名单和自己的号")
            FlowStep("记账", "发出去、跳过、转人工都写进台账")
        }

        Section("它不会做的事", "这几条写死在代码里，界面上没有开关。", tone = t.warn) {
            Bullet("不代填、不采集、不接触密码，登录只有扫码和你自己粘 Cookie。", t)
            Bullet("不把 Cookie、API Key 写进任何明文配置或日志。", t)
            Bullet("不静默执行模型要求的命令，每条都要你点确认。", t)
            Bullet("不绕过平台风控：撞上限流就退避，连续失败自动暂停。", t)
        }

        Section("本机数据", "删掉这个目录就等于恢复出厂。") {
            KvRow("数据目录", Paths2.root.toString())
            KvRow("密钥存储", "Windows DPAPI 密文，只在本机可解")
            KvRow("台账", "CSV 追加写，重启不重复回复")
            KvRow("窗口标题", WINDOW_TITLE)
        }

        Section("致谢", "") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(16.dp), tint = t.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    "用到 Compose Multiplatform、Material 3、material-kolor、ZXing、JNA。",
                    style = AppTheme.type.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            SectionSpace(10)
            GhostButton("访问作者主页", onClick = { openInBrowser(AUTHOR_HOME) })
        }
    }
}

@Composable
private fun FlowStep(name: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
        }
        Spacer(Modifier.width(12.dp))
        Text(name, style = AppTheme.type.titleSmall, modifier = Modifier.width(96.dp))
        Text(desc, style = AppTheme.type.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun Bullet(text: String, t: AppTokens) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(t.warn)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurface)
    }
}

@Composable
private fun LinkLine(url: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        url,
        style = AppTheme.type.bodySmall.copy(color = scheme.primary),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .clip(AppShape.small)
            .clickable { openInBrowser(url) }
            .padding(vertical = 2.dp)
    )
}

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun authorLogo(): ImageBitmap? = runCatching {
    val bytes = AppIcon::class.java.classLoader.getResourceAsStream("seedtut-lab.png")?.use { it.readBytes() }
    bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
}.getOrNull()
