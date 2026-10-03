package com.shuaib.classmate.activities

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassMateBusDaysTest {
    @Test fun selectedDatesUseUniversityWeekends() {
        val sunday = LocalDate.of(2026, 10, 4)
        val expected = listOf("office_open", "office_open", "office_open", "office_open", "closed", "closed", "office_open")
        expected.forEachIndexed { offset, kind ->
            assertEquals(kind, ClassMateBusDays.kindFor(sunday.plusDays(offset.toLong())))
        }
    }

    @Test fun calendarHolidayOverridesAnOrdinaryTuesday() {
        val tuesday = LocalDate.of(2026, 10, 6)
        assertEquals("office_open", ClassMateBusDays.kindFor(tuesday))
        assertEquals("closed", ClassMateBusDays.kindFor(tuesday, calendarHoliday = true))
        assertEquals("closed", ClassMateBusDays.kindFor(LocalDate.of(2026, 10, 8), calendarHoliday = false))
    }
    @Test fun exceptionalWorkingDaysOverrideTheWeeklyClosure() {
        val thursday = LocalDate.of(2026, 10, 8)
        assertEquals("office_open", ClassMateBusDays.kindFor(thursday, calendarWorkingDay = true))
        assertEquals("closed", ClassMateBusDays.kindFor(thursday, calendarHoliday = true, calendarWorkingDay = true))
    }
}
