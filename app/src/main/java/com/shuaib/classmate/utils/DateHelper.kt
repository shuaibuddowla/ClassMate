// com/shuaib/classmate/utils/DateHelper.kt
package com.shuaib.classmate.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DateHelper {

    private val dateFormat = SimpleDateFormat(
        "yyyy-MM-dd", Locale.getDefault()
    )

    // Get today's date as "yyyy-MM-dd"
    fun today(): String {
        return dateFormat.format(Calendar.getInstance().time)
    }

    // Get tomorrow's date as "yyyy-MM-dd"
    fun tomorrow(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return dateFormat.format(cal.time)
    }

    // Get day string for Firestore
    // e.g "saturday", "sunday" etc
    fun todayDayString(): String {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY  -> "saturday"
            Calendar.SUNDAY    -> "sunday"
            Calendar.MONDAY    -> "monday"
            Calendar.TUESDAY   -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY  -> "thursday"
            Calendar.FRIDAY     -> "friday"
            else               -> "saturday"
        }
    }

    // Get tomorrow's day string
    fun tomorrowDayString(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY  -> "saturday"
            Calendar.SUNDAY    -> "sunday"
            Calendar.MONDAY    -> "monday"
            Calendar.TUESDAY   -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY  -> "thursday"
            Calendar.FRIDAY     -> "friday"
            else               -> "saturday"
        }
    }

    // Check if a cancelDate is still active (today or future)
    fun isCancellationActive(cancelDate: String): Boolean {
        if (cancelDate.isEmpty()) return false
        return cancelDate == today()
    }



    // Format for display e.g "Today, Apr 11"
    fun formatForDisplay(dateStr: String): String {
        return when (dateStr) {
            today() -> "Today"
            tomorrow() -> "Tomorrow"
            else -> dateStr
        }
    }
}
