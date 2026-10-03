package com.shuaib.classmate.activities

import java.time.DayOfWeek
import java.time.LocalDate

/** Resolve the selected date; a future academic calendar can supply its holiday flag. */
internal object ClassMateBusDays {
    fun kindFor(date: LocalDate, calendarHoliday: Boolean = false, calendarWorkingDay: Boolean = false): String =
        when {
            calendarHoliday -> "closed"
            calendarWorkingDay -> "office_open"
            date.dayOfWeek == DayOfWeek.THURSDAY || date.dayOfWeek == DayOfWeek.FRIDAY -> "closed"
            else -> "office_open"
        }
}
