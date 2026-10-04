package com.shuaib.classmate.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** System haptics respect the user's touch-feedback and accessibility settings. */
internal object ClassMateHaptics {
    fun selection(view: View) {
        view.isHapticFeedbackEnabled=true
        view.performHapticFeedback(if(Build.VERSION.SDK_INT>=30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun hold(view: View) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}
