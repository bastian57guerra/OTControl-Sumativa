package com.example.otcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Barra de tres zonas (verde > 2 m, amarillo 1–2 m, rojo < 1 m) con marcador de distancia. */
class ProximityBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E9E55.toInt() }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC77700.toInt() }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A80.toInt() }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEEEEE.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var distance = 3f
    private val seg = floatArrayOf(0.50f, 0.29f, 0.21f)
    private val rect = RectF()
    private val path = Path()

    fun setDistance(meters: Float) {
        distance = meters.coerceIn(0f, 3f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            (50 * d).toInt()
        )
    }

    /** Posición horizontal (0..1) del marcador según la distancia. */
    private fun fraction(m: Float): Float = when {
        m >= 2f -> 0.5f * (3f - m)
        m >= 1f -> 0.5f + 0.29f * (2f - m)
        else -> 0.79f + 0.21f * (1f - m)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val barTop = 14 * d
        val barBottom = 28 * d
        val gap = 2 * d
        var x = 0f
        val paints = arrayOf(green, amber, red)
        val labels = arrayOf(">2 m", "1–2 m", "<1 m")
        for (i in 0..2) {
            val segW = w * seg[i]
            rect.set(x + (if (i == 0) 0f else gap / 2), barTop, x + segW - (if (i == 2) 0f else gap / 2), barBottom)
            canvas.drawRoundRect(rect, 3 * d, 3 * d, paints[i])
            canvas.drawText(labels[i], x + segW / 2, 44 * d, labelPaint)
            x += segW
        }
        // Marcador triangular
        val mx = (w * fraction(distance)).coerceIn(6 * d, w - 6 * d)
        path.reset()
        path.moveTo(mx - 6 * d, 2 * d)
        path.lineTo(mx + 6 * d, 2 * d)
        path.lineTo(mx, 12 * d)
        path.close()
        canvas.drawPath(path, markerPaint)
    }
}
