package com.shuaib.classmate.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.update.UpdateActionActivity
import com.shuaib.classmate.update.UpdateCoordinator
import com.shuaib.classmate.update.UpdateNotifications
import com.shuaib.classmate.utils.AppPreferences
import org.json.JSONObject

/** Durable processing survives the short Firebase service callback lifetime. */
class ClassMatePushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        ClassMateAuthApi.attach(context)
        if (!ClassMateAuthApi.hasSavedSession()) return Result.success()
        val expectedProject = android.net.Uri.parse(BuildConfig.CLASSMATE_URL).host?.substringBefore('.')
        if (inputData.getString("project_ref") != expectedProject) return Result.success()
        var identity = ClassMateAuthApi.notificationIdentity()

        val release = inputData.getString("kind") == "app_update"
        val blood = inputData.getString("kind") == "blood_request"
        val fund = inputData.getString("kind") in setOf("batch_fund_deposit", "batch_fund_update", "batch_fund_reminder")
        val attendance = inputData.getString("kind") in setOf("attendance_open", "attendance_marked")
        val assessmentReminder = inputData.getString("kind") == "assessment_reminder"
        if (identity == null || (!release && !blood && identity.optString("role") == "student" && identity.optString("batch_id") != inputData.getString("batch_id"))) {
            try {
                identity = ClassMateAuthApi.initializeProfile()
                if (identity != null) ClassMateAuthApi.saveNotificationIdentity(identity)
            } catch (_: Exception) { return if(runAttemptCount<3) Result.retry() else Result.failure() }
        }
        if (identity == null) return Result.success()

        val isTargetedPersonal = inputData.getString("kind") in setOf(
            "student_ban", "student_ban_lifted",
            "batch_fund_deposit", "batch_fund_update", "batch_fund_reminder",
            "attendance_marked"
        )
        val targetRecipient = inputData.getString("recipient_id")
        if (isTargetedPersonal && !targetRecipient.isNullOrBlank() && targetRecipient != identity.optString("id")) {
            return Result.success()
        }
        if (!ClassMatePushPolicy.addressedToCurrentUser(inputData.getString("project_ref"),inputData.getString("recipient_id"),
                inputData.getString("batch_id"),release || blood,expectedProject,identity.optString("id"),identity.optString("verification_status"),
                identity.optString("role"),identity.optString("batch_id"))) return Result.success()
        if (release && (inputData.getString("version_code")?.toLongOrNull() ?: 0L) <= BuildConfig.VERSION_CODE) return Result.success()
        var bloodDetails:JSONObject?=null
        if(blood) {
            val key=inputData.getString("record_id") ?: return Result.success()
            try {
                if(ClassMateAuthApi.rpcText("blood_alert_allowed",JSONObject().put("target_request",key)).trim()!="true") return Result.success()
                bloodDetails=ClassMateAuthApi.rpc("blood_request_details",JSONObject().put("target_request",key))
            } catch (_:Exception) { return if(runAttemptCount<3) Result.retry() else Result.failure() }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val matched = blood && inputData.getString("blood_match") == "true"
        val channel = when {
            matched -> "classmate_blood_emergency"
            release -> UpdateNotifications.CHANNEL_ID
            assessmentReminder -> "classmate_assessments"
            inputData.getString("kind") == "class_change" -> "classmate_cancellations"
            inputData.getString("kind") == "notice" -> "classmate_notices"
            else -> "classmate_notifications"
        }
        if(matched && Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(android.app.NotificationChannel(channel,"Verified blood requests",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Urgent blood requests matching your profile"
                enableVibration(true)
                setSound(android.net.Uri.parse("android.resource://${context.packageName}/raw/blood_alert"),android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            })
        } else if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            val (name, desc) = when (channel) {
                "classmate_assessments" -> "Upcoming Assessments & Deadlines" to "Reminders for upcoming class tests, quizzes, and assignments"
                "classmate_notices" -> "ClassMate Notices" to "Notifications for ClassMate updates"
                "classmate_cancellations" -> "ClassMate Cancellations" to "Notifications for cancelled classes"
                UpdateNotifications.CHANNEL_ID -> "ClassMate Updates" to "App release announcements"
                else -> "ClassMate Notifications" to "General class schedules, notices, and updates"
            }
            manager.createNotificationChannel(android.app.NotificationChannel(channel, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableVibration(true)
            })
        }
        val state = when {
            !AppPreferences(context).isNotificationsEnabled() -> "app_notifications_disabled"
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> "notifications_disabled"
            Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE -> "channel_disabled"
            else -> "displayed"
        }

        val asmtId = if (assessmentReminder) {
            inputData.getString("assessment_id") ?: inputData.getString("record_id") ?: ""
        } else ""
        val rawReminderType = if (assessmentReminder) {
            inputData.getString("reminder_type")?.trim() ?: ""
        } else ""
        val reminderType = if (assessmentReminder) {
            when {
                rawReminderType.isNotBlank() -> rawReminderType
                (inputData.getString("title") ?: "").contains("Tomorrow", ignoreCase = true) ||
                (inputData.getString("body") ?: "").contains("tomorrow", ignoreCase = true) ||
                (inputData.getString("title") ?: "").contains("24h", ignoreCase = true) -> "24h"
                else -> "2h"
            }
        } else ""

        if (assessmentReminder && asmtId.isNotBlank()) {
            if (com.shuaib.classmate.services.ClassMateAssessmentReminderReceiver.isReminderAlreadyDelivered(context, asmtId, reminderType)) {
                android.util.Log.d("PushWorker", "Assessment reminder for $asmtId ($reminderType) already delivered locally, suppressing duplicate push")
                val event = inputData.getString("event_id")
                val hash = inputData.getString("token_hash")
                if (event != null && hash != null) {
                    runCatching {
                        ClassMateAuthApi.rpcText(
                            "report_notification_receipt",
                            JSONObject().put("target_event", event)
                                .put("target_hash", hash)
                                .put("target_state", "suppressed_duplicate")
                                .put("target_version", BuildConfig.VERSION_CODE)
                        )
                    }
                }
                return Result.success()
            }
        }

        val eventKey = when {
            inputData.getString("event_id") != null -> inputData.getString("event_id")
            assessmentReminder && asmtId.isNotBlank() -> "${asmtId}_$reminderType"
            else -> inputData.getString("record_id")
        }
        val ledger = ClassMatePushLedger(context, identity.optString("id"))
        if (state == "displayed" && (eventKey == null || !ledger.contains(eventKey))) {
            if (release) {
                UpdateNotifications.show(context, inputData.getString("title") ?: "ClassMate update",
                    "Tap to review the latest update.", UpdateActionActivity.ACTION_PROMPT, notificationId = 3902)
            } else {
                if (assessmentReminder && asmtId.isNotBlank()) {
                    com.shuaib.classmate.services.ClassMateAssessmentReminderReceiver.markReminderDelivered(context, asmtId, reminderType)
                    com.shuaib.classmate.services.ClassMateAssessmentReminderScheduler.cancelSingle(context, asmtId, reminderType)
                }
                val key = inputData.getString("record_id") ?: id.toString()
                val targetNoticeId = inputData.getString("record_id")
                    ?: inputData.getString("noticeId")
                    ?: inputData.getString("notice_id")
                val intent = when {
                    attendance -> Intent(context, ClassMateAuthActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("OPEN_TAB", "library")
                        putExtra("OPEN_RFID_ATTENDANCE", true)
                        putExtra("EXTRA_SESSION_ID", inputData.getString("session_id"))
                        putExtra("EXTRA_COURSE_CODE", inputData.getString("course_code"))
                        putExtra("EXTRA_COURSE_TITLE", inputData.getString("course_title"))
                    }
                    blood -> Intent(context,com.shuaib.classmate.activities.ClassMateBloodActivity::class.java).putExtra("request_id",key)
                    fund -> Intent(context,com.shuaib.classmate.activities.ClassMateBatchFundActivity::class.java).apply {
                        putExtra("batch_id", inputData.getString("batch_id") ?: identity.optString("batch_id"))
                        if (inputData.getString("kind") == "batch_fund_reminder") {
                            putExtra("open_payers", true)
                        }
                    }
                    assessmentReminder -> Intent(context, ClassMateAuthActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("OPEN_TAB", "upcoming")
                        val aid = inputData.getString("assessment_id") ?: inputData.getString("record_id")
                        if (!aid.isNullOrBlank()) {
                            putExtra("EXTRA_ASSESSMENT_ID", aid)
                            putExtra("record_id", aid)
                        }
                    }
                    else -> Intent(context,ClassMateAuthActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("OPEN_TAB","notices")
                        if (!targetNoticeId.isNullOrBlank()) {
                            putExtra("TARGET_NOTICE_ID", targetNoticeId)
                            putExtra("noticeId", targetNoticeId)
                            putExtra("record_id", targetNoticeId)
                        }
                    }
                }
                val pending = PendingIntent.getActivity(context,key.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val rawTitle = inputData.getString("title") ?: "ClassMate update"
                val rawBody = inputData.getString("body") ?: "New class update available"
                val formattedTitle = com.shuaib.classmate.activities.ClassMateNoticeText.parseMarkup(context, rawTitle, isForNotification = true)
                val formattedBody = com.shuaib.classmate.activities.ClassMateNoticeText.parseMarkup(context, rawBody, isForNotification = true)
                val batchId = inputData.getString("batch_id") ?: identity.optString("batch_id")
                val groupKey = if (blood) "classmate_group_blood" else "classmate_group_batch_${batchId.ifBlank { "general" }}"
                val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_classmate_notification)
                    .setColor(0xFF3B82F6.toInt()).setContentTitle(formattedTitle)
                    .setContentText(formattedBody).setStyle(NotificationCompat.BigTextStyle().bigText(formattedBody)).setContentIntent(pending)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setGroup(groupKey)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                    .setOnlyAlertOnce(true).setAutoCancel(true)
                if(blood) {
                    builder.setColor(0xFFC43E52.toInt())
                    if(matched) builder.setSound(android.net.Uri.parse("android.resource://${context.packageName}/raw/blood_alert"))
                    val volunteerIntent=Intent(intent).putExtra("volunteer_now",true)
                    val volunteer=PendingIntent.getActivity(context,(key+"donate").hashCode(),volunteerIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    if(bloodDetails?.optBoolean("can_donate")==true) builder.addAction(R.drawable.ic_classmate_notification,"I can donate",volunteer)
                    bloodDetails?.optString("attendant_phone")?.takeIf { it.matches(Regex("\\+[1-9][0-9]{7,14}")) }?.let { phone ->
                        val call=PendingIntent.getActivity(context,(key+"call").hashCode(),Intent(Intent.ACTION_DIAL,android.net.Uri.parse("tel:$phone")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        builder.addAction(R.drawable.ic_classmate_notification,"Call attendant",call)
                    }
                }
                val notification=builder.build()
                try {
                    if (assessmentReminder && asmtId.isNotBlank()) {
                        val notifTag = com.shuaib.classmate.services.ClassMateAssessmentReminderReceiver.notificationTag(asmtId, reminderType)
                        val notifId = com.shuaib.classmate.services.ClassMateAssessmentReminderReceiver.requestCode(asmtId, reminderType)
                        manager.notify(notifTag, notifId, notification)
                    } else {
                        manager.notify(key.hashCode(), notification)
                    }
                    if (!blood && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val summaryNotification = NotificationCompat.Builder(context, channel)
                            .setSmallIcon(R.drawable.ic_classmate_notification)
                            .setColor(0xFF3B82F6.toInt())
                            .setStyle(NotificationCompat.InboxStyle().setSummaryText("ClassMate updates"))
                            .setGroup(groupKey)
                            .setGroupSummary(true)
                            .setAutoCancel(true)
                            .build()
                        manager.notify(groupKey.hashCode(), summaryNotification)
                    }
                    ClassMateInAppPush.notify(
                        InAppNotificationEvent(
                            title = rawTitle,
                            body = rawBody,
                            kind = inputData.getString("kind") ?: "notice",
                            recordId = key,
                            openTab = when {
                                attendance -> "library"
                                blood -> "blood"
                                fund -> "fund"
                                assessmentReminder -> "upcoming"
                                else -> "notices"
                            }
                        )
                    )
                } catch (_: SecurityException) { return Result.failure() }
            }
        }
        if(state=="displayed" && eventKey!=null) ledger.record(eventKey)
        // Automatic checks remain useful even when Android notifications are disabled.
        if(release) UpdateCoordinator.enqueueReleaseCheck(context)
        if(!release) {
            com.shuaib.classmate.activities.ClassMateUnreadActivity.changed()
            val batch = inputData.getString("batch_id") ?: identity.optString("batch_id")
            if (!batch.isNullOrBlank()) {
                runCatching {
                    com.shuaib.classmate.repositories.AssessmentRepository.getInstance(context).refreshFromRemote(batch)
                }
            }
        }
        val event = inputData.getString("event_id")
        val hash = inputData.getString("token_hash")
        if (event != null && hash != null) {
            runCatching {
                ClassMateAuthApi.rpcText("report_notification_receipt",JSONObject().put("target_event",event)
                    .put("target_hash",hash).put("target_state",state).put("target_version",BuildConfig.VERSION_CODE))
            }.onFailure { e ->
                android.util.Log.w("PushWorker", "Receipt report deferred/failed: ${e.message}")
            }
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, values: Map<String,String>) {
            // Only known, bounded fields enter WorkManager's persisted Data (10 KB limit).
            val fields = listOf(
                "project_ref", "recipient_id", "batch_id", "kind", "record_id", "event_id",
                "token_hash", "version_code", "version_name", "title", "body", "blood_match",
                "expires_at", "session_id", "course_code", "course_title", "assessment_id", "routine_slot_id",
                "reminder_type"
            )
            val data = Data.Builder().apply { fields.forEach { key -> values[key]?.let { putString(key,it.take(500)) } } }.build()
            val work = OneTimeWorkRequestBuilder<ClassMatePushWorker>().setInputData(data)
                .apply { if(Build.VERSION.SDK_INT>=31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }.build()
            val key = values["event_id"] ?: values["record_id"] ?: java.util.UUID.randomUUID().toString()
            WorkManager.getInstance(context).enqueueUniqueWork("ClassMatePush:$key:${values["recipient_id"]}",ExistingWorkPolicy.KEEP,work)
        }
    }
}
