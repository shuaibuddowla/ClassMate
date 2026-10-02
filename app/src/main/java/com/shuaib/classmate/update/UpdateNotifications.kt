package com.shuaib.classmate.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object UpdateNotifications {
    const val CHANNEL_ID = "classmate_app_updates"
    private const val NOTIFICATION_ID = 3901

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
    }

    fun show(context: Context, title: String, message: String, action: String? = null,
             confirmation: Intent? = null, progress: Int? = null, notificationId: Int = NOTIFICATION_ID) {
        if (!canNotify(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(message)
            .setOnlyAlertOnce(true).setAutoCancel(progress == null)
        if (progress != null) builder.setProgress(100, progress, false).setOngoing(true)
        if (action != null) {
            val intent = Intent(context, UpdateActionActivity::class.java).apply {
                this.action = action
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (confirmation != null) putExtra("confirmation", confirmation)
            }
            val pending = PendingIntent.getActivity(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(pending)
        }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
