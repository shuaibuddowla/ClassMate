package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.domain.academic.AcademicCatalogRepository
import com.shuaib.classmate.domain.schedule.BusDeparture
import com.shuaib.classmate.domain.schedule.ClassChange
import com.shuaib.classmate.domain.schedule.ClassChangeKind
import com.shuaib.classmate.domain.schedule.DailySchedule
import com.shuaib.classmate.domain.schedule.RoutineSlot
import com.shuaib.classmate.domain.schedule.ScheduleRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class SupabaseScheduleRepository @Inject constructor(
    private val clientProvider: SupabaseClientProvider,
    private val academicCatalogRepository: AcademicCatalogRepository,
    private val sessionRepository: com.shuaib.classmate.domain.auth.SessionRepository
) : ScheduleRepository {

    val isConfigured: Boolean get() = clientProvider.isConfigured

    suspend fun manageableOfferings(batchRoute: String, semesterRoute: String): Result<List<CourseOfferingOption>> = runCatching {
        val catalog = academicCatalogRepository.loadAccessibleCatalog().getOrThrow()
        val departmentCode = batchRoute.filter(Char::isLetter).lowercase()
        val batchCode = batchRoute.filter(Char::isDigit).takeLast(2).trimStart('0').ifBlank { "0" }
        val department = catalog.departments.firstOrNull { it.code.equals(departmentCode, true) }
            ?: error("This department is not configured in ClassMate V2 yet.")
        val batch = catalog.batches.firstOrNull {
            it.departmentId == department.id && it.cohortCode.trimStart('0') == batchCode
        }
            ?: error("This batch is not configured in ClassMate V2 yet.")
        val ordinal = semesterRoute.firstOrNull(Char::isDigit)?.digitToIntOrNull()
            ?: error("Select a valid semester.")
        val batchSemesterIds = catalog.batchSemesters.asSequence()
            .filter { it.batchId == batch.id && it.state == com.shuaib.classmate.domain.academic.SemesterState.PUBLISHED }
            .filter { entry -> catalog.semesters.firstOrNull { it.id == entry.semesterId }?.ordinal == ordinal }
            .map { it.id }.toSet()
        catalog.courseOfferings.filter { it.batchSemesterId in batchSemesterIds }.mapNotNull { offering ->
            catalog.courses.firstOrNull { it.id == offering.courseId }?.let { course ->
                CourseOfferingOption(
                    offering.id, course.code, course.name, offering.sectionId,
                    offering.sectionId?.let { sectionId -> catalog.sections.firstOrNull { it.id == sectionId }?.code },
                    course.kind.name.lowercase(), course.teacherName.orEmpty()
                )
            }
        }.sortedBy { "${it.code} ${it.name}" }
    }

    suspend fun createRoutine(
        offeringId: String, weekday: Int, startsAt: String, endsAt: String,
        room: String?, classKind: String
    ): Result<Unit> = runCatching {
        val session = sessionRepository.refresh().getOrThrow()
        clientProvider.client.from("routine_slots").insert(
            RoutineSlotInsert(
                courseOfferingId = offeringId, weekday = weekday, startsAt = startsAt,
                endsAt = endsAt, room = room?.takeIf(String::isNotBlank), classKind = classKind,
                createdBy = session.id
            )
        )
    }

    suspend fun updateRoutine(
        id: String, offeringId: String, weekday: Int, startsAt: String, endsAt: String,
        room: String?, classKind: String
    ): Result<Unit> = runCatching {
        clientProvider.client.from("routine_slots").update({
            set("course_offering_id", offeringId); set("weekday", weekday)
            set("starts_at", startsAt); set("ends_at", endsAt)
            set("room", room?.takeIf(String::isNotBlank)); set("class_kind", classKind)
        }) { filter { eq("id", id) } }
    }

    override suspend fun deleteRoutine(id: String): Result<Unit> = runCatching {
        clientProvider.client.postgrest.rpc("archive_routine_slot", parameters = buildJsonObject {
            put("target_slot", id)
        })
    }

    suspend fun createBus(
        routeName: String, departureTime: String, origin: String, destination: String,
        weekdays: List<Int>, notes: String?
    ): Result<Unit> = runCatching {
        val session = sessionRepository.refresh().getOrThrow()
        clientProvider.client.from("bus_schedules").insert(
            BusScheduleInsert(
                universityId = session.universityId, routeName = routeName,
                departureTime = departureTime, origin = origin, destination = destination,
                weekdays = weekdays, notes = notes?.takeIf(String::isNotBlank), createdBy = session.id
            )
        )
    }

    override suspend fun loadAllBusDepartures(): Result<List<BusDeparture>> = runCatching {
        check(clientProvider.isConfigured) { "Supabase is not configured." }
        clientProvider.client.from("bus_schedules").select().decodeList<BusScheduleRow>()
            .map { it.toDomain() }
            .sortedBy { it.departureTime }
    }

    suspend fun updateBus(
        id: String, routeName: String, departureTime: String, origin: String, destination: String,
        weekdays: List<Int>, notes: String?
    ): Result<Unit> = runCatching {
        clientProvider.client.from("bus_schedules").update({
            set("route_name", routeName); set("departure_time", departureTime)
            set("origin", origin); set("destination", destination)
            set("weekdays", weekdays); set("notes", notes?.takeIf(String::isNotBlank))
        }) { filter { eq("id", id) } }
    }

    suspend fun deleteBus(id: String): Result<Unit> = runCatching {
        val session = sessionRepository.refresh().getOrThrow()
        clientProvider.client.from("bus_schedules").update({
            set("deleted_at", Instant.now().toString())
            set("deleted_by", session.id)
        }) { filter { eq("id", id) } }
    }

    suspend fun createClassChange(
        routineSlotId: String,
        effectiveDate: String,
        kind: String,
        previousRoom: String? = null,
        newRoom: String? = null,
        previousStartsAt: String? = null,
        previousEndsAt: String? = null,
        newStartsAt: String? = null,
        newEndsAt: String? = null,
        reason: String? = null
    ): Result<Unit> = runCatching {
        require(kind in setOf("cancelled", "room_changed", "rescheduled", "time_changed"))
        val session = sessionRepository.refresh().getOrThrow()
        clientProvider.client.from("class_changes").insert(
            ClassChangeInsert(
                routineSlotId = routineSlotId,
                effectiveDate = effectiveDate,
                kind = kind,
                previousRoom = previousRoom?.takeIf(String::isNotBlank),
                newRoom = newRoom?.takeIf(String::isNotBlank),
                previousStartsAt = previousStartsAt,
                previousEndsAt = previousEndsAt,
                newStartsAt = newStartsAt,
                newEndsAt = newEndsAt,
                reason = reason?.takeIf(String::isNotBlank),
                createdBy = session.id
            )
        )
    }

    override suspend fun loadDay(
        weekday: Int,
        effectiveDate: String,
        batchRoute: String?,
        semesterRoute: String?,
        includeBusDepartures: Boolean
    ): Result<DailySchedule> =
        runCatching {
            require(weekday in 0..6) { "Weekday must be between 0 and 6." }
            require(ISO_DATE.matches(effectiveDate)) { "Date must use yyyy-MM-dd." }
            check(clientProvider.isConfigured) { "Supabase is not configured." }

            val client = clientProvider.client
            coroutineScope {
                val catalogRequest = async {
                    academicCatalogRepository.loadAccessibleCatalog().getOrThrow()
                }
                val routineRows = async {
                    client.from("routine_slots")
                        .select()
                        .decodeList<RoutineSlotRow>()
                }
                val changeRows = async {
                    client.from("class_changes")
                        .select { filter { eq("effective_date", effectiveDate) } }
                        .decodeList<ClassChangeRow>()
                }
                val busRows = async {
                    if (includeBusDepartures) client.from("bus_schedules").select().decodeList<BusScheduleRow>()
                    else emptyList()
                }

                val catalog = catalogRequest.await()

                val scopedOfferingIds = if (batchRoute.isNullOrBlank() || semesterRoute.isNullOrBlank()) {
                    null
                } else {
                    val departmentCode = batchRoute.filter(Char::isLetter)
                    val cohortCode = batchRoute.filter(Char::isDigit).trimStart('0')
                    val department = catalog.departments.firstOrNull { it.code.equals(departmentCode, true) }
                    val batch = catalog.batches.firstOrNull {
                        it.departmentId == department?.id && it.cohortCode.trimStart('0') == cohortCode
                    }
                    val ordinal = semesterRoute.firstOrNull(Char::isDigit)?.digitToIntOrNull()
                    val semesterIds = catalog.semesters.filter { it.ordinal == ordinal }.map { it.id }.toSet()
                    val batchSemesterIds = catalog.batchSemesters.filter {
                        it.batchId == batch?.id && it.semesterId in semesterIds &&
                            it.state == com.shuaib.classmate.domain.academic.SemesterState.PUBLISHED
                    }.map { it.id }.toSet()
                    catalog.courseOfferings.filter { it.batchSemesterId in batchSemesterIds }.map { it.id }.toSet()
                }
                val courses = catalog.courses.associateBy { it.id }
                val offerings = catalog.courseOfferings.associateBy { it.id }
                val sections = catalog.sections.associateBy { it.id }
                val visibleRoutineRows = routineRows.await().filter {
                    scopedOfferingIds == null || it.courseOfferingId in scopedOfferingIds
                }
                val visibleRoutineIds = visibleRoutineRows.map { it.id }.toSet()
                val changes = changeRows.await().filter { it.routineSlotId in visibleRoutineIds }.map { it.toDomain() }
                val changedSlotIds = changes.map { it.routineSlotId }.toSet()
                val applicableRoutineRows = visibleRoutineRows.filter {
                    it.weekday == weekday || it.id in changedSlotIds
                }
                val teacherNames = runCatching {
                    val offeringIds = applicableRoutineRows.map { it.courseOfferingId }.distinct()
                    if (offeringIds.isEmpty()) emptyMap() else {
                        client.postgrest.rpc(
                            "get_schedule_teacher_names",
                            parameters = buildJsonObject {
                                put("target_offerings", kotlinx.serialization.json.JsonArray(offeringIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                            }
                        ).decodeList<ScheduleTeacherNamesRow>().associate { it.courseOfferingId to it.teacherNames }
                    }
                }.getOrDefault(emptyMap())
                val routine = applicableRoutineRows.map { row ->
                    val offering = checkNotNull(offerings[row.courseOfferingId]) {
                        "Routine references an inaccessible course offering."
                    }
                    val course = checkNotNull(courses[offering.courseId]) {
                        "Course offering references an inaccessible course."
                    }
                    RoutineSlot(
                        id = row.id,
                        courseOfferingId = row.courseOfferingId,
                        courseCode = course.code,
                        courseName = course.name,
                        sectionCode = offering.sectionId?.let { sections[it]?.code },
                        weekday = row.weekday,
                        startsAt = row.startsAt,
                        endsAt = row.endsAt,
                        room = row.room,
                        classKind = row.classKind,
                        createdBy = row.createdBy,
                        teacherName = teacherNames[row.courseOfferingId].orEmpty()
                            .ifBlank { course.teacherName.orEmpty() }
                    )
                }.sortedBy { it.startsAt }

                DailySchedule(
                    weekday = weekday,
                    effectiveDate = effectiveDate,
                    routine = routine,
                    classChanges = changes,
                    busDepartures = busRows.await()
                        .filter { weekday in it.weekdays }
                        .map { it.toDomain() }
                        .sortedBy { it.departureTime }
                )
            }
        }

    private fun ClassChangeRow.toDomain() = ClassChange(
        id = id,
        routineSlotId = routineSlotId,
        noticeId = noticeId,
        effectiveDate = effectiveDate,
        kind = when (kind) {
            "cancelled" -> ClassChangeKind.CANCELLED
            "room_changed" -> ClassChangeKind.ROOM_CHANGED
            "rescheduled" -> ClassChangeKind.RESCHEDULED
            "time_changed" -> ClassChangeKind.TIME_CHANGED
            else -> error("Unsupported class-change kind: $kind")
        },
        previousRoom = previousRoom,
        newRoom = newRoom,
        previousStartsAt = previousStartsAt,
        previousEndsAt = previousEndsAt,
        newStartsAt = newStartsAt,
        newEndsAt = newEndsAt,
        reason = reason
    )

    private fun BusScheduleRow.toDomain() = BusDeparture(
        id, routeName, departureTime, origin, destination, weekdays, notes
    )

    private companion object {
        val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
