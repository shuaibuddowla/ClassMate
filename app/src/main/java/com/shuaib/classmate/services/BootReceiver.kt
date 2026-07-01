package com.shuaib.classmate.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shuaib.classmate.utils.AppPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = AppPreferences(context)
            if (prefs.isAutoMuteEnabled()) {
                Log.d(TAG, "Auto-mute is enabled. Rescheduling mute/unmute alarms after boot.")
                AutoMuteScheduler.scheduleAlarms(context)
            } else {
                Log.d(TAG, "Auto-mute is disabled. No alarms rescheduled after boot.")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
