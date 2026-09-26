package com.shuaib.classmate.domain.schedule

enum class ClassChangeKind {
    CANCELLED,
    ROOM_CHANGED,
    RESCHEDULED,
    TIME_CHANGED
}

data class RoutineSlot(
    val id: String,
    val courseOfferingId: String,
    val courseCode: String,
    val courseName: String,
    val weekday: Int,
    val startsAt: String,
    val endsAt: String,
    val room: String?,
    val classKind: String
)

data class ClassChange(
    val id: String,
    val routineSlotId: String,
    val noticeId: String?,
    val effectiveDate: String,
    val kind: ClassChangeKind,
    val previousRoom: String?,
    val newRoom: String?,
    val previousStartsAt: String?,
    val previousEndsAt: String?,
    val newStartsAt: String?,
    val newEndsAt: String?,
    val reason: String?
)

data class BusDeparture(
    val id: String,
    val routeName: String,
    val departureTime: String,
    val origin: String,
    val destination: String,
    val weekdays: List<Int>,
    val notes: String?
)

data class DailySchedule(
    val weekday: Int,
    val effectiveDate: String,
    val routine: List<RoutineSlot>,
    val classChanges: List<ClassChange>,
    val busDepartures: List<BusDeparture>
)
