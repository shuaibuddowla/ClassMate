package com.shuaib.classmate.activities

import java.time.LocalDate
import java.time.LocalTime

internal object ClassMateBusPresentation {
    fun next(times: List<LocalTime>, selectedDate: LocalDate, today: LocalDate, now: LocalTime): LocalTime? {
        if (selectedDate.isBefore(today)) return null
        return times.filter { selectedDate.isAfter(today) || !it.isBefore(now) }.minOrNull()
    }
}
