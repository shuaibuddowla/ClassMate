package com.shuaib.classmate.utils

import android.content.Context
import com.google.firebase.Timestamp

object NoticeReadTracker {
    private const val PREFS = "notice_read_state"
    private const val KEY_LAST_READ_MS = "last_notice_read_ms"

    fun lastReadMillis(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_READ_MS, 0L)
    }

    fun markReadNow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_READ_MS, System.currentTimeMillis())
            .apply()
    }

    fun isUnread(context: Context, timestamp: Timestamp?): Boolean {
        val noticeMillis = timestamp?.toDate()?.time ?: return false
        return noticeMillis > lastReadMillis(context)
    }
}
