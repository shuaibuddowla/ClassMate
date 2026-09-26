package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.domain.academic.AcademicCatalogRepository
import com.shuaib.classmate.domain.schedule.BusDeparture
import com.shuaib.classmate.domain.schedule.ClassChange
import com.shuaib.classmate.domain.schedule.ClassChangeKind
import com.shuaib.classmate.domain.schedule.DailySchedule
import com.shuaib.classmate.domain.schedule.RoutineSlot
import com.shuaib.classmate.domain.schedule.ScheduleRepository
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Singleton
class SupabaseScheduleRepository @Inject constructor(
    private val clientProvider: SupabaseClientProvider,
    private val academicCatalogRepository: AcademicCatalogRepository
) : ScheduleRepository {

    override suspend fun loadDay(weekday: Int, effectiveDate: String): Result<DailySchedule> =
        runCatching {
            require(weekday in 0..6) { "Weekday must be between 0 and 6." }
            require(ISO_DATE.matches(effectiveDate)) { "Date must use yyyy-MM-dd." }
            check(clientProvider.isConfigured) { "Supabase is not configured." }

            val catalog = academicCatalogRepository.loadAccessibleCatalog().getOrThrow()
            val client = clientProvider.client
            coroutineScope {
                val routineRows = async {
                    client.from("routine_slots")
                        .select { filter { eq("weekday", weekday) } }
                        .decodeList<RoutineSlotRow>()
                }
                val changeRows = async {
                    client.from("class_changes")
                        .select { filter { eq("effective_date", effectiveDate) } }
                        .decodeList<ClassChangeRow>()
                }
                val busRows = async {
                    client.from("bus_schedules").select().decodeList<BusScheduleRow>()
                }

                val courses = catalog.courses.associateBy { it.id }
                val offerings = catalog.courseOfferings.associateBy { it.id }
                val routine = routineRows.await().map { row ->
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
                        weekday = row.weekday,
                        startsAt = row.startsAt,
                        endsAt = row.endsAt,
                        room = row.room,
                        classKind = row.classKind
                    )
                }.sortedBy { it.startsAt }

                DailySchedule(
                    weekday = weekday,
                    effectiveDate = effectiveDate,
                    routine = routine,
                    classChanges = changeRows.await().map { it.toDomain() },
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
