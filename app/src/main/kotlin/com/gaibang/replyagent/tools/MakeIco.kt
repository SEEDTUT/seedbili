package com.gaibang.replyagent.tools

import com.gaibang.replyagent.ui.AppIcon
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 AppIcon 画出来的像素打包成 Windows .ico，给 jpackage 当安装包图标。
 * 用 32bpp BMP 条目而不是 PNG 条目：老壳层对 PNG-in-ICO 的支持不一致，BMP 到处都认。
 *
 * 用法：gradle makeIco
 */
fun main(args: Array<String>) {
    val out = File(if (args.isEmpty()) "src/main/resources/app.ico" else args[0])
    out.parentFile?.mkdirs()
    val sizes = intArrayOf(16, 24, 32, 48, 64, 128, 256)
    val images = sizes.map { sz -> bmpEntry(AppIcon.render(sz)) }
    out.writeBytes(container(images, sizes))
    println("已生成 ${out.absolutePath}（${out.length()} 字节，${sizes.size} 个尺寸）")
}

private fun bmpEntry(img: java.awt.image.BufferedImage): ByteArray {
    val w = img.width
    val h = img.height
    val xor = ByteArray(w * h * 4)
    var p = 0
    // DIB 的行是从下往上存的
    for (y in h - 1 downTo 0) {
        for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            xor[p++] = (argb and 0xFF).toByte()            // B
            xor[p++] = ((argb shr 8) and 0xFF).toByte()    // G
            xor[p++] = ((argb shr 16) and 0xFF).toByte()   // R
            xor[p++] = ((argb shr 24) and 0xFF).toByte()   // A
        }
    }
    val maskRow = ((w + 31) / 32) * 4
    val mask = ByteArray(maskRow * h) // 全 0：透明度由上面的 alpha 通道决定

    val head = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    head.putInt(40).putInt(w).putInt(h * 2).putShort(1).putShort(32)
        .putInt(0).putInt(xor.size + mask.size).putInt(0).putInt(0).putInt(0).putInt(0)
    return head.array() + xor + mask
}

private fun container(images: List<ByteArray>, sizes: IntArray): ByteArray {
    val dir = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
    dir.putShort(0).putShort(1).putShort(images.size.toShort())
    var offset = dir.capacity()
    images.forEachIndexed { i, bytes ->
        val sz = sizes[i]
        dir.put((if (sz >= 256) 0 else sz).toByte())
            .put((if (sz >= 256) 0 else sz).toByte())
            .put(0).put(0)
            .putShort(1).putShort(32)
            .putInt(bytes.size).putInt(offset)
        offset += bytes.size
    }
    return dir.array() + images.fold(ByteArray(0)) { acc, b -> acc + b }
}
