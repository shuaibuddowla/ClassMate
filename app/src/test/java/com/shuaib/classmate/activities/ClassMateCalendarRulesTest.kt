package com.shuaib.classmate.activities

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ClassMateCalendarRulesTest {
    private val tuesday = LocalDate.of(2026, 11, 17)

    @Test fun classOnlyAndOfficeHolidaysBothHidePeriods() {
        assertTrue(ClassMateCalendarRules.classesClosed(tuesday, setOf("classes")))
        assertTrue(ClassMateCalendarRules.classesClosed(tuesday, setOf("university")))
        assertEquals("office_open", ClassMateBusDays.kindFor(tuesday))
    }

    @Test fun observancesDoNotCancelClasses() {
        assertFalse(ClassMateCalendarRules.classesClosed(tuesday, setOf("observance")))
        assertFalse(ClassMateCalendarRules.classesClosed(tuesday, emptySet()))
    }

    @Test fun weeklyHolidaysAndWorkingDayExceptions() {
        val thursday = LocalDate.of(2026, 10, 8)
        assertTrue(ClassMateCalendarRules.classesClosed(thursday, emptySet()))
        assertTrue(ClassMateCalendarRules.classesClosed(thursday.plusDays(1), emptySet()))
        assertFalse(ClassMateCalendarRules.classesClosed(thursday, setOf("working_day")))
        assertTrue(ClassMateCalendarRules.classesClosed(thursday, setOf("working_day", "classes")))
    }

    @Test fun dynamicWeekendDaysEvaluation() {
        val thursday = LocalDate.of(2026, 10, 8) // Thursday
        val friday = thursday.plusDays(1)       // Friday
        val saturday = thursday.plusDays(2)     // Saturday
        val sunday = thursday.plusDays(3)       // Sunday

        val friSatWeekend = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        val thuFriWeekend = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        // Fri/Sat Institution (e.g. RU, DU)
        assertFalse(ClassMateCalendarRules.classesClosed(thursday, emptySet(), weekendDays = friSatWeekend))
        assertEquals("office_open", ClassMateBusDays.kindFor(thursday, weekendDays = friSatWeekend))
        assertTrue(ClassMateCalendarRules.classesClosed(friday, emptySet(), weekendDays = friSatWeekend))
        assertEquals("closed", ClassMateBusDays.kindFor(friday, weekendDays = friSatWeekend))
        assertTrue(ClassMateCalendarRules.classesClosed(saturday, emptySet(), weekendDays = friSatWeekend))
        assertEquals("closed", ClassMateBusDays.kindFor(saturday, weekendDays = friSatWeekend))
        assertFalse(ClassMateCalendarRules.classesClosed(sunday, emptySet(), weekendDays = friSatWeekend))

        // Thu/Fri Institution (e.g. MBSTU)
        assertTrue(ClassMateCalendarRules.classesClosed(thursday, emptySet(), weekendDays = thuFriWeekend))
        assertEquals("closed", ClassMateBusDays.kindFor(thursday, weekendDays = thuFriWeekend))
        assertTrue(ClassMateCalendarRules.classesClosed(friday, emptySet(), weekendDays = thuFriWeekend))
        assertEquals("closed", ClassMateBusDays.kindFor(friday, weekendDays = thuFriWeekend))
        assertFalse(ClassMateCalendarRules.classesClosed(saturday, emptySet(), weekendDays = thuFriWeekend))
        assertEquals("office_open", ClassMateBusDays.kindFor(saturday, weekendDays = thuFriWeekend))
    }

    @Test fun ruWeeklyHolidaysAreFridayAndSaturday() {
        val thursday = LocalDate.of(2026, 10, 8) // Thursday
        val friday = thursday.plusDays(1)       // Friday
        val saturday = thursday.plusDays(2)     // Saturday
        val sunday = thursday.plusDays(3)       // Sunday

        // RU has Thursday as regular class day
        assertFalse(ClassMateCalendarRules.classesClosed(thursday, emptySet(), isRu = true))
        assertEquals("office_open", ClassMateBusDays.kindFor(thursday, isRu = true))

        // RU has Friday & Saturday as closed weekly holiday
        assertTrue(ClassMateCalendarRules.classesClosed(friday, emptySet(), isRu = true))
        assertEquals("closed", ClassMateBusDays.kindFor(friday, isRu = true))
        assertTrue(ClassMateCalendarRules.classesClosed(saturday, emptySet(), isRu = true))
        assertEquals("closed", ClassMateBusDays.kindFor(saturday, isRu = true))

        // RU has Sunday as regular class day
        assertFalse(ClassMateCalendarRules.classesClosed(sunday, emptySet(), isRu = true))
        assertEquals("office_open", ClassMateBusDays.kindFor(sunday, isRu = true))
    }
}
