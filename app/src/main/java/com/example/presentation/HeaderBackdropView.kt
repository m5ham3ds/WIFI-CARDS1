package com.example.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Draws the app header artwork at runtime. No bitmap/photo assets are used.
 * The geometry is intentionally subtle so page titles and controls remain readable.
 */
class HeaderBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Deep navy foundation with a restrained slate highlight.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.rgb(7, 10, 18), Color.rgb(14, 20, 34), Color.rgb(9, 11, 19)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // Soft red glow under the right-side title area.
        paint.shader = RadialGradient(
            w * 0.91f, h * 0.18f, maxOf(w * 0.72f, h * 1.15f),
            intArrayOf(Color.argb(58, 255, 30, 39), Color.argb(0, 255, 30, 39)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Fine diagonal technical lines, deliberately low contrast.
        paint.color = Color.argb(17, 218, 229, 255)
        paint.strokeWidth = dp(0.65f)
        paint.style = Paint.Style.STROKE
        var i = 0
        while (i < 9) {
            val x = w * 0.32f + i * dp(22f)
            canvas.drawLine(x, 0f, x - h * 0.68f, h, paint)
            i++
        }

        // Concentric broken-looking portal rings behind the text, no image texture.
        paint.color = Color.argb(37, 255, 52, 61)
        paint.strokeWidth = dp(1f)
        val centerX = w * 0.94f
        val centerY = h * 0.48f
        for (ring in 0..4) {
            val radius = dp(18f + ring * 15f)
            canvas.drawArc(
                RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                202f, 132f, false, paint
            )
        }

        // Tiny signal nodes near the upper edge.
        paint.style = Paint.Style.FILL
        for (node in 0..5) {
            val x = w * 0.62f + dp(node * 14f)
            canvas.drawCircle(x, dp(11f), dp(if (node == 2) 1.7f else 1.05f), paint)
        }

        // Subtle neon baseline.
        paint.shader = LinearGradient(
            0f, h - dp(1.5f), w, h - dp(1.5f),
            intArrayOf(Color.argb(0, 255, 30, 39), Color.argb(170, 255, 30, 39), Color.argb(0, 255, 30, 39)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h - dp(1.5f), w, h, paint)
        paint.shader = null
    }
}
