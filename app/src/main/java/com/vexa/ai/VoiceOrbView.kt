package com.vexa.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class VoiceOrbView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var rmsLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var phase = 0f
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 2500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy)
        if (maxR <= 0f) return

        val (c1, c2, speed) = when (mode) {
            Mode.IDLE -> Triple(Color.parseColor("#22D3EE"), Color.parseColor("#6366F1"), 1f)
            Mode.LISTENING -> Triple(Color.parseColor("#06B6D4"), Color.parseColor("#3B82F6"), 2.2f)
            Mode.THINKING -> Triple(Color.parseColor("#A855F7"), Color.parseColor("#EC4899"), 3.2f)
            Mode.SPEAKING -> Triple(Color.parseColor("#34D399"), Color.parseColor("#06B6D4"), 2.5f)
        }

        val wave = (sin(phase * speed) + 1f) * 0.5f
        val boost = if (mode == Mode.LISTENING) rmsLevel * maxR * 0.18f else 0f
        val baseR = maxR * 0.44f + wave * maxR * 0.04f + boost

        val glowR = (baseR * 1.95f).coerceAtMost(maxR)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                Color.argb(105, Color.red(c1), Color.green(c1), Color.blue(c1)),
                Color.argb(30, Color.red(c2), Color.green(c2), Color.blue(c2)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        val rippleP = ((phase * speed) % (2 * Math.PI.toFloat())) / (2 * Math.PI.toFloat())
        val rippleR = baseR + rippleP * (maxR * 0.48f)
        val rippleA = ((1f - rippleP) * (if (mode == Mode.IDLE) 75 else 170)).toInt()
        ringPaint.strokeWidth = 2.5f
        ringPaint.color = Color.argb(rippleA.coerceIn(0, 255), Color.red(c1), Color.green(c1), Color.blue(c1))
        canvas.drawCircle(cx, cy, rippleR.coerceAtMost(maxR - 2f), ringPaint)

        corePaint.shader = RadialGradient(
            cx - baseR * 0.25f, cy - baseR * 0.25f, baseR * 1.3f,
            intArrayOf(c1, c2, Color.parseColor("#0B1120")),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, baseR, corePaint)

        if (mode != Mode.IDLE) {
            val orbitR = baseR + maxR * 0.16f
            dotPaint.color = c1
            for (i in 0 until 3) {
                val angle = phase * (if (mode == Mode.THINKING) 2.2f else 1.1f) + (i * 2.094f)
                canvas.drawCircle(
                    (cx + cos(angle) * orbitR).toFloat(),
                    (cy + sin(angle) * orbitR).toFloat(),
                    4.5f,
                    dotPaint
                )
            }
        }
    }
}
