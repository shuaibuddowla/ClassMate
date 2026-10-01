package com.shuaib.classmate

import com.shuaib.classmate.domain.schedule.ClassChange
import com.shuaib.classmate.domain.schedule.ClassChangeKind
import com.shuaib.classmate.domain.schedule.DailySchedule
import com.shuaib.classmate.domain.schedule.RoutineSlot
import com.shuaib.classmate.utils.toTimetablePeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TimetableMapperTest {
    @Test
    fun previousDaysCancellationDoesNotCancelTodaysRecurringSlot() {
        val slot = RoutineSlot(
            id = "slot-1", courseOfferingId = "offering-1", courseCode = "CSE2103",
            courseName = "DSA", sectionCode = null, weekday = 1,
            startsAt = "14:21:00", endsAt = "17:21:00", room = "338",
            classKind = "class"
        )
        val yesterdayCancellation = ClassChange(
            id = "change-yesterday", routineSlotId = slot.id, noticeId = "notice-1",
            effectiveDate = "2026-09-28", kind = ClassChangeKind.CANCELLED,
            previousRoom = null, newRoom = null, previousStartsAt = null,
            previousEndsAt = null, newStartsAt = null, newEndsAt = null,
            reason = "Cancelled yesterday"
        )

        val period = DailySchedule(
            weekday = 2, effectiveDate = "2026-09-29", routine = listOf(slot),
            classChanges = listOf(yesterdayCancellation), busDepartures = emptyList()
        ).toTimetablePeriods().single()

        assertTrue(!period.isCancelled)
        assertEquals("", period.cancelDate)
        assertEquals(null, period.scheduleChange)
    }

    @Test
    fun cancellationWinsOverAnotherChangeAndKeepsCourseDetails() {
        val slot = RoutineSlot(
            id = "slot-1", courseOfferingId = "offering-1", courseCode = "CSE2103",
            courseName = "DSA", sectionCode = null, weekday = 1,
            startsAt = "14:21:00", endsAt = "17:21:00", room = "338",
            classKind = "class", teacherName = "Dr. Example"
        )
        val cancellation = ClassChange(
            id = "change-1", routineSlotId = slot.id, noticeId = "notice-1",
            effectiveDate = "2026-09-28", kind = ClassChangeKind.CANCELLED,
            previousRoom = null, newRoom = null, previousStartsAt = null,
            previousEndsAt = null, newStartsAt = null, newEndsAt = null,
            reason = "Class cancelled"
        )
        val roomChange = cancellation.copy(
            id = "change-2", kind = ClassChangeKind.ROOM_CHANGED, newRoom = "339"
        )
        val period = DailySchedule(
            weekday = 1, effectiveDate = "2026-09-28", routine = listOf(slot),
            classChanges = listOf(cancellation, roomChange), busDepartures = emptyList()
        ).toTimetablePeriods().single()

        assertTrue(period.isCancelled)
        assertEquals("2026-09-28", period.cancelDate)
        assertEquals("DSA", period.subject)
        assertEquals("Dr. Example", period.teacher)
        assertEquals("338", period.room)
    }
}
