package cn.edu.sicnu.musicplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 生成通知栏用的大图标（专辑封面）：品牌渐变圆角方块 + 白色音符。
 *
 * 实验里没有真实的封面图，用代码画一个和界面风格一致的占位图，
 * 这样通知栏不会只显示一个空白小方块。结果做了缓存，避免每次刷新通知都重新绘制。
 */
object ArtworkFactory {

    // 通知栏背景会用这张图做取色，颜色深一点系统才会把按钮图标染成白色
    private const val START_COLOR = 0xFF3B32B0.toInt()
    private const val END_COLOR = 0xFF23205E.toInt()

    private var cached: Bitmap? = null

    fun create(size: Int): Bitmap {
        cached?.let { if (it.width == size) return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = size.toFloat()

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, side, side, START_COLOR, END_COLOR, Shader.TileMode.CLAMP)
        }
        val radius = side * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, side, side), radius, radius, background)

        // 两个符头
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val headRadius = side * 0.085f
        val leftHeadX = side * 0.34f
        val rightHeadX = side * 0.62f
        val headY = side * 0.70f
        canvas.save()
        canvas.rotate(-18f, leftHeadX, headY)
        canvas.drawOval(
            RectF(leftHeadX - headRadius, headY - headRadius * 0.78f,
                leftHeadX + headRadius, headY + headRadius * 0.78f),
            note
        )
        canvas.restore()
        canvas.save()
        canvas.rotate(-18f, rightHeadX, headY - side * 0.10f)
        canvas.drawOval(
            RectF(rightHeadX - headRadius, headY - side * 0.10f - headRadius * 0.78f,
                rightHeadX + headRadius, headY - side * 0.10f + headRadius * 0.78f),
            note
        )
        canvas.restore()

        // 两条竖线 + 顶部横梁，组成一个八分音符
        val stroke = side * 0.035f
        canvas.drawRoundRect(
            RectF(leftHeadX + headRadius * 0.55f, side * 0.32f,
                leftHeadX + headRadius * 0.55f + stroke, headY - headRadius * 0.2f),
            stroke / 2f, stroke / 2f, note
        )
        canvas.drawRoundRect(
            RectF(rightHeadX + headRadius * 0.55f, side * 0.22f,
                rightHeadX + headRadius * 0.55f + stroke, headY - side * 0.10f - headRadius * 0.2f),
            stroke / 2f, stroke / 2f, note
        )
        canvas.drawRoundRect(
            RectF(leftHeadX + headRadius * 0.55f, side * 0.32f,
                rightHeadX + headRadius * 0.55f + stroke, side * 0.32f + side * 0.075f),
            stroke / 2f, stroke / 2f, note
        )

        cached = bitmap
        return bitmap
    }
}
