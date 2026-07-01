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
        prefs.getBoolean("dark_mode", false)

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

    fun isAutoMuteEnabled(): Boolean =
        prefs.getBoolean("auto_mute_enabled", false)

    fun setAutoMuteEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("auto_mute_enabled", enabled)
            .apply()
    }

    fun isShakeToTorchEnabled(): Boolean =
        prefs.getBoolean("shake_to_torch_enabled", false)

    fun setShakeToTorchEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("shake_to_torch_enabled", enabled)
            .apply()
    }

    fun getSavedRingerMode(): Int =
        prefs.getInt("saved_ringer_mode", 2) // AudioManager.RINGER_MODE_NORMAL is 2

    fun setSavedRingerMode(mode: Int) {
        prefs.edit()
            .putInt("saved_ringer_mode", mode)
            .apply()
    }

    fun isGeminiPrimary(): Boolean =
        prefs.getBoolean("is_gemini_primary", true)

    fun setGeminiPrimary(enabled: Boolean) {
        prefs.edit()
            .putBoolean("is_gemini_primary", enabled)
            .apply()
    }

    fun getGeminiModel(): String =
        prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"

    fun setGeminiModel(model: String) {
        prefs.edit()
            .putString("gemini_model", model)
            .apply()
    }

    fun getGroqModel(): String =
        prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"

    fun setGroqModel(model: String) {
        prefs.edit()
            .putString("groq_model", model)
            .apply()
    }
}
