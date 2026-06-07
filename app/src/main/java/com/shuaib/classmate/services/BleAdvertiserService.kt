package com.shuaib.classmate.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.attendance.BleAdvertiser

class BleAdvertiserService : Service() {

    private lateinit var bleAdvertiser: BleAdvertiser
    private val handler = Handler(Looper.getMainLooper())
    private var currentSessionId: String? = null
    private var currentClassId: String = BleAdvertiser.CLASS_ID
    private var currentTimestamp: Long = 0L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val sessionId = currentSessionId
            if (sessionId.isNullOrBlank()) return

            Log.d(TAG, "Refreshing BLE advertiser for sessionId: $sessionId")
            bleAdvertiser.stop()
            startAdvertising(
                sessionId = sessionId,
                classId = currentClassId,
                timestamp = currentTimestamp,
                scheduleRefresh = true
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleAdvertiserService onCreate()")
        bleAdvertiser = BleAdvertiser(this)
        startForeground(NOTIFICATION_ID, buildNotification("Preparing attendance broadcast..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleAdvertiserService onStartCommand()")
        when (intent?.action) {
            ACTION_START -> startAdvertising(intent)
            ACTION_STOP -> {
                handler.removeCallbacks(refreshRunnable)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun startAdvertising(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val classId = intent.getStringExtra(EXTRA_CLASS_ID) ?: BleAdvertiser.CLASS_ID
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        Log.d(TAG, "sessionId received: $sessionId")

        if (sessionId.isNullOrBlank()) {
            Log.e(TAG, "Missing session id for BLE attendance advertising.")
            stopSelf()
            return
        }

        startAdvertising(
            sessionId = sessionId,
            classId = classId,
            timestamp = timestamp,
            scheduleRefresh = true
        )
    }

    private fun startAdvertising(
        sessionId: String,
        classId: String,
        timestamp: Long,
        scheduleRefresh: Boolean
    ) {
        currentSessionId = sessionId
        currentClassId = classId
        currentTimestamp = timestamp

        val payload = BleAdvertiser.Payload(
            sessionId = sessionId,
            classId = classId,
            timestamp = timestamp
        )

        bleAdvertiser.start(payload, object : BleAdvertiser.Callback {
            override fun onStarted() {
                Log.d(TAG, "BLE attendance advertising started for $sessionId")
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Broadcasting attendance for $classId")
                )
                if (scheduleRefresh) {
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, ADVERTISER_REFRESH_MS)
                }
            }

            override fun onFailed(message: String) {
                Log.e(TAG, message)
                stopSelf()
            }
        })
    }

    private fun buildNotification(content: String): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            CHANNEL_ID
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ClassMate Attendance")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Attendance",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth attendance broadcasting active."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    override fun onDestroy() {
        Log.d(TAG, "BleAdvertiserService onDestroy()")
        handler.removeCallbacks(refreshRunnable)
        bleAdvertiser.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BleAdvertiser"
        private const val CHANNEL_ID = "ble_attendance_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ADVERTISER_REFRESH_MS = 30_000L

        const val ACTION_START = "com.shuaib.classmate.attendance.START_BLE_ADVERTISING"
        const val ACTION_STOP = "com.shuaib.classmate.attendance.STOP_BLE_ADVERTISING"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_CLASS_ID = "extra_class_id"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        fun startIntent(
            context: Context,
            sessionId: String,
            classId: String,
            timestamp: Long
        ): Intent {
            return Intent(context, BleAdvertiserService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CLASS_ID, classId)
                putExtra(EXTRA_TIMESTAMP, timestamp)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BleAdvertiserService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
