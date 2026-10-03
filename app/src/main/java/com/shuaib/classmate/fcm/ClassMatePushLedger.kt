package com.shuaib.classmate.fcm

import android.content.Context
import com.shuaib.classmate.BuildConfig
import java.util.concurrent.TimeUnit

/** Display history survives WorkManager completion and process recreation. */
internal class ClassMatePushLedger(context: Context,profileId: String) {
    private val prefs=context.getSharedPreferences("classmate_push_seen_${BuildConfig.CLASSMATE_ENV}_$profileId",Context.MODE_PRIVATE)
    fun contains(event: String): Boolean=prefs.contains(event)
    fun record(event: String) {
        val cutoff=System.currentTimeMillis()-TimeUnit.DAYS.toMillis(90)
        val editor=prefs.edit().putLong(event,System.currentTimeMillis())
        prefs.all.forEach { (key,value) -> if(value is Long && value<cutoff) editor.remove(key) }
        editor.commit() // The background worker finishes only after display history is durable.
    }
}
