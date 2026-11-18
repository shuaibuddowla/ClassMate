package com.shuaib.classmate.activities

import java.time.DayOfWeek
import java.time.LocalDate

/** Resolve the selected date; a future academic calendar can supply its holiday flag. */
internal object ClassMateBusDays {
    fun kindFor(
        date: LocalDate,
        calendarHoliday: Boolean = false,
        calendarWorkingDay: Boolean = false,
        weekendDays: Set<DayOfWeek>? = null,
        isRu: Boolean = false
    ): String {
        if (calendarHoliday) return "closed"
        if (calendarWorkingDay) return "office_open"
        val effectiveWeekend = weekendDays ?: if (isRu) {
            setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        } else {
            setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        }
        return if (date.dayOfWeek in effectiveWeekend) "closed" else "office_open"
    }
}
