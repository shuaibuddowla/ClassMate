// com/shuaib/classmate/utils/AppPreferences.kt
package com.shuaib.classmate.utils

import android.content.Context

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(
        "classmate_prefs",
        Context.MODE_PRIVATE
    )

    fun isOnboardingComplete(): Boolean =
        prefs.getBoolean("onboarding_complete", false)

    fun setOnboardingComplete() {
        prefs.edit()
            .putBoolean("onboarding_complete", true)
            .apply()
    }

    fun isDarkMode(): Boolean =
        prefs.getBoolean("dark_mode", true)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean("dark_mode", enabled)
            .apply()
    }

    fun isNotificationsEnabled(): Boolean =
        prefs.getBoolean("notifications_enabled", true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("notifications_enabled", enabled)
            .apply()
    }
}
