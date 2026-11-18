package com.shuaib.classmate.activities

import java.time.DayOfWeek
import java.time.LocalDate

internal object ClassMateCalendarRules {
    fun classesClosed(
        date: LocalDate,
        scopes: Set<String>,
        weekendDays: Set<DayOfWeek>? = null,
        isRu: Boolean = false
    ): Boolean =
        ClassMateBusDays.kindFor(
            date,
            calendarHoliday = "university" in scopes || "classes" in scopes,
            calendarWorkingDay = "working_day" in scopes,
            weekendDays = weekendDays,
            isRu = isRu
        ) == "closed"
}
