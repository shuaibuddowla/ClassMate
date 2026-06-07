package com.shuaib.classmate.utils

import android.provider.Settings
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/utils/ViewExtensions.kt
 */
fun View.animateSpringScale(targetScale: Float) {
    val springX = SpringAnimation(this, SpringAnimation.SCALE_X, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
    }
    val springY = SpringAnimation(this, SpringAnimation.SCALE_Y, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
    }

    this.scaleX = targetScale
    this.scaleY = targetScale
    springX.start()
    springY.start()
}

fun View.applyClickAnimation(onClick: () -> Unit) {
    val reduceMotion = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    setOnClickListener {
        onClick()
        if (!reduceMotion) {
            SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
                spring.stiffness = SpringForce.STIFFNESS_HIGH
                spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                this@applyClickAnimation.scaleX = 0.93f
                start()
            }
            SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
                spring.stiffness = SpringForce.STIFFNESS_HIGH
                spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                this@applyClickAnimation.scaleY = 0.93f
                start()
            }
        }
    }
}
