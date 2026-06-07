/*
 * PeriodNotificationWorker.kt
 * Responsible for showing a notification to the user about an upcoming class.
 */
package com.shuaib.classmate.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shuaib.classmate.R
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import java.time.LocalDate

class PeriodNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val exception = AcademicCalendarRepository().getActiveExceptionForDate(LocalDate.now())
        if (exception != null && exception.isActive && exception.scope == "ALL_CLASSES") {
            return Result.success()
        }

        val subject = inputData.getString("subject") ?: "Class"
        val teacher = inputData.getString("teacher") ?: "Unknown"

        showNotification(subject, teacher)
        return Result.success()
    }

    private fun showNotification(subject: String, teacher: String) {
        val channelId = "class_reminders"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Class Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle("Upcoming: $subject")
            .setContentText("With $teacher in 10 minutes.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
