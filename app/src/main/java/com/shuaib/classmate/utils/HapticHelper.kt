package com.shuaib.classmate.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

object HapticHelper {

    /**
     * Subtle, tactile tick for tabs, filter chips, and selector pills.
     */
    fun selectionTick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /**
     * Light pop for small actions like Liking, Pinning, or Toggling switches.
     */
    fun lightPop(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.GESTURE_END
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        )
    }

    /**
     * Medium thud for main actions like clicking a card or opening a detail page.
     */
    fun mediumThud(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Heavy click for high-impact actions like voting or deleting.
     */
    fun heavyClick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Success vibration for when a process completes (e.g., notice posted, attendance recorded, fund added).
     */
    fun success(view: View) {
        success(view.context)
    }

    fun success(context: Context) {
        try {
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 35, 75, 45), -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (_: Exception) {
            // Ignore if vibration permissions are restricted
        }
    }

    /**
     * Warning / Error vibration for input validation, network errors, or duplicate scans.
     */
    fun warning(view: View) {
        warning(view.context)
    }

    fun warning(context: Context) {
        try {
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 60), -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60)
            }
        } catch (_: Exception) {
            // Ignore if vibration permissions are restricted
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
