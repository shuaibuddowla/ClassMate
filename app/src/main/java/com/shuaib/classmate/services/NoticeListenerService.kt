// C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/services/NoticeListenerService.kt
package com.shuaib.classmate.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import com.shuaib.classmate.R
import com.shuaib.classmate.utils.NotificationHelper

class NoticeListenerService : Service() {

    private var listenerRegistration: ListenerRegistration? = null
    private var lastNoticeTimestamp: Timestamp? = null
    private var isFirstLoad = true

    companion object {
        private const val CHANNEL_ID = "notice_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startListening()
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(CHANNEL_ID, "Notice Listener Service")
        } else {
            ""
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ClassMate is running")
            .setContentText("Listening for new notices...")
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun startListening() {
        val db = FirebaseFirestore.getInstance()
        val noticesRef = db.collection("notices")
            .orderBy("timestamp", Query.Direction.DESCENDING)

        listenerRegistration = noticesRef.addSnapshotListener { snapshots, e ->
            if (e != null || snapshots == null) return@addSnapshotListener

            for (dc in snapshots.documentChanges) {
                if (dc.type == DocumentChange.Type.ADDED) {
                    val timestamp = dc.document.getTimestamp("timestamp")
                    val title = dc.document.getString("title") ?: "New Notice"
                    val body = dc.document.getString("body") ?: ""
                    val isCancel = dc.document.getBoolean("isCancel") ?: false

                    if (isFirstLoad) {
                        // On first load, just update the latest timestamp
                        if (lastNoticeTimestamp == null || (timestamp != null && timestamp > lastNoticeTimestamp!!)) {
                            lastNoticeTimestamp = timestamp
                        }
                    } else {
                        // For subsequent additions, check if it's newer
                        if (timestamp != null && (lastNoticeTimestamp == null || timestamp > lastNoticeTimestamp!!)) {
                            NotificationHelper.showNotification(this, title, body, isCancel)
                            lastNoticeTimestamp = timestamp
                        }
                    }
                }
            }
            isFirstLoad = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        listenerRegistration?.remove()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
