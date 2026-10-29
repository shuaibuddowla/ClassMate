package com.shuaib.classmate.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.shuaib.classmate.R

/**
 * Utility to check system-level animation settings and handle modern activity transitions.
 */
object AnimUtils {
    fun isReduceMotionEnabled(context: Context): Boolean {
        return Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/**
 * Overrides the opening activity transition using modern Android 14+ [Activity.overrideActivityTransition]
 * with graceful fallback to [Activity.overridePendingTransition] on earlier APIs.
 */
fun Activity.overrideOpenTransition(
    enterAnim: Int = R.anim.slide_in_right,
    exitAnim: Int = R.anim.slide_out_left
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}

/**
 * Overrides the closing activity transition using modern Android 14+ [Activity.overrideActivityTransition]
 * with graceful fallback to [Activity.overridePendingTransition] on earlier APIs.
 */
fun Activity.overrideCloseTransition(
    enterAnim: Int = R.anim.slide_in_left,
    exitAnim: Int = R.anim.slide_out_right
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}

/**
 * Sets up standard backward navigation callback using [OnBackPressedCallback],
 * ensuring compatibility with modern Android 13/14+ predictive back gestures.
 */
fun ComponentActivity.setupStandardBackTransition(
    enterAnim: Int = R.anim.slide_in_left,
    exitAnim: Int = R.anim.slide_out_right,
    onBack: (() -> Unit)? = null
) {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (onBack != null) {
                onBack()
            } else {
                finish()
                overrideCloseTransition(enterAnim, exitAnim)
            }
        }
    })
}
