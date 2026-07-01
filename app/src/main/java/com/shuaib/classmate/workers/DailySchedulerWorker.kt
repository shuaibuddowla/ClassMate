/*
 * DailySchedulerWorker.kt
 * Runs once a day to fetch today's timetable and schedule individual notifications
 * for each class session.
 */
package com.shuaib.classmate.workers

import android.content.Context
import androidx.work.*
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.utils.ClassReminderWorkCoordinator
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DailySchedulerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            return Result.success()
        }
        val firestore = FirebaseFirestore.getInstance()
        val calendar = Calendar.getInstance()
        val dayName = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            else -> return Result.success()
        }

        try {
            if (AcademicCalendarRepository(firestore).areAllClassesSuspended(LocalDate.now())) {
                ClassReminderWorkCoordinator.cancelTodayClassReminders(applicationContext)
                return Result.success()
            }

            val snapshot = firestore.collection("timetable").document(dayName)
                .collection("periods")
                .get()
                .await() 

            val periods = snapshot.documents.map { doc ->
                doc.toObject(Period::class.java)?.copy(id = doc.id)
            }.filterNotNull()
            
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            for (period in periods) {
                // Check if the class is cancelled for today
                val isCancelledToday = period.isCancelled || period.cancelDate == todayDate
                
                if (!isCancelledToday) {
                    scheduleNotification(period, dayName)
                }
            }

            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    private fun scheduleNotification(period: Period, dayName: String) {
        val classTime = parseTime(period.startTime) ?: return
        val now = Calendar.getInstance()
        
        // Target time is 10 minutes before class
        val targetTime = (classTime.clone() as Calendar).apply {
            add(Calendar.MINUTE, -10)
        }

        if (targetTime.after(now)) {
            val delay = targetTime.timeInMillis - now.timeInMillis
            
            val data = workDataOf(
                "id" to period.id,
                "day" to dayName,
                "subject" to period.subject,
                "teacher" to period.teacher
            )

            val notificationWork = OneTimeWorkRequestBuilder<PeriodNotificationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(ClassReminderWorkCoordinator.CLASS_REMINDER_TAG)
                .addTag("period_${period.id}")
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                ClassReminderWorkCoordinator.uniqueReminderName(period.id),
                ExistingWorkPolicy.REPLACE,
                notificationWork
            )
        }
    }

    private fun parseTime(timeStr: String): Calendar? {
        return try {
            val parts = timeStr.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
        } catch (e: Exception) {
            null
        }
    }
}
