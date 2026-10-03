package com.shuaib.classmate.activities

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ClassMateBusPresentationTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val times = listOf(LocalTime.of(16, 10), LocalTime.of(8, 0), LocalTime.of(13, 10))
    @Test fun nextDepartureSkipsElapsedTimesAndSorts() {
        assertEquals(LocalTime.of(16, 10), ClassMateBusPresentation.next(times, today, today, LocalTime.of(14, 59)))
        assertEquals(LocalTime.of(13, 10), ClassMateBusPresentation.next(times, today, today, LocalTime.of(13, 10)))
    }
    @Test fun futureDateShowsFirstDeparture() {
        assertEquals(LocalTime.of(8, 0), ClassMateBusPresentation.next(times, today.plusDays(1), today, LocalTime.of(20, 0)))
    }
    @Test fun finishedPastOrEmptySchedulesHaveNoNextDeparture() {
        assertNull(ClassMateBusPresentation.next(times, today, today, LocalTime.of(23, 0)))
        assertNull(ClassMateBusPresentation.next(times, today.minusDays(1), today, LocalTime.NOON))
        assertNull(ClassMateBusPresentation.next(emptyList(), today, today, LocalTime.NOON))
    }
}
