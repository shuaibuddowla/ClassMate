package com.shuaib.classmate.activities

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
}
