package com.kaden.focusgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A ring split into colored slices; [segments] are (color, fraction of the whole ring). */
class Ring(ctx: Context, private val strokeDp: Int = 14) : View(ctx) {
    var segments: List<Pair<Int, Float>> = emptyList()
        set(v) { field = v; invalidate() }
    var track = Ui.FIELD

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = Ui.dp(context, strokeDp).toFloat()
        paint.strokeWidth = stroke
        box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)

        val gap = if (segments.size > 1) 2f else 0f
        var start = -90f
        for ((color, fraction) in segments) {
            val sweep = fraction.coerceIn(0f, 1f) * 360f
            if (sweep > gap) {
                paint.color = color
                canvas.drawArc(box, start + gap / 2, sweep - gap, false, paint)
            }
            start += sweep
        }
    }
}
