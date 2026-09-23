package com.shuaib.classmate.utils

import android.content.Context
import androidx.work.WorkManager
import java.time.LocalDate

object ClassReminderWorkCoordinator {
    const val CLASS_REMINDER_TAG = "class_reminder"
    private const val DAILY_REFRESH_WORK = "RefreshClassRemindersAfterCalendarChange"

    suspend fun cancelTodayClassReminders(context: Context, date: LocalDate = LocalDate.now()) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(CLASS_REMINDER_TAG)
            workManager.cancelUniqueWork(DAILY_REFRESH_WORK)
        } catch (_: Exception) {}
    }

    fun refreshTodayClassReminders(context: Context) {
        // Reminders removed - cancel any lingering tasks
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(CLASS_REMINDER_TAG)
            workManager.cancelUniqueWork(DAILY_REFRESH_WORK)
        } catch (_: Exception) {}
    }

    fun uniqueReminderName(periodId: String): String = "notification_$periodId"
}
