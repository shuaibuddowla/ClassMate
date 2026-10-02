/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/fcm/MyFirebaseMessagingService.kt
 * Handles incoming FCM messages and displays them as system notifications.
 */
package com.shuaib.classmate.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "From: ${remoteMessage.from}")

        if (BuildConfig.CLASSMATE_AUTH_ENABLED) {
            ClassMateAuthApi.attach(applicationContext)
            if (!ClassMateAuthApi.hasSavedSession() || !com.shuaib.classmate.utils.AppPreferences(this).isNotificationsEnabled()) return
            val identity = ClassMateAuthApi.notificationIdentity() ?: return
            val expectedProject = android.net.Uri.parse(BuildConfig.CLASSMATE_URL).host?.substringBefore('.')
            if (remoteMessage.data["project_ref"] != expectedProject || remoteMessage.data["recipient_id"] != identity.optString("id")) return
            if (identity.optString("verification_status") != "active") return
            if (identity.optString("role") == "student" && remoteMessage.data["batch_id"] != identity.optString("batch_id")) return
            val kind = remoteMessage.data["kind"] ?: "update"
            sendNotification(remoteMessage.data["title"] ?: "ClassMate update", remoteMessage.data["body"] ?: "New $kind available", remoteMessage.data["record_id"] ?: kind)
            return
        }

        // Check if user is logged in
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            Log.d("FCM", "User is logged out, ignoring notification.")
            return
        }

        // If the message is from OneSignal, let OneSignal SDK handle it
        if (remoteMessage.data.containsKey("custom")) {
            Log.d("FCM", "Message is from OneSignal, skipping manual notification.")
            return
        }

        // Handle both notification payload and data payload
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"]
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]
        val type = remoteMessage.data["type"] ?: "notice"

        // Only show notification if we actually have content to show
        if (title != null && body != null) {
            sendNotification(title, body, type)
        }
    }

    private fun sendNotification(title: String, messageBody: String, type: String) {
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        // Intent to open MainActivity
        val target = if (BuildConfig.CLASSMATE_AUTH_ENABLED) ClassMateAuthActivity::class.java
            else MainActivity::class.java
        val intent = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("OPEN_TAB", "notices")
        }

        val pendingIntent = PendingIntent.getActivity(
            this, type.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "classmate_notifications"
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setColor(android.graphics.Color.parseColor("#3B82F6"))
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(type.hashCode(), notificationBuilder.build())
    }

    override fun onNewToken(token: String) {
        ClassMateAuthApi.attach(applicationContext)
        if (BuildConfig.CLASSMATE_AUTH_ENABLED && ClassMateAuthApi.hasSavedSession() &&
            com.shuaib.classmate.utils.AppPreferences(this).isNotificationsEnabled() && androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { ClassMateAuthApi.registerDeviceToken(token) }
                    .onFailure { Log.w("FCM", "Device token registration failed") }
            }
        }
    }
}
