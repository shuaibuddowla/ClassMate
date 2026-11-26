package com.shuaib.classmate.services

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.models.AssessmentType
import com.shuaib.classmate.utils.AppPreferences
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ClassMateAssessmentReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val assessmentId = intent.getStringExtra("assessment_id") ?: return
        val batchId = intent.getStringExtra("batch_id") ?: ""
        val reminderType = intent.getStringExtra("reminder_type") ?: "24h"
        val typeName = intent.getStringExtra("type_name") ?: "Assessment"
        val courseCode = intent.getStringExtra("course_code") ?: ""
        val courseTitle = intent.getStringExtra("course_title") ?: ""
        val title = intent.getStringExtra("title") ?: "Assessment"
        val timeStr = intent.getStringExtra("time_str") ?: ""
        val room = intent.getStringExtra("room") ?: ""

        deliver(
            context = context,
            assessmentId = assessmentId,
            batchId = batchId,
            reminderType = reminderType,
            typeName = typeName,
            courseCode = courseCode,
            courseTitle = courseTitle,
            title = title,
            timeStr = timeStr,
            room = room
        )
    }

    companion object {
        private const val TAG = "AssessmentReminder"
        const val PREFS_NAME = "classmate_assessment_alarms"
        const val CHANNEL_ID = com.shuaib.classmate.utils.ClassMateNotificationChannels.CHANNEL_ASSESSMENTS

        fun notificationTag(assessmentId: String, reminderType: String): String {
            return "asmt_${assessmentId}_$reminderType"
        }

        fun requestCode(id: String, reminderType: String): Int {
            val suffix = if (reminderType == "24h") 24 else 2
            return ((id.hashCode() * 31) + suffix) and 0x7FFFFFFF
        }

        fun isReminderAlreadyDelivered(context: Context, assessmentId: String, reminderType: String): Boolean {
            if (assessmentId.isBlank()) return false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dedupKey = "delivered_${assessmentId}_$reminderType"
            val lastDelivered = prefs.getLong(dedupKey, 0L)
            val now = System.currentTimeMillis()
            return lastDelivered > 0L && (now - lastDelivered) < 12 * 3600 * 1000L
        }

        fun markReminderDelivered(context: Context, assessmentId: String, reminderType: String) {
            if (assessmentId.isBlank()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dedupKey = "delivered_${assessmentId}_$reminderType"
            prefs.edit().putLong(dedupKey, System.currentTimeMillis()).apply()
        }

        fun deliver(
            context: Context,
            assessmentId: String,
            batchId: String,
            reminderType: String,
            typeName: String,
            courseCode: String,
            courseTitle: String,
            title: String,
            timeStr: String,
            room: String
        ) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Notification permission not granted, suppressing reminder")
                return
            }

            if (!AppPreferences(context).isNotificationsEnabled()) {
                Log.d(TAG, "Notifications disabled in user preferences")
                return
            }

            // Strict Batch Isolation check
            val activeBatch = ClassMateAuthApi.notificationIdentity()?.optString("batch_id")
            if (!batchId.isNullOrBlank() && !activeBatch.isNullOrBlank() && activeBatch != batchId) {
                Log.d(TAG, "Assessment batch ($batchId) does not match active batch ($activeBatch), suppressing")
                return
            }

            // Deduplication (prevents double alert between FCM Push, AlarmManager, and WorkManager fallback)
            if (isReminderAlreadyDelivered(context, assessmentId, reminderType)) {
                Log.d(TAG, "Reminder for $assessmentId ($reminderType) already delivered recently, skipping")
                return
            }
            markReminderDelivered(context, assessmentId, reminderType)

            val notifTitle = if (reminderType == "24h") {
                if (timeStr.isNotBlank()) "Reminder: $typeName tomorrow at $timeStr" else "Reminder: $typeName tomorrow"
            } else {
                "Upcoming: $typeName in 2 Hours!"
            }

            val locationPart = if (room.isNotBlank()) " in $room" else ""
            val coursePart = if (courseTitle.isNotBlank()) "$courseCode · $courseTitle" else courseCode
            val notifBody = when {
                coursePart.isNotBlank() -> "$coursePart$locationPart. Tap to review details."
                title.isNotBlank() -> "$title$locationPart. Tap to review details."
                else -> "Upcoming assessment deadline approaching. Tap to review details."
            }

            val requestCode = requestCode(assessmentId, reminderType)

            val openIntent = Intent(context, ClassMateAuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_TAB", "upcoming")
                putExtra("EXTRA_ASSESSMENT_ID", assessmentId)
                putExtra("record_id", assessmentId)
            }

            val pending = PendingIntent.getActivity(
                context,
                requestCode,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            com.shuaib.classmate.utils.ClassMateNotificationChannels.ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_classmate_notification)
                .setContentTitle(notifTitle)
                .setContentText(notifBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
                .setSound(com.shuaib.classmate.utils.ClassMateNotificationChannels.soundUri(context))
                .build()

            val notifTag = notificationTag(assessmentId, reminderType)
            NotificationManagerCompat.from(context).notify(notifTag, requestCode, notification)
            Log.d(TAG, "Delivered on-device reminder for $assessmentId ($reminderType)")
        }
    }
}

class ClassMateAssessmentReminderWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    override fun doWork(): Result {
        val assessmentId = inputData.getString("assessment_id") ?: return Result.failure()
        val batchId = inputData.getString("batch_id") ?: ""
        val reminderType = inputData.getString("reminder_type") ?: "24h"
        val typeName = inputData.getString("type_name") ?: "Assessment"
        val courseCode = inputData.getString("course_code") ?: ""
        val courseTitle = inputData.getString("course_title") ?: ""
        val title = inputData.getString("title") ?: "Assessment"
        val timeStr = inputData.getString("time_str") ?: ""
        val room = inputData.getString("room") ?: ""

        ClassMateAssessmentReminderReceiver.deliver(
            context = applicationContext,
            assessmentId = assessmentId,
            batchId = batchId,
            reminderType = reminderType,
            typeName = typeName,
            courseCode = courseCode,
            courseTitle = courseTitle,
            title = title,
            timeStr = timeStr,
            room = room
        )

        return Result.success()
    }
}

object ClassMateAssessmentReminderScheduler {

    private const val TAG = "AssessmentScheduler"

    fun scheduleForAssessment(context: Context, item: AssessmentItem) {
        if (item.id.isBlank() || item.deadlineDate.isBlank() || item.isPast()) {
            cancelForAssessment(context, item.id)
            return
        }

        val ldt = item.getDeadlineDateTime() ?: return
        val zone = runCatching { ZoneId.of("Asia/Dhaka") }.getOrElse { ZoneId.systemDefault() }
        val deadlineMs = ldt.atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val remind24Ms = deadlineMs - 24 * 60 * 60 * 1000L
        val remind2hMs = deadlineMs - 2 * 60 * 60 * 1000L

        scheduleSingle(context, item, "24h", remind24Ms, now)
        scheduleSingle(context, item, "2h", remind2hMs, now)
    }

    private fun scheduleSingle(
        context: Context,
        item: AssessmentItem,
        reminderType: String,
        targetMs: Long,
        now: Long
    ) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val work = WorkManager.getInstance(context)
        val workName = "asmt_rem_${item.id}_$reminderType"
        val requestCode = ClassMateAssessmentReminderReceiver.requestCode(item.id, reminderType)

        val intent = Intent(context, ClassMateAssessmentReminderReceiver::class.java).apply {
            putExtra("assessment_id", item.id)
            putExtra("batch_id", item.batchId)
            putExtra("reminder_type", reminderType)
            putExtra("type_name", item.assessmentType.displayName)
            putExtra("course_code", item.courseCode)
            putExtra("course_title", item.courseTitle)
            putExtra("title", item.title)
            putExtra("time_str", item.deadlineTime)
            putExtra("room", item.getFormattedRoom())
        }

        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel previous pending alarm & work if already scheduled
        manager.cancel(pending)
        work.cancelUniqueWork(workName)

        // If reminder time has already passed, don't schedule an obsolete alarm
        if (targetMs <= now + 15_000L) {
            return
        }

        // If reminder was already delivered (either by FCM push or earlier alarm), don't schedule
        if (ClassMateAssessmentReminderReceiver.isReminderAlreadyDelivered(context, item.id, reminderType)) {
            Log.d(TAG, "Reminder ${item.id} ($reminderType) was already delivered, skipping schedule")
            return
        }

        // 1. AlarmManager exact / inexact scheduling
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pending)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule exact alarm for ${item.id} ($reminderType), relying on WorkManager", e)
        }

        // 2. WorkManager as durable backup
        val delayMs = (targetMs - now).coerceAtLeast(0)
        val workData = workDataOf(
            "assessment_id" to item.id,
            "batch_id" to item.batchId,
            "reminder_type" to reminderType,
            "type_name" to item.assessmentType.displayName,
            "course_code" to item.courseCode,
            "course_title" to item.courseTitle,
            "title" to item.title,
            "time_str" to item.deadlineTime,
            "room" to item.getFormattedRoom()
        )

        val workRequest = OneTimeWorkRequestBuilder<ClassMateAssessmentReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workData)
            .build()

        work.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)
        Log.d(TAG, "Scheduled on-device $reminderType reminder for ${item.id} in ${delayMs / 1000 / 60} minutes")
    }

    fun cancelSingle(context: Context, assessmentId: String, reminderType: String) {
        if (assessmentId.isBlank()) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val work = WorkManager.getInstance(context)
        val workName = "asmt_rem_${assessmentId}_$reminderType"
        val requestCode = ClassMateAssessmentReminderReceiver.requestCode(assessmentId, reminderType)
        val intent = Intent(context, ClassMateAssessmentReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null && manager != null) {
            manager.cancel(pending)
            pending.cancel()
        }
        work.cancelUniqueWork(workName)
        Log.d(TAG, "Cancelled on-device $reminderType reminder for $assessmentId")
    }

    fun cancelForAssessment(context: Context, assessmentId: String) {
        if (assessmentId.isBlank()) return
        val notifManager = NotificationManagerCompat.from(context)
        listOf("24h", "2h").forEach { type ->
            cancelSingle(context, assessmentId, type)
            notifManager.cancel(
                ClassMateAssessmentReminderReceiver.notificationTag(assessmentId, type),
                ClassMateAssessmentReminderReceiver.requestCode(assessmentId, type)
            )
        }
        Log.d(TAG, "Cancelled on-device reminders and notifications for $assessmentId")
    }

    fun rescheduleAll(context: Context, items: List<AssessmentItem>) {
        if (items.isEmpty()) return
        items.forEach { item ->
            scheduleForAssessment(context, item)
        }
    }
}
