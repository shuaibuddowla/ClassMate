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
        if (identity == null || (!release && !blood && identity.optString("role") == "student" && identity.optString("batch_id") != inputData.getString("batch_id"))) {
            try {
                identity = ClassMateAuthApi.initializeProfile()
                ClassMateAuthApi.saveNotificationIdentity(identity)
            } catch (_: Exception) { return if(runAttemptCount<3) Result.retry() else Result.failure() }
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
        val channel = if(matched) "classmate_blood_emergency" else if(release) UpdateNotifications.CHANNEL_ID else "classmate_notifications"
        if(matched && Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(android.app.NotificationChannel(channel,"Verified blood requests",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Urgent blood requests matching your profile"
                enableVibration(true)
                setSound(android.net.Uri.parse("android.resource://${context.packageName}/raw/blood_alert"),android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            })
        }
        val state = when {
            !AppPreferences(context).isNotificationsEnabled() -> "app_notifications_disabled"
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> "notifications_disabled"
            Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE -> "channel_disabled"
            else -> "displayed"
        }
        val eventKey=inputData.getString("event_id") ?: inputData.getString("record_id")
        val ledger=ClassMatePushLedger(context,identity.optString("id"))
        if (state == "displayed" && (eventKey==null || !ledger.contains(eventKey))) {
            if(release) {
                UpdateNotifications.show(context, inputData.getString("title") ?: "ClassMate update",
                    "Tap to check and install the latest update.", UpdateActionActivity.ACTION_RETRY, notificationId = 3902)
            } else {
                val key = inputData.getString("record_id") ?: id.toString()
                val intent = if(blood) Intent(context,com.shuaib.classmate.activities.ClassMateBloodActivity::class.java).putExtra("request_id",key)
                    else Intent(context,ClassMateAuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("OPEN_TAB","notices")
                val pending = PendingIntent.getActivity(context,key.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val body = inputData.getString("body") ?: "New class update available"
                val builder = NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_classmate_notification)
                    .setColor(0xFF3B82F6.toInt()).setContentTitle(inputData.getString("title") ?: "ClassMate update")
                    .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
                try { manager.notify(key.hashCode(),notification) } catch (_: SecurityException) { return Result.failure() }
            }
        }
        if(state=="displayed" && eventKey!=null) ledger.record(eventKey)
        // Automatic checks remain useful even when Android notifications are disabled.
        if(release) UpdateCoordinator.enqueueReleaseCheck(context)
        if(!release) com.shuaib.classmate.activities.ClassMateUnreadActivity.changed()
        val event = inputData.getString("event_id")
        val hash = inputData.getString("token_hash")
        if (event != null && hash != null) {
            try {
                ClassMateAuthApi.rpcText("report_notification_receipt",JSONObject().put("target_event",event)
                    .put("target_hash",hash).put("target_state",state).put("target_version",BuildConfig.VERSION_CODE))
            } catch (_: Exception) { return if(runAttemptCount<3) Result.retry() else Result.failure() }
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, values: Map<String,String>) {
            // Only known, bounded fields enter WorkManager's persisted Data (10 KB limit).
            val fields = listOf("project_ref","recipient_id","batch_id","kind","record_id","event_id","token_hash","version_code","version_name","title","body","blood_match","expires_at")
            val data = Data.Builder().apply { fields.forEach { key -> values[key]?.let { putString(key,it.take(500)) } } }.build()
            val work = OneTimeWorkRequestBuilder<ClassMatePushWorker>().setInputData(data)
                .apply { if(Build.VERSION.SDK_INT>=31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }.build()
            val key = values["event_id"] ?: values["record_id"] ?: java.util.UUID.randomUUID().toString()
            WorkManager.getInstance(context).enqueueUniqueWork("ClassMatePush:$key:${values["recipient_id"]}",ExistingWorkPolicy.KEEP,work)
        }
    }
}
