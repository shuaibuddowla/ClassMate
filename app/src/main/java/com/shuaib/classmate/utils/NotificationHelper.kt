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

    private const val CHANNEL_NOTICES_ID = "classmate_notices"
    private const val CHANNEL_CANCELLATIONS_ID = "classmate_cancellations"

    fun showNotification(context: Context, title: String, body: String, isCancel: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = if (isCancel) CHANNEL_CANCELLATIONS_ID else CHANNEL_NOTICES_ID
        val color = if (isCancel) Color.RED else Color.parseColor("#3B82F6")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "notices")
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
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
