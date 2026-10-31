package com.shuaib.classmate.transit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity

object ClassMateTransitManager {

    const val CHANNEL_ID = "transit_departure_alerts"
    private const val PREFS_NAME = "classmate_transit_prefs"
    private const val KEY_ALERT_PREFIX = "departure_alert_"

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MBSTU Bus Departure Alerts"
            val descriptionText = "Notifications when MBSTU buses leave campus or start route"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun isAlertEnabled(context: Context, busCode: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ALERT_PREFIX + busCode, true)
    }

    fun setAlertEnabled(context: Context, busCode: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ALERT_PREFIX + busCode, enabled).apply()
    }

    fun showDepartureNotification(
        context: Context,
        busName: String,
        busCode: String,
        destination: String,
        speed: Int
    ) {
        if (!isAlertEnabled(context, busCode)) return

        initNotificationChannel(context)

        val intent = Intent(context, ClassMateAuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "transit")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            busCode.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus_filled)
            .setContentTitle("Campus Departure: $busName ($busCode)")
            .setContentText("Bus has departed Santosh Campus stand heading towards $destination ($speed km/h).")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Bus has departed Santosh Campus stand heading towards $destination. Current speed is $speed km/h. Tap to track on radar.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(busCode.hashCode(), notification)
        } catch (_: SecurityException) {
            // Android 13 notification permission guard
        }
    }

    const val PROXIMITY_CHANNEL_ID = "transit_proximity_alerts"
    private const val KEY_PROXIMITY_ALERT = "proximity_alert_enabled"

    fun isProximityAlertEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PROXIMITY_ALERT, true)
    }

    fun setProximityAlertEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PROXIMITY_ALERT, enabled).apply()
    }

    fun initProximityChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "বাসের নিকটবর্তী আগমন অ্যালার্ট"
            val descriptionText = "ব্যবহারকারীর নিকটবর্তী স্টপেজে বাস পৌঁছানোর পূর্বে নোটিফিকেশন ও অ্যালার্ট"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(PROXIMITY_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProximityNotification(
        context: Context,
        busName: String,
        stopName: String,
        etaMinutes: Int,
        distanceMeters: Int
    ) {
        if (!isProximityAlertEnabled(context)) return

        initProximityChannel(context)

        val intent = Intent(context, ClassMateAuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "transit")
        }

        val notifId = (busName + stopName).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = "🔔 $busName আপনার কাছাকাছি আসছে!"
        val distText = if (distanceMeters >= 1000) "${String.format(java.util.Locale.US, "%.1f", distanceMeters / 1000.0)} কিমি" else "$distanceMeters মিটার"
        val body = "$stopName স্টপেজে পৌঁছাতে আনুমানিক $etaMinutes মিনিট সময় লাগবে (দূরত্ব: $distText)। প্রস্তুত থাকুন।"

        val notification = NotificationCompat.Builder(context, PROXIMITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus_filled)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (_: SecurityException) {
            // Android 13 notification permission guard
        }
    }
}

class ClassMateTransitBridge(
    private val context: Context,
    private val onBusSelected: ((String) -> Unit)? = null
) {

    var onBackStateChanged: ((Boolean) -> Unit)? = null
    var onMapReadyListener: (() -> Unit)? = null

    @JavascriptInterface
    fun onMapReady() {
        onMapReadyListener?.invoke()
    }

    @JavascriptInterface
    fun notifyProximityAlert(busName: String, stopName: String, etaMinutes: Int, distanceMeters: Int) {
        ClassMateTransitManager.showProximityNotification(context, busName, stopName, etaMinutes, distanceMeters)
    }

    @JavascriptInterface
    fun isProximityAlertEnabled(): Boolean {
        return ClassMateTransitManager.isProximityAlertEnabled(context)
    }

    @JavascriptInterface
    fun setProximityAlertEnabled(enabled: Boolean) {
        ClassMateTransitManager.setProximityAlertEnabled(context, enabled)
    }

    @JavascriptInterface
    fun setCanGoBack(canGoBack: Boolean) {
        onBackStateChanged?.invoke(canGoBack)
    }

    @JavascriptInterface
    fun dialDriver(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${phoneNumber.trim()}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun setAlarmForBusDeparture(busCode: String, enabled: Boolean) {
        ClassMateTransitManager.setAlertEnabled(context, busCode, enabled)
    }

    @JavascriptInterface
    fun onBusDeparted(busName: String, busCode: String, destination: String, speed: Int) {
        ClassMateTransitManager.showDepartureNotification(context, busName, busCode, destination, speed)
    }

    @JavascriptInterface
    fun vibrateTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(15)
                }
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun notifyBusSelected(busCode: String) {
        onBusSelected?.invoke(busCode)
    }

    @JavascriptInterface
    fun hasLocationPermission(): Boolean {
        return try {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun requestLocationPermission() {
        try {
            if (context is android.app.Activity) {
                context.runOnUiThread {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        context,
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        9901
                    )
                }
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
