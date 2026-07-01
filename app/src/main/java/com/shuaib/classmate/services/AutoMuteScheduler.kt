package com.shuaib.classmate.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.utils.DateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar

object AutoMuteScheduler {
    private const val TAG = "AutoMuteScheduler"
    private val calendarRepository = AcademicCalendarRepository()

    fun scheduleAlarms(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                cancelAllAlarms(context)

                if (!prefs.isAutoMuteEnabled()) {
                    Log.d(TAG, "Auto-mute is disabled. Cancelled all scheduled mute/unmute alarms.")
                    return@launch
                }

                // Check for today's academic calendar exceptions (holidays/cancellations)
                val todayDate = try { LocalDate.now() } catch (e: Exception) { null }
                if (todayDate != null && calendarRepository.areAllClassesSuspended(todayDate)) {
                    Log.d(TAG, "All classes are suspended today due to academic calendar exception. Skipping alarms.")
                    return@launch
                }

                val todayDay = DateHelper.todayDayString()
                val todayDateStr = DateHelper.today()
                
                val db = ClassMateDatabase.getInstance(context)
                val periodEntities = db.timetableDao().getPeriodsSync(todayDay)
                val periods = periodEntities.map { it.toPeriod(todayDateStr) }

                if (periods.isEmpty()) {
                    Log.d(TAG, "No timetable periods scheduled for $todayDay today.")
                    return@launch
                }

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@launch
                val nowTime = System.currentTimeMillis()

                periods.forEach { period ->
                    // Skip if the period itself is cancelled today
                    if (period.isCancelled) {
                        Log.d(TAG, "Period ${period.subject} is cancelled today. Skipping mute scheduling.")
                        return@forEach
                    }

                    val startCal = parseTimeToCalendar(period.startTime)
                    val endCal = parseTimeToCalendar(period.endTime)

                    if (startCal == null || endCal == null) {
                        Log.e(TAG, "Failed to parse class times for ${period.subject}: ${period.startTime} - ${period.endTime}")
                        return@forEach
                    }

                    val startMillis = startCal.timeInMillis
                    val endMillis = endCal.timeInMillis

                    // Generate unique request codes
                    val muteRequestCode = period.id.hashCode() * 2
                    val unmuteRequestCode = period.id.hashCode() * 2 + 1

                    // 1. Mute Alarm scheduling
                    if (startMillis > nowTime) {
                        val muteIntent = Intent(context, AutoMuteReceiver::class.java).apply {
                            action = AutoMuteReceiver.ACTION_MUTE
                            putExtra("period_id", period.id)
                            putExtra("subject", period.subject)
                        }
                        val mutePending = PendingIntent.getBroadcast(
                            context,
                            muteRequestCode,
                            muteIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val info = AlarmManager.AlarmClockInfo(startMillis, mutePending)
                        alarmManager.setAlarmClock(info, mutePending)
                        Log.d(TAG, "Scheduled MUTE alarm for ${period.subject} at ${period.startTime} ($startMillis)")
                    } else if (nowTime in startMillis..endMillis) {
                        // Class is currently ongoing, trigger mute immediately
                        Log.d(TAG, "Class ${period.subject} is currently active. Muting phone now.")
                        val muteIntent = Intent(context, AutoMuteReceiver::class.java).apply {
                            action = AutoMuteReceiver.ACTION_MUTE
                        }
                        context.sendBroadcast(muteIntent)
                    }

                    // 2. Unmute Alarm scheduling
                    if (endMillis > nowTime) {
                        val unmuteIntent = Intent(context, AutoMuteReceiver::class.java).apply {
                            action = AutoMuteReceiver.ACTION_UNMUTE
                            putExtra("period_id", period.id)
                            putExtra("subject", period.subject)
                        }
                        val unmutePending = PendingIntent.getBroadcast(
                            context,
                            unmuteRequestCode,
                            unmuteIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val info = AlarmManager.AlarmClockInfo(endMillis, unmutePending)
                        alarmManager.setAlarmClock(info, unmutePending)
                        Log.d(TAG, "Scheduled UNMUTE alarm for ${period.subject} at ${period.endTime} ($endMillis)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling auto-mute alarms", e)
            }
        }
    }

    private fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val db = ClassMateDatabase.getInstance(context)
        
        // Cancel all periods from all days to be safe
        TimetableRepository.DAYS.forEach { day ->
            val periodEntities = db.timetableDao().getPeriodsSync(day)
            periodEntities.forEach { entity ->
                val muteRequestCode = entity.id.hashCode() * 2
                val unmuteRequestCode = entity.id.hashCode() * 2 + 1

                val muteIntent = Intent(context, AutoMuteReceiver::class.java).apply {
                    action = AutoMuteReceiver.ACTION_MUTE
                }
                val mutePending = PendingIntent.getBroadcast(
                    context,
                    muteRequestCode,
                    muteIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (mutePending != null) {
                    alarmManager.cancel(mutePending)
                    mutePending.cancel()
                }

                val unmuteIntent = Intent(context, AutoMuteReceiver::class.java).apply {
                    action = AutoMuteReceiver.ACTION_UNMUTE
                }
                val unmutePending = PendingIntent.getBroadcast(
                    context,
                    unmuteRequestCode,
                    unmuteIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (unmutePending != null) {
                    alarmManager.cancel(unmutePending)
                    unmutePending.cancel()
                }
            }
        }
    }

    private fun parseTimeToCalendar(timeStr: String): Calendar? {
        return try {
            val parts = timeStr.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (e: Exception) {
            null
        }
    }
}
