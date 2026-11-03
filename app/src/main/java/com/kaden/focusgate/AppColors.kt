package com.kaden.focusgate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/** Each app's main icon color (e.g. Disney+ blue, Netflix red), for the session ring. */
object AppColors {
    private val cache = mutableMapOf<String, Int>()

    fun of(ctx: Context, pkg: String): Int = cache.getOrPut(pkg) { compute(ctx, pkg) ?: Ui.ACCENT }

    private fun compute(ctx: Context, pkg: String): Int? {
        val icon = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))

        // bucket the colorful pixels by hue and take the biggest bucket's average
        val buckets = 12
        val weight = DoubleArray(buckets)
        val r = DoubleArray(buckets); val g = DoubleArray(buckets); val b = DoubleArray(buckets)
        val hsv = FloatArray(3)
        for (y in 0 until size) for (x in 0 until size) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            val i = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            val w = (hsv[1] * hsv[2]).toDouble()
            weight[i] += w
            r[i] += Color.red(p) * w; g[i] += Color.green(p) * w; b[i] += Color.blue(p) * w
        }
        bmp.recycle()
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < 4) return null   // a grey or monochrome icon
        val c = Color.rgb((r[best] / weight[best]).toInt(), (g[best] / weight[best]).toInt(), (b[best] / weight[best]).toInt())
        // keep it visible on the dark background
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.7f)
        return Color.HSVToColor(hsv)
    }
}
