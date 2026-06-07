package com.shuaib.classmate.ui

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.view.View

/**
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/ui/GlowHelper.kt
 * Helper to apply glowing effects to views.
 */
object GlowHelper {
    fun applyGlow(view: View, glowColor: Int, radius: Float = 40f) {
        if (radius <= 0f) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, Paint().apply {
            this.color = glowColor
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER)
        })
    }

    fun pulseGlow(view: View, glowColor: Int) {
        val animator = ValueAnimator.ofFloat(20f, 50f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                applyGlow(view, glowColor, anim.animatedValue as Float)
            }
        }
        animator.start()
    }
}
