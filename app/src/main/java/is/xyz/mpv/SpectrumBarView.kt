package `is`.xyz.mpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class SpectrumBarView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    private val barCount = 36
    private var active = true
    private var lastCaptureNanos = 0L
    private val levels = FloatArray(barCount)
    private val targetLevels = FloatArray(barCount)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val barGap = 3f * resources.displayMetrics.density
    private val minimumBarHeight = 3f * resources.displayMetrics.density

    private var frameScheduled = false
    private var lastFrameTimeNanos = 0L
    private var gradientShader: Shader? = null
    private var gradientHeight = 0
    private var gradientPrimary = Color.WHITE
    private var gradientSecondary = Color.WHITE

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val deltaSeconds =
                    if (lastFrameTimeNanos == 0L) {
                        1f / 60f
                    } else {
                        ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(
                            1f / 120f,
                            0.05f,
                        )
                    }
                lastFrameTimeNanos = frameTimeNanos

                if (!active || frameTimeNanos - lastCaptureNanos > 180_000_000L)
                    targetLevels.fill(0f)
                var needsAnotherFrame = false

                for (index in 0 until barCount) {
                    val current = levels[index]
                    val target = targetLevels[index]
                    val timeConstant = if (target > current) 0.055f else 0.19f
                    val interpolation = (1f - exp(-deltaSeconds / timeConstant)).coerceIn(0f, 1f)
                    val next = current + (target - current) * interpolation

                    levels[index] = next
                    if (abs(target - next) > 0.0025f) {
                        needsAnotherFrame = true
                    }
                }

                invalidate()

                if (
                    (needsAnotherFrame ||
                        (active && frameTimeNanos - lastCaptureNanos < 180_000_000L)) &&
                        isShown &&
                        isAttachedToWindow
                ) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    frameScheduled = false
                    lastFrameTimeNanos = 0L
                }
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val primary =
            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
        val secondary = MaterialColors.getColor(this, android.R.attr.colorAccent, primary)

        if (
            gradientShader == null ||
                gradientHeight != height ||
                gradientPrimary != primary ||
                gradientSecondary != secondary
        ) {
            gradientHeight = height
            gradientPrimary = primary
            gradientSecondary = secondary
            gradientShader =
                LinearGradient(
                    0f,
                    height.toFloat(),
                    0f,
                    0f,
                    secondary,
                    primary,
                    Shader.TileMode.CLAMP,
                )
        }

        barPaint.shader = gradientShader
        barPaint.alpha = 168

        val availableWidth = max(0f, width.toFloat() - barGap * (barCount - 1))
        val barWidth = max(1f, availableWidth / barCount)
        val baseline = height * 0.96f
        val maximumHeight = height * 0.68f

        for (index in 0 until barCount) {
            val normalized = levels[index].coerceIn(0f, 1f)
            val barHeight = max(minimumBarHeight, normalized * maximumHeight)

            val left = index * (barWidth + barGap)
            barRect.set(left, baseline - barHeight, left + barWidth, baseline)

            val radius = minOf(barWidth, barHeight) * 0.5f
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }

        barPaint.shader = null
    }

    fun setFftData(fft: ByteArray) {
        if (fft.size < 8 || !isShown || !active) return
        lastCaptureNanos = System.nanoTime()

        val binCount = fft.size / 2
        val frequencyStep = binCount.toDouble().pow(1.0 / barCount)

        for (bar in 0 until barCount) {
            val startBin = max(1, frequencyStep.pow(bar.toDouble()).toInt())
            val endBin =
                minOf(binCount - 1, max(startBin, frequencyStep.pow((bar + 1).toDouble()).toInt()))

            var peakMagnitude = 0.0

            for (bin in startBin..endBin) {
                val real = fft[bin * 2].toInt()
                val imaginary = fft[bin * 2 + 1].toInt()
                val magnitude = sqrt((real * real + imaginary * imaginary).toDouble())

                if (magnitude > peakMagnitude) {
                    peakMagnitude = magnitude
                }
            }

            targetLevels[bar] = (peakMagnitude / 170.0).coerceIn(0.0, 1.0).pow(0.72).toFloat()
        }

        scheduleAnimation()
    }

    private fun scheduleAnimation() {
        if (frameScheduled || !isAttachedToWindow || !isShown) return
        frameScheduled = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            targetLevels.fill(0f)
            scheduleAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
            lastFrameTimeNanos = 0L
        }
    }

    fun clear() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        lastFrameTimeNanos = 0L
        levels.fill(0f)
        targetLevels.fill(0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        lastFrameTimeNanos = 0L
        super.onDetachedFromWindow()
    }
}
