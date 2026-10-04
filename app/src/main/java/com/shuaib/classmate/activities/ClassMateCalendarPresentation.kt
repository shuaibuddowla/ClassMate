package com.shuaib.classmate.activities

import java.time.LocalDate
import java.time.YearMonth

/** Display geometry only; closure decisions still come from CalendarData. */
internal object ClassMateCalendarPresentation {
    fun cells(month: YearMonth): List<LocalDate?> {
        val offset=month.atDay(1).dayOfWeek.value % 7
        val count=((offset+month.lengthOfMonth()+6)/7)*7
        return List(count) { index -> (index-offset+1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay) }
    }

    enum class Indicator { CLOSED, CLASS_HOLIDAY, EVENT }
    fun indicators(closed: Boolean, scopes: Set<String>): List<Indicator> = buildList {
        if(closed || "university" in scopes) add(Indicator.CLOSED)
        if("classes" in scopes) add(Indicator.CLASS_HOLIDAY)
        if("observance" in scopes || "working_day" in scopes) add(Indicator.EVENT)
    }
}
