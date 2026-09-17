package dev.phonerobot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import dev.phonerobot.core.MotionTarget
import kotlin.math.sin

class RobotFaceView(context: Context) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    var target = MotionTarget(0f, 0f)
        set(value) { field = value; invalidate() }
    var mode = "Ready"
        set(value) { field = value; contentDescription = "Robot face: $value"; invalidate() }
    init { setBackgroundColor(Color.rgb(9, 19, 26)); contentDescription = "Robot face: Ready" }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(null)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val phase = SystemClock.uptimeMillis() % 5000
        val blink = phase < 120 || mode == "Sleeping"
        val eyeHeight = if (blink) h * .022f else h * .17f
        ink.color = if (mode == "Thinking") Color.rgb(255, 196, 100) else Color.rgb(115, 232, 210)
        val dx = target.x * w * .055f; val dy = target.y * h * .07f
        for (center in listOf(.32f, .68f)) {
            val cx = w * center + dx; val cy = h * .42f + dy
            canvas.drawRoundRect(cx - w * .09f, cy - eyeHeight / 2, cx + w * .09f,
                cy + eyeHeight / 2, 20f, 20f, ink)
        }
        val mouth = if (mode == "Speaking") .035f + .06f * kotlin.math.abs(sin(phase / 85.0)).toFloat() else .025f
        canvas.drawRoundRect(w * .43f, h * .70f, w * .57f, h * (.70f + mouth), 12f, 12f, ink)
        if (isAttachedToWindow && isShown && mode != "Sleeping" && mode != "Paused") {
            if (mode == "Speaking") {
                postInvalidateDelayed(70)
            } else {
                val delay = if (blink) 120L else (5000 - phase).coerceIn(70L, 1000L)
                postInvalidateDelayed(delay)
            }
        }
    }
}
