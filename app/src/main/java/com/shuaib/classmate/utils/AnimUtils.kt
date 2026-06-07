package com.shuaib.classmate.utils

import android.content.Context
import android.provider.Settings

/**
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/utils/AnimUtils.kt
 * Utility to check system-level animation settings.
 */
object AnimUtils {
    fun isReduceMotionEnabled(context: Context): Boolean {
        return Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}
