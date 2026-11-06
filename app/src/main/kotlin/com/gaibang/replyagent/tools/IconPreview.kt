package com.gaibang.replyagent.tools

import com.gaibang.replyagent.ui.AppIcon
import java.io.File
import javax.imageio.ImageIO

/** 把图标按大尺寸导出为 PNG（参数为输出路径），人工检查用。 */
fun main(args: Array<String>) {
    val out = File(if (args.isEmpty()) "icon-preview.png" else args[0])
    ImageIO.write(AppIcon.render(512), "png", out)
    println("已生成 ${out.absolutePath}（${out.length()} 字节）")
}
