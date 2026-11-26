// C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/utils/NotificationHelper.kt
package com.shuaib.classmate.utils

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.R

object NotificationHelper {

    private const val CHANNEL_NOTICES_ID = ClassMateNotificationChannels.CHANNEL_NOTICES
    private const val CHANNEL_CANCELLATIONS_ID = ClassMateNotificationChannels.CHANNEL_CANCELLATIONS
    private const val CHANNEL_GENERAL_ID = ClassMateNotificationChannels.CHANNEL_GENERAL

    fun showFundSyncNotification(context: Context, title: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ClassMateNotificationChannels.ensureChannels(context)

        val targetClass = if (com.shuaib.classmate.BuildConfig.CLASSMATE_AUTH_ENABLED) {
            com.shuaib.classmate.activities.ClassMateBatchFundActivity::class.java
        } else MainActivity::class.java

        val intent = Intent(context, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL_ID)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(Color.parseColor("#10B981"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    fun showNotification(context: Context, title: String, body: String, isCancel: Boolean, noticeId: String? = null) {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            return
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = if (isCancel) CHANNEL_CANCELLATIONS_ID else CHANNEL_NOTICES_ID
        val color = if (isCancel) Color.RED else Color.parseColor("#3B82F6")

        val targetClass = if (com.shuaib.classmate.BuildConfig.CLASSMATE_AUTH_ENABLED) com.shuaib.classmate.activities.ClassMateAuthActivity::class.java else MainActivity::class.java
        val intent = Intent(context, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_TAB", "notices")
            if (!noticeId.isNullOrBlank()) {
                putExtra("TARGET_NOTICE_ID", noticeId)
                putExtra("noticeId", noticeId)
                putExtra("record_id", noticeId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(color)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(ClassMateNotificationChannels.soundUri(context))
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
