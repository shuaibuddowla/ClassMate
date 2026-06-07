package com.shuaib.classmate.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R

object AttendanceNotificationWatcher {

    private const val CHANNEL_ID = "attendance_absent_alerts"
    private const val CHANNEL_NAME = "Attendance Alerts"
    private const val MY_ATTENDANCE_ACTIVITY = "com.shuaib.classmate.activities.MyAttendanceActivity"

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var queueListener: ListenerRegistration? = null
    private var activeUid: String? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        val auth = FirebaseAuth.getInstance()

        authListener?.let { auth.removeAuthStateListener(it) }
        authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            if (uid == null) {
                stopListening()
                return@AuthStateListener
            }

            if (uid != activeUid) {
                stopListening()
                checkStudentAndListen(appContext, uid)
            }
        }

        auth.addAuthStateListener(authListener!!)
    }

    private fun checkStudentAndListen(context: Context, uid: String) {
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                if (role == "student") {
                    activeUid = uid
                    listenForAbsentNotifications(context, uid)
                }
            }
    }

    private fun listenForAbsentNotifications(context: Context, uid: String) {
        val db = FirebaseFirestore.getInstance()
        queueListener = db.collection("notification_queue")
            .whereEqualTo("studentUid", uid)
            .whereEqualTo("sent", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                snapshot.documentChanges
                    .filter { it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED }
                    .forEach { change ->
                        val doc = change.document
                        val subject = doc.getString("subject") ?: "Attendance"
                        val studentUid = doc.getString("studentUid") ?: return@forEach
                        if (studentUid != uid) return@forEach

                        showAbsentNotification(
                            context = context,
                            subject = subject,
                            sessionId = doc.getString("sessionId").orEmpty()
                        )

                        doc.reference.update("sent", true)
                    }
            }
    }

    private fun showAbsentNotification(
        context: Context,
        subject: String,
        sessionId: String
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when you are marked absent from attendance."
                enableLights(true)
                lightColor = Color.parseColor("#FF4D6D")
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent().apply {
            setClassName(context.packageName, MY_ATTENDANCE_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sessionId", sessionId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle("Attendance Missed")
            .setContentText("You were marked absent in $subject")
            .setColor(Color.parseColor("#FF4D6D"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun stopListening() {
        queueListener?.remove()
        queueListener = null
        activeUid = null
    }
}
