// com/shuaib/classmate/utils/AppPreferences.kt
package com.shuaib.classmate.utils

import android.content.Context

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(
        "classmate_prefs",
        Context.MODE_PRIVATE
    )

    fun isAutoUpdateEnabled(): Boolean = prefs.getBoolean("auto_update_enabled", true)
    fun setAutoUpdateEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_update_enabled", enabled).apply()
    fun isWifiOnlyUpdates(): Boolean = prefs.getBoolean("wifi_only_updates", true)
    fun setWifiOnlyUpdates(enabled: Boolean) = prefs.edit().putBoolean("wifi_only_updates", enabled).apply()
    fun lastUpdateCheckTimestamp(): Long = prefs.getLong("last_update_check_timestamp", 0L)
    fun setLastUpdateCheckTimestamp(value: Long) = prefs.edit().putLong("last_update_check_timestamp", value).apply()
    fun lastDownloadedVersionCode(): Long = prefs.getLong("last_downloaded_version_code", 0L)
    fun setLastDownloadedVersionCode(value: Long) = prefs.edit().putLong("last_downloaded_version_code", value).apply()
    fun updateInstallStartedAt(): Long = prefs.getLong("update_install_started_at", 0L)
    fun setUpdateInstallStartedAt(value: Long) = prefs.edit().putLong("update_install_started_at", value).apply()
    fun pendingMandatoryVersionCode(): Long = prefs.getLong("mandatory_update_version_code", 0L)
    fun setPendingMandatoryVersionCode(value: Long) = prefs.edit().putLong("mandatory_update_version_code", value).apply()
    fun needsInstallerConfirmation(): Boolean = prefs.getBoolean("needs_installer_confirmation", false)
    fun setNeedsInstallerConfirmation(value: Boolean) = prefs.edit().putBoolean("needs_installer_confirmation", value).apply()

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
            .putString("theme_mode", if (enabled) "dark" else "light")
            .apply()
    }

    fun getThemeMode(): String =
        prefs.getString("theme_mode", if (prefs.contains("dark_mode")) (if (isDarkMode()) "dark" else "light") else "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit()
            .putString("theme_mode", mode)
            .putBoolean("dark_mode", mode == "dark")
            .apply()
    }

    fun isNoticeReadReceiptsEnabled(): Boolean =
        prefs.getBoolean("notice_read_receipts_enabled", true)

    fun setNoticeReadReceiptsEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("notice_read_receipts_enabled", enabled)
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
