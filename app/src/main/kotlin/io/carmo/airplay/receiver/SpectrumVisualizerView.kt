package io.carmo.airplay.receiver

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SpectrumVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 209, 139)
        style = Paint.Style.FILL
    }
    private val quietPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val bars = FloatArray(if (isLowRamDevice(context)) 16 else 28) { 0.12f }
    private var enabledBySettings = true
    private var reduceMotion = false
    private var lastSampleAtMs = 0L

    private val animator = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE && enabledBySettings && !reduceMotion) {
                decayBars()
                invalidate()
                postDelayed(this, FRAME_MS)
            }
        }
    }

    fun configure(enabled: Boolean, reduceMotion: Boolean) {
        enabledBySettings = enabled
        this.reduceMotion = reduceMotion
        visibility = if (enabled) VISIBLE else GONE
        if (enabled && !reduceMotion) {
            removeCallbacks(animator)
            post(animator)
        } else {
            removeCallbacks(animator)
            invalidate()
        }
    }

    fun recordSample(byteCount: Int) {
        if (!enabledBySettings || reduceMotion || byteCount <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAtMs < SAMPLE_THROTTLE_MS) return
        lastSampleAtMs = now
        val base = min(1.0f, max(0.16f, byteCount / 65536f))
        for (i in bars.indices) {
            val wave = (((i * 37 + byteCount) % 100) / 100f)
            bars[i] = max(bars[i], min(1.0f, base * (0.45f + wave)))
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(animator)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledBySettings) return
        if (width <= 0 || height <= 0) return
        val count = bars.size
        val gap = width * 0.012f
        val barWidth = (width - gap * (count - 1)) / count
        val baseline = height.toFloat()
        for (i in 0 until count) {
            val value = if (reduceMotion) 0.18f else bars[i]
            val barHeight = max(height * 0.12f, height * value)
            val left = i * (barWidth + gap)
            val top = baseline - barHeight
            val paint = if (reduceMotion) quietPaint else barPaint
            canvas.drawRoundRect(left, top, left + barWidth, baseline, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private fun decayBars() {
        for (i in bars.indices) {
            bars[i] = max(0.12f, bars[i] * 0.86f)
        }
    }

    private fun isLowRamDevice(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return manager?.isLowRamDevice == true
    }

    companion object {
        private const val FRAME_MS = 120L
        private const val SAMPLE_THROTTLE_MS = 90L
    }
}
