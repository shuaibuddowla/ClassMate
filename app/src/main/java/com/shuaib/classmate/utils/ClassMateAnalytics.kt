package com.shuaib.classmate.utils

import android.os.Bundle
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

object ClassMateAnalytics {
    private const val TAG = "ClassMateAnalytics"

    private val analytics: FirebaseAnalytics? by lazy {
        runCatching { Firebase.analytics }.getOrNull()
    }

    /**
     * Logs a screen view event to Firebase Analytics.
     * @param screenName Descriptive name of the screen (e.g. "Timetable", "Notices", "UpcomingAssessments")
     * @param screenClass Class or category of the screen component (defaults to "MainActivity")
     */
    fun logScreen(screenName: String, screenClass: String = "MainActivity") {
        runCatching {
            val bundle = Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
            }
            analytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
            Log.d(TAG, "ScreenView logged: $screenName ($screenClass)")
        }.onFailure { e ->
            Log.w(TAG, "Failed to log screen view: ${e.message}")
        }
    }

    /**
     * Sets the user's selected batch as a user property to allow segmenting/filtering
     * analytics reports in the Firebase Console by batch.
     */
    fun setUserBatch(batchLabel: String?) {
        if (batchLabel.isNullOrBlank()) return
        runCatching {
            analytics?.setUserProperty("selected_batch", batchLabel.trim())
            Log.d(TAG, "User property selected_batch set: $batchLabel")
        }.onFailure { e ->
            Log.w(TAG, "Failed to set user property: ${e.message}")
        }
    }

    /**
     * Logs a general custom event with optional key-value parameters.
     */
    fun logEvent(eventName: String, params: Map<String, Any?> = emptyMap()) {
        runCatching {
            val bundle = Bundle()
            for ((key, value) in params) {
                when (value) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is Double -> bundle.putDouble(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    null -> {}
                    else -> bundle.putString(key, value.toString())
                }
            }
            analytics?.logEvent(eventName, bundle)
            Log.d(TAG, "Event logged: $eventName -> $params")
        }.onFailure { e ->
            Log.w(TAG, "Failed to log event $eventName: ${e.message}")
        }
    }
}
