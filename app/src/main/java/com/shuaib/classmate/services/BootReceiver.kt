package com.shuaib.classmate.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = AppPreferences(context)
            if (BuildConfig.CLASSMATE_AUTH_ENABLED) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ClassMateAuthApi.attach(context.applicationContext)
                            if (ClassMateAuthApi.restoreSession()) {
                                val profile = ClassMateAuthApi.initializeProfile()
                                if (prefs.isAutoMuteEnabled() && profile.optString("role") == "student")
                                    ClassMateAutoMuteScheduler.schedule(context, profile.optString("batch_id"))
                                if (prefs.isNotificationsEnabled())
                                    ClassMateNoticeReminderScheduler.restore(context, profile.getString("id"))
                            }
                        } catch (e: Exception) { Log.e(TAG, "Could not restore Supabase alarms", e) }
                        finally { pending.finish() }
                    }
            } else if (prefs.isAutoMuteEnabled()) {
                AutoMuteScheduler.scheduleAlarms(context)
            }

            if (prefs.isShakeToTorchEnabled()) {
                Log.d(TAG, "Shake to Torch is enabled. Starting service after boot.")
                ShakeToTorchService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
