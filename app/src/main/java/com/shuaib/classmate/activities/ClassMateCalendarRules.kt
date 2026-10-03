package com.shuaib.classmate.activities

import java.time.LocalDate

internal object ClassMateCalendarRules {
    fun classesClosed(date: LocalDate, scopes: Set<String>): Boolean =
        ClassMateBusDays.kindFor(date,
            calendarHoliday = "university" in scopes || "classes" in scopes,
            calendarWorkingDay = "working_day" in scopes) == "closed"
}
