package com.shuaib.classmate.utils

import com.shuaib.classmate.domain.schedule.ClassChangeKind
import com.shuaib.classmate.domain.schedule.DailySchedule
import com.shuaib.classmate.models.Period

/** Convert a V2 day into the same periods used by the timetable and widget cache. */
fun DailySchedule.toTimetablePeriods(): List<Period> {
    // The schedule is date-specific. Ignore stale/mis-scoped changes defensively
    // so a previous day's cancellation can never tint today's recurring slot.
    val changes = classChanges
        .filter { it.effectiveDate == effectiveDate }
        .groupBy { it.routineSlotId }
        .mapValues { (_, entries) ->
        entries.firstOrNull { it.kind == ClassChangeKind.CANCELLED } ?: entries.last()
    }
    return routine.map { slot ->
        val change = changes[slot.id]
        val note = when (change?.kind) {
            ClassChangeKind.ROOM_CHANGED -> listOfNotNull(
                change.previousRoom?.let { "Room changed from $it" },
                change.newRoom?.let { "to $it" }, change.reason
            ).joinToString(" ")
            ClassChangeKind.TIME_CHANGED -> change.reason ?: "Class time changed"
            ClassChangeKind.RESCHEDULED -> change.reason ?: "Class rescheduled"
            ClassChangeKind.CANCELLED -> change.reason ?: "Class cancelled"
            null -> null
        }
        Period(
            id = slot.id,
            subject = slot.courseName,
            teacher = slot.teacherName,
            startTime = (change?.newStartsAt ?: slot.startsAt).take(5),
            endTime = (change?.newEndsAt ?: slot.endsAt).take(5),
            isCancelled = change?.kind == ClassChangeKind.CANCELLED,
            cancelDate = if (change?.kind == ClassChangeKind.CANCELLED) effectiveDate else "",
            room = change?.newRoom ?: slot.room,
            scheduleChange = note,
            classKind = slot.classKind,
            courseOfferingId = slot.courseOfferingId,
            createdBy = slot.createdBy
        )
    }
}
