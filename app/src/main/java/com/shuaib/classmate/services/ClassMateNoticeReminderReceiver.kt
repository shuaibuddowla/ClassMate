package com.shuaib.classmate.services

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.AppPreferences
import java.time.Instant
import java.util.concurrent.TimeUnit

class ClassMateNoticeReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        deliver(context, intent.getStringExtra("notice_id") ?: return,
            intent.getStringExtra("title") ?: "ClassMate notice")
    }

    companion object {
    fun deliver(context: Context, noticeId: String, title: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!AppPreferences(context).isNotificationsEnabled()) return
        val preferences = context.getSharedPreferences("classmate_notice_alarms", Context.MODE_PRIVATE)
        val due = preferences.getLong(noticeId, 0L)
        if (due <= 0L || System.currentTimeMillis() + 5_000L < due) return
        preferences.edit().remove(noticeId).apply()
        val open = PendingIntent.getActivity(context, noticeId.hashCode(),
            Intent(context, ClassMateAuthActivity::class.java).apply {
                putExtra("OPEN_TAB", "notices")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "classmate_notices")
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle("Notice reminder")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(noticeId.hashCode(), notification)
    }
    }
}

class ClassMateNoticeReminderWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val id = inputData.getString("notice_id") ?: return Result.failure()
        ClassMateNoticeReminderReceiver.deliver(applicationContext, id,
            inputData.getString("title") ?: "ClassMate notice")
        return Result.success()
    }
}

object ClassMateNoticeReminderScheduler {
    fun schedule(context: Context, noticeId: String, title: String, at: Instant?) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ClassMateNoticeReminderReceiver::class.java).apply {
            putExtra("notice_id", noticeId)
            putExtra("title", title)
        }
        val pending = PendingIntent.getBroadcast(context, noticeId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(pending)
        val work = WorkManager.getInstance(context)
        val workName = "notice_reminder_$noticeId"
        work.cancelUniqueWork(workName)
        val preferences = context.getSharedPreferences("classmate_notice_alarms", Context.MODE_PRIVATE)
        if (at == null || at.toEpochMilli() <= System.currentTimeMillis()) {
            preferences.edit().remove(noticeId).apply()
            return
        }
        preferences.edit().putLong(noticeId, at.toEpochMilli()).apply()
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms())
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        work.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClassMateNoticeReminderWorker>()
                .setInitialDelay((at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("notice_id" to noticeId, "title" to title))
                .build())
    }

    suspend fun restore(context: Context, profileId: String) {
        val reactions = ClassMateAuthApi.rows("notice_reactions",
            "select=notice_id,reminder_at&profile_id=eq.$profileId&reminder_at=not.is.null")
        for (i in 0 until reactions.length()) {
            val reaction = reactions.getJSONObject(i)
            val at = runCatching { Instant.parse(reaction.getString("reminder_at")) }.getOrNull()
                ?: continue
            if (at <= Instant.now()) continue
            val noticeId = reaction.getString("notice_id")
            val notice = ClassMateAuthApi.rows("notices", "select=title&id=eq.$noticeId&limit=1")
                .optJSONObject(0) ?: continue
            schedule(context, noticeId, notice.optString("title"), at)
        }
    }
}


