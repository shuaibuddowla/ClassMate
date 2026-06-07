package com.shuaib.classmate.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.attendance.BleScanner

class BleScannerService : Service() {

    private lateinit var bleScanner: BleScanner

    override fun onCreate() {
        super.onCreate()
        bleScanner = BleScanner(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startScanning()
        return START_STICKY
    }

    private fun startScanning() {
        bleScanner.start(object : BleScanner.Callback {
            override fun onMarkedPresent(sessionId: String) {
                Log.d(TAG, "Student marked present for session $sessionId")
                stopSelf()
            }

            override fun onScanFailed(message: String) {
                Log.e(TAG, message)
                stopSelf()
            }
        })
    }

    private fun buildNotification(): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            CHANNEL_ID
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ClassMate")
            .setContentText("Attendance detection active")
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
                "Attendance Scanner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Detects nearby ClassMate attendance sessions."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    override fun onDestroy() {
        bleScanner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BleScannerService"
        private const val CHANNEL_ID = "attendance_scanner_channel"
        private const val NOTIFICATION_ID = 2002

        fun startIntent(context: Context): Intent {
            return Intent(context, BleScannerService::class.java)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BleScannerService::class.java)
        }
    }
}
