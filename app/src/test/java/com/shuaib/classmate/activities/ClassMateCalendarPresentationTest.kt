package com.shuaib.classmate.activities

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class ClassMateCalendarPresentationTest {
    @Test fun fourFiveAndSixRowsStayCompleteAndSundayAligned() {
        listOf(YearMonth.of(2026,2) to 4,YearMonth.of(2026,10) to 5,YearMonth.of(2026,8) to 6).forEach { (month,rows) ->
            val cells=ClassMateCalendarPresentation.cells(month)
            assertEquals(rows*7,cells.size)
            assertEquals((1..month.lengthOfMonth()).map(month::atDay),cells.filterNotNull())
            assertEquals(month.atDay(1).dayOfWeek.value%7,cells.indexOf(month.atDay(1)))
        }
    }
    @Test fun leapYearsAndEveryWeekdayStartHaveValidGeometry() {
        for(year in 2024..2032) for(number in 1..12) {
            val month=YearMonth.of(year,number); val cells=ClassMateCalendarPresentation.cells(month)
            assertEquals(0,cells.size%7)
            assertEquals(month.lengthOfMonth(),cells.filterNotNull().size)
            assertEquals(month.atEndOfMonth(),cells.filterNotNull().last())
        }
        assertEquals(29,ClassMateCalendarPresentation.cells(YearMonth.of(2024,2)).filterNotNull().size)
    }
    @Test fun simultaneousHolidayAndEventIndicatorsArePreserved() {
        assertEquals(3,ClassMateCalendarPresentation.indicators(true,setOf("classes","observance")).size)
        assertEquals(listOf(ClassMateCalendarPresentation.Indicator.EVENT),ClassMateCalendarPresentation.indicators(false,setOf("working_day")))
        assertTrue(ClassMateCalendarPresentation.indicators(false,emptySet()).isEmpty())
    }
}
