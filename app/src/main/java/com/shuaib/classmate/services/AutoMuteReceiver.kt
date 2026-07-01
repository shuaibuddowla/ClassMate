package com.shuaib.classmate.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.shuaib.classmate.utils.AppPreferences

class AutoMuteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val prefs = AppPreferences(context)

        // Make sure auto-mute is still enabled in preferences
        if (!prefs.isAutoMuteEnabled()) {
            Log.d(TAG, "Auto-mute is disabled. Skipping broadcast action: $action")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        when (action) {
            ACTION_MUTE -> {
                val currentMode = audioManager.ringerMode
                Log.d(TAG, "Received ACTION_MUTE. Current ringer mode: $currentMode")

                // Only save the mode if it is not already silent/vibrate to avoid saving a muted state
                if (currentMode == AudioManager.RINGER_MODE_NORMAL) {
                    prefs.setSavedRingerMode(currentMode)
                    Log.d(TAG, "Saved ringer mode: $currentMode")
                }

                // Check if notification policy access is granted to allow RINGER_MODE_SILENT
                val hasPolicyAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    notificationManager?.isNotificationPolicyAccessGranted == true
                } else {
                    true
                }

                if (hasPolicyAccess) {
                    try {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                        Log.d(TAG, "Phone set to SILENT mode successfully.")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException setting silent mode. Falling back to VIBRATE.", e)
                        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    }
                } else {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    Log.d(TAG, "No notification policy access. Set phone to VIBRATE mode fallback.")
                }
            }

            ACTION_UNMUTE -> {
                val savedMode = prefs.getSavedRingerMode()
                Log.d(TAG, "Received ACTION_UNMUTE. Restoring ringer mode to: $savedMode")
                try {
                    audioManager.ringerMode = savedMode
                    Log.d(TAG, "Ringer mode restored successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore ringer mode", e)
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            }
        }
    }

    companion object {
        private const val TAG = "AutoMuteReceiver"
        const val ACTION_MUTE = "com.shuaib.classmate.ACTION_MUTE"
        const val ACTION_UNMUTE = "com.shuaib.classmate.ACTION_UNMUTE"
    }
}
