package com.shuaib.classmate.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RoutineSlotRow(
    val id: String,
    @SerialName("course_offering_id") val courseOfferingId: String,
    val weekday: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val room: String? = null,
    @SerialName("class_kind") val classKind: String
)

@Serializable
internal data class ClassChangeRow(
    val id: String,
    @SerialName("routine_slot_id") val routineSlotId: String,
    @SerialName("notice_id") val noticeId: String? = null,
    @SerialName("effective_date") val effectiveDate: String,
    val kind: String,
    @SerialName("previous_room") val previousRoom: String? = null,
    @SerialName("new_room") val newRoom: String? = null,
    @SerialName("previous_starts_at") val previousStartsAt: String? = null,
    @SerialName("previous_ends_at") val previousEndsAt: String? = null,
    @SerialName("new_starts_at") val newStartsAt: String? = null,
    @SerialName("new_ends_at") val newEndsAt: String? = null,
    val reason: String? = null
)

@Serializable
internal data class BusScheduleRow(
    val id: String,
    @SerialName("university_id") val universityId: String,
    @SerialName("route_name") val routeName: String,
    @SerialName("departure_time") val departureTime: String,
    val origin: String,
    val destination: String,
    val weekdays: List<Int>,
    val notes: String? = null
)

data class CourseOfferingOption(
    val id: String,
    val code: String,
    val name: String,
    val sectionId: String?,
    val sectionCode: String?,
    val classKind: String,
    val teacherName: String
) {
    val label: String get() = name +
        sectionCode?.let { " · Section $it" }.orEmpty()
}

@Serializable
internal data class RoutineSlotInsert(
    @SerialName("course_offering_id") val courseOfferingId: String,
    val weekday: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val room: String?,
    @SerialName("class_kind") val classKind: String,
    @SerialName("created_by") val createdBy: String
)

@Serializable
internal data class BusScheduleInsert(
    @SerialName("university_id") val universityId: String,
    @SerialName("route_name") val routeName: String,
    @SerialName("departure_time") val departureTime: String,
    val origin: String,
    val destination: String,
    val weekdays: List<Int>,
    val notes: String?,
    @SerialName("created_by") val createdBy: String
)

@Serializable
internal data class ScheduleTeacherNamesRow(
    @SerialName("course_offering_id") val courseOfferingId: String,
    @SerialName("teacher_names") val teacherNames: String
)

@Serializable
internal data class ClassChangeInsert(
    @SerialName("routine_slot_id") val routineSlotId: String,
    @SerialName("effective_date") val effectiveDate: String,
    val kind: String,
    @SerialName("previous_room") val previousRoom: String?,
    @SerialName("new_room") val newRoom: String?,
    @SerialName("previous_starts_at") val previousStartsAt: String?,
    @SerialName("previous_ends_at") val previousEndsAt: String?,
    @SerialName("new_starts_at") val newStartsAt: String?,
    @SerialName("new_ends_at") val newEndsAt: String?,
    val reason: String?,
    @SerialName("created_by") val createdBy: String
)
