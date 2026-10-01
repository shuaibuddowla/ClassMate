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
import com.google.android.gms.auth.api.signin.GoogleSignIn
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
            if (GoogleSignIn.getLastSignedInAccount(this) == null) return
            val kind = remoteMessage.data["kind"] ?: "update"
            sendNotification("ClassMate update", "New $kind available", kind)
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
        // Intent to open MainActivity
        val target = if (BuildConfig.CLASSMATE_AUTH_ENABLED) ClassMateAuthActivity::class.java
            else MainActivity::class.java
        val intent = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("OPEN_TAB", "notices")
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    override fun onNewToken(token: String) {
        if (BuildConfig.CLASSMATE_AUTH_ENABLED && ClassMateAuthApi.accessToken != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { ClassMateAuthApi.registerDeviceToken(token) }
                    .onFailure { Log.w("FCM", "Staging token registration failed", it) }
            }
        }
    }
}
