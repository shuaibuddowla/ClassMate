package com.shuaib.classmate.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.utils.ShakeDetector
import com.shuaib.classmate.utils.TorchManager

class ShakeToTorchService : Service() {

    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private lateinit var appPrefs: AppPreferences

    companion object {
        private const val NOTIFICATION_ID = 9912
        private const val CHANNEL_ID = "shake_to_torch_channel"
        
        fun start(context: Context) {
            val intent = Intent(context, ShakeToTorchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShakeToTorchService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPrefs = AppPreferences(this)
        
        createNotificationChannel()
        startForegroundService()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector {
            if (appPrefs.isShakeToTorchEnabled()) {
                val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasCameraPermission) {
                    val isOn = TorchManager.toggle(this)
                    val state = if (isOn) "ON" else "OFF"
                    Log.d("ShakeToTorchService", "Flashlight toggled: $state")
                }
            }
        }
        
        registerShakeListener()
    }

    private fun registerShakeListener() {
        if (appPrefs.isShakeToTorchEnabled()) {
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer != null) {
                sensorManager?.registerListener(
                    shakeDetector,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerShakeListener()
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(shakeDetector)
        TorchManager.turnOff(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Shake to Flashlight Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs in background to detect phone shakes for flashlight control"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shake to Flashlight Active")
            .setContentText("Shake your phone to toggle the flashlight.")
            .setSmallIcon(R.drawable.ic_flash)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
