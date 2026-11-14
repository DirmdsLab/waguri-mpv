package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.hypot

/** Draws a soft glow behind the cover using colors sampled from its edges. */
internal class AmbientLightDrawable(private val backgroundColor: Int) : Drawable() {
    private val paint = Paint()
    private var source: Bitmap? = null
    private val cover = RectF()
    private var colors: IntArray? = null
    private var gradients = emptyList<RadialGradient>()

    var reach: Int = 0
        set(value) {
            if (field == value) return
            field = value
            updateGradients()
        }

    fun setCoverBounds(bounds: RectF) {
        if (cover == bounds) return
        cover.set(bounds)
        updateGradients()
    }

    fun setSource(bitmap: Bitmap?, refresh: Boolean = false) {
        if (source === bitmap && !refresh) return
        source = bitmap
        val sampled = bitmap?.let { image ->
            intArrayOf(
                sample(image, 0.15f, 0.5f),
                sample(image, 0.85f, 0.5f),
                sample(image, 0.5f, 0.15f),
                sample(image, 0.5f, 0.85f),
            )
        }
        if (sampled == null && colors == null ||
            sampled != null && colors?.contentEquals(sampled) == true
        ) return
        colors = sampled
        updateGradients()
    }

    private fun sample(bitmap: Bitmap, x: Float, y: Float): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val px = ((x + dx * 0.08f) * (bitmap.width - 1)).toInt()
                val py = ((y + dy * 0.08f) * (bitmap.height - 1)).toInt()
                val color = bitmap.getPixel(px, py)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
            }
        }
        return Color.rgb(red / 9, green / 9, blue / 9)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        updateGradients()
    }

    private fun updateGradients() {
        val palette = colors
        gradients = if (reach <= 0 || palette == null || cover.isEmpty || bounds.isEmpty) {
            emptyList()
        } else {
            val fraction = reach / 100f
            val distance = hypot(bounds.width().toFloat(), bounds.height().toFloat())
            val radius = 1f + distance * fraction * 1.4f
            val alpha = (65 + 55 * fraction).toInt()
            val centers = listOf(
                cover.left to cover.centerY(),
                cover.right to cover.centerY(),
                cover.centerX() to cover.top,
                cover.centerX() to cover.bottom,
            )
            centers.mapIndexed { index, (x, y) ->
                val color = palette[index] and 0x00FFFFFF
                RadialGradient(
                    x, y, radius,
                    intArrayOf(color or (alpha shl 24), color or ((alpha / 2) shl 24), color),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        paint.shader = null
        paint.color = backgroundColor
        canvas.drawRect(bounds, paint)
        paint.color = Color.WHITE
        for (gradient in gradients) {
            paint.shader = gradient
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
