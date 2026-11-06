package com.gaibang.replyagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.unit.dp
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * seedbili 图标：深色圆角底 + SEEDTUT 的橙色立方体与放射线。
 * 一份像素同时供界面品牌区与安装包 .ico 使用，不依赖外部图片。
 */
object AppIcon {

    private val seedOrange = Color(0xF2, 0x65, 0x22)

    fun render(px: Int, dark: Boolean = false): BufferedImage {
        val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        val s = px.toFloat()
        val radius = s * 0.235f
        g.paint = GradientPaint(
            0f, 0f, if (dark) Color(0x20, 0x20, 0x24) else Color(0x17, 0x17, 0x1A),
            0f, s, if (dark) Color(0x0C, 0x0C, 0x0E) else Color(0x0B, 0x0B, 0x0D)
        )
        g.fill(RoundRectangle2D.Float(0f, 0f, s, s, radius, radius))

        if (px >= 24) {
            g.paint = RadialGradientPaint(
                Point2D.Float(s * 0.56f, s * 0.56f),
                s * 0.46f,
                floatArrayOf(0f, 1f),
                arrayOf(seedOrange.alpha(90), seedOrange.alpha(0))
            )
            g.fill(Ellipse2D.Float(s * 0.10f, s * 0.10f, s * 0.86f, s * 0.86f))
        }

        // 放射线：从立方体左上角向外，长度递减，和 SEEDTUT LAB 的标志同一套语言
        val burstX = s * 0.355f
        val burstY = s * 0.435f
        g.stroke = BasicStroke(s * 0.042f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val rays = listOf(
            Triple(148.0, 0.250f, 255),
            Triple(163.0, 0.195f, 255),
            Triple(181.0, 0.230f, 235),
            Triple(198.0, 0.155f, 210)
        )
        for ((deg, len, alpha) in rays) {
            val rad = Math.toRadians(deg)
            g.color = seedOrange.alpha(alpha)
            g.drawLine(
                burstX.toInt(), burstY.toInt(),
                (burstX + cos(rad).toFloat() * s * len).toInt(),
                (burstY - sin(rad).toFloat() * s * len).toInt()
            )
        }

        // 等距立方体
        val cx = s * 0.56f
        val hw = s * 0.235f
        val topY = s * 0.335f
        val midY = s * 0.455f
        val botY = s * 0.575f
        val depth = s * 0.215f

        fun face(color: Color, vararg pts: FloatArray) {
            val path = Path2D.Float()
            path.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size) path.lineTo(pts[i][0], pts[i][1])
            path.closePath()
            g.color = color
            g.fill(path)
        }

        val top = floatArrayOf(cx, topY)
        val right = floatArrayOf(cx + hw, midY)
        val center = floatArrayOf(cx, botY)
        val left = floatArrayOf(cx - hw, midY)
        val leftLow = floatArrayOf(cx - hw, midY + depth)
        val bottom = floatArrayOf(cx, botY + depth)
        val rightLow = floatArrayOf(cx + hw, midY + depth)

        face(Color(0xFF, 0xA8, 0x6A), top, right, center, left)
        face(Color(0xD8, 0x4A, 0x14), left, center, bottom, leftLow)
        face(Color(0xF2, 0x65, 0x22), right, center, bottom, rightLow)

        // 顶面左上的一道高光，小尺寸下自动消失
        if (px >= 48) {
            g.color = Color(255, 255, 255, 46)
            g.stroke = BasicStroke(s * 0.012f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(top[0].toInt(), top[1].toInt(), right[0].toInt(), right[1].toInt())
        }

        g.dispose()
        return img
    }

    private fun Color.alpha(a: Int) = Color(red, green, blue, a)

    fun painter(px: Int = 64, dark: Boolean = false): androidx.compose.ui.graphics.painter.Painter =
        render(px, dark).toPainter()

    fun bitmap(px: Int = 64, dark: Boolean = false): ImageBitmap = render(px, dark).toComposeImageBitmap()

    @Composable
    fun BrandMark(sizePx: Int = 28) {
        Image(bitmap = bitmap(sizePx * 2), contentDescription = "seedbili", modifier = Modifier.size(sizePx.dp))
    }
}
