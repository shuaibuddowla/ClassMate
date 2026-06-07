package com.shuaib.classmate.ui

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.shuaib.classmate.utils.AnimUtils

/**
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/ui/BottomNavAnimator.kt
 */
object BottomNavAnimator {
    fun animateTabSelect(view: View) {
        if (AnimUtils.isReduceMotionEnabled(view.context)) return

        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(90L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(110L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }
}
