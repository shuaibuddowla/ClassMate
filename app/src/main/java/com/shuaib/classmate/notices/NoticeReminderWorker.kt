package com.shuaib.classmate.notices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity

class NoticeReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val noticeId = inputData.getString("noticeId").orEmpty()
        val title = inputData.getString("title").orEmpty().ifBlank { "Notice reminder" }
        val body = inputData.getString("body").orEmpty()
        
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null && noticeId.isNotBlank()) {
            val docId = "${noticeId}_$userId"
            FirebaseFirestore.getInstance()
                .collection("user_notice_reminders")
                .document(docId)
                .update("isDone", true)
        }

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Notice reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra("noticeId", noticeId)
            .putExtra("OPEN_TAB", "notices")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(applicationContext, noticeId.hashCode(), intent, flags)
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        manager.notify(noticeId.hashCode(), notification)
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "notice_reminders"
    }
}
