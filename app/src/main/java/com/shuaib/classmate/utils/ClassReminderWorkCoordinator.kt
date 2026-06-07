package com.shuaib.classmate.utils

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.workers.DailySchedulerWorker
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.Calendar

object ClassReminderWorkCoordinator {
    const val CLASS_REMINDER_TAG = "class_reminder"
    private const val DAILY_REFRESH_WORK = "RefreshClassRemindersAfterCalendarChange"

    suspend fun cancelTodayClassReminders(context: Context, date: LocalDate = LocalDate.now()) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(CLASS_REMINDER_TAG)

        val dayName = dayNameFor(date) ?: return
        val periods = FirebaseFirestore.getInstance()
            .collection("timetable")
            .document(dayName)
            .collection("periods")
            .get()
            .await()
            .documents
            .mapNotNull { doc -> doc.toObject(Period::class.java)?.copy(id = doc.id) }

        periods.forEach { period ->
            if (period.id.isNotBlank()) {
                workManager.cancelUniqueWork(uniqueReminderName(period.id))
            }
        }
    }

    fun refreshTodayClassReminders(context: Context) {
        val request = OneTimeWorkRequestBuilder<DailySchedulerWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DAILY_REFRESH_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun uniqueReminderName(periodId: String): String = "notification_$periodId"

    fun dayNameFor(date: LocalDate): String? {
        return when (date.dayOfWeek.value) {
            6 -> "saturday"
            7 -> "sunday"
            1 -> "monday"
            2 -> "tuesday"
            3 -> "wednesday"
            4 -> "thursday"
            5 -> "friday"
            else -> null
        }
    }

    fun todayDayNameFromCalendar(): String? {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            else -> null
        }
    }
}
