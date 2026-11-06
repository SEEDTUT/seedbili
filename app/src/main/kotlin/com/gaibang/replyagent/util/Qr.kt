package com.gaibang.replyagent.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 本地渲染二维码，绝不把登录内容发给任何第三方图片服务。 */
object Qr {
    data class QrImage(val argb: IntArray, val size: Int)

    fun render(text: String, size: Int = 280): QrImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                px[off + x] = if (matrix.get(x, y)) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return QrImage(px, w)
    }
}
