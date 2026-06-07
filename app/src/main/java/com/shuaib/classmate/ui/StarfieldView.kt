package com.shuaib.classmate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.shuaib.classmate.utils.AnimUtils
import com.shuaib.classmate.utils.ThemeColors
import kotlin.random.Random

/**
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/ui/StarfieldView.kt
 */
class StarfieldView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Star(var x: Float, var y: Float, var radius: Float,
                    var speed: Float, var alpha: Float, var color: Int)
    private val stars = mutableListOf<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private val random = Random(1287)

    init {
        repeat(56) {
            val tint = when (it % 5) {
                0 -> Color.rgb(96, 165, 250)
                1 -> Color.rgb(251, 191, 36)
                else -> Color.WHITE
            }
            stars.add(Star(
                x = random.nextFloat() * 1000f,
                y = random.nextFloat() * 2000f,
                radius = 0.7f + random.nextFloat() * 1.5f,
                speed = 0.18f + random.nextFloat() * 0.34f,
                alpha = 28f + random.nextFloat() * 92f,
                color = tint
            ))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        stars.forEach {
            it.x = random.nextFloat() * w.toFloat()
            it.y = random.nextFloat() * h.toFloat()
        }

        try {
            if (ThemeColors.isDark(context) && !AnimUtils.isReduceMotionEnabled(context)) {
                startAnimation(h)
            } else {
                animator?.cancel()
            }
        } catch (_: Exception) {}
    }

    private fun startAnimation(height: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 18_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                stars.forEach { star ->
                    star.y -= star.speed
                    if (star.y < 0) {
                        star.y = height.toFloat()
                        star.x = random.nextFloat() * width.toFloat()
                    }
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!ThemeColors.isDark(context)) return
        stars.forEach { star ->
            paint.color = star.color
            paint.alpha = star.alpha.toInt()
            canvas.drawCircle(star.x, star.y, star.radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
