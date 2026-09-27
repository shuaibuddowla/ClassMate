package com.shuaib.classmate.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.Source
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.domain.schedule.ScheduleRepository
import com.shuaib.classmate.domain.schedule.ClassChangeKind
import com.shuaib.classmate.domain.auth.SessionRepository
import com.shuaib.classmate.models.BusSchedule
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.AppContextManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: TimetableRepository,
    private val scheduleRepository: ScheduleRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val isV2Configured: Boolean get() = sessionRepository.isConfigured

    fun observePeriods(day: String): Flow<List<Period>> {
        return AppContextManager.activeBatchFlow.flatMapLatest { batch ->
            AppContextManager.activeSemesterFlow.flatMapLatest { sem ->
                val cacheBatch = if (sessionRepository.isConfigured) "v2-$batch" else batch
                repository.observePeriods(day, sem, cacheBatch, dateForWeekDay(day).toString())
            }
        }
    }

    fun refreshDay(
        day: String,
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ) {
        viewModelScope.launch {
            if (sessionRepository.isConfigured) {
                refreshFromSupabase(day, semester, batch)
            } else {
                runCatching { repository.syncDayFromFirestore(day, semester, batch, Source.CACHE) }
                runCatching { repository.syncDayFromFirestore(day, semester, batch, Source.SERVER) }
            }
        }
    }

    fun refreshAll(
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ) {
        viewModelScope.launch {
            if (sessionRepository.isConfigured) {
                DAYS.forEach { day ->
                    refreshFromSupabase(day, semester, batch)
                }
            } else {
                repository.enqueueNetworkSync()
                runCatching { repository.syncAllFromFirestore(semester, batch, Source.CACHE) }
                runCatching { repository.syncAllFromFirestore(semester, batch, Source.SERVER) }
            }
        }
    }

    private suspend fun refreshFromSupabase(day: String, semester: String, batch: String): Boolean {
        if (!sessionRepository.isConfigured) return false
        val weekday = POSTGRES_WEEKDAYS[day.lowercase()] ?: return false
        return scheduleRepository.loadDay(weekday, dateForWeekDay(day).toString(), batch, semester)
            .mapCatching { schedule ->
                val changes = schedule.classChanges.associateBy { it.routineSlotId }
                val periods = schedule.routine.map { slot ->
                    val change = changes[slot.id]
                    val note = when (change?.kind) {
                        ClassChangeKind.ROOM_CHANGED -> listOfNotNull(
                            change.previousRoom?.let { "Room changed from $it" },
                            change.newRoom?.let { "to $it" }, change.reason
                        ).joinToString(" ")
                        ClassChangeKind.TIME_CHANGED -> change.reason ?: "Class time changed"
                        ClassChangeKind.RESCHEDULED -> change.reason ?: "Class rescheduled"
                        ClassChangeKind.CANCELLED -> change.reason ?: "Class cancelled"
                        else -> null
                    }
                    Period(
                        id = slot.id,
                        subject = listOfNotNull(
                            "${slot.courseCode} — ${slot.courseName}",
                            slot.sectionCode?.let { "Section $it" }
                        ).joinToString(" · "),
                        teacher = slot.teacherName,
                        startTime = (change?.newStartsAt ?: slot.startsAt).take(5),
                        endTime = (change?.newEndsAt ?: slot.endsAt).take(5),
                        isCancelled = change?.kind == ClassChangeKind.CANCELLED,
                        cancelDate = if (change?.kind == ClassChangeKind.CANCELLED) schedule.effectiveDate else "",
                        room = change?.newRoom ?: slot.room,
                        scheduleChange = note,
                        classKind = slot.classKind
                    )
                }
                repository.cacheSupabaseDay("v2-$batch", semester, day, periods)
            }.isSuccess
    }

    suspend fun loadBusSchedules(day: String): Result<List<BusSchedule>> = withContext(Dispatchers.IO) {
        if (!sessionRepository.isConfigured) return@withContext Result.failure(
            IllegalStateException("Supabase is not configured.")
        )
        val weekday = POSTGRES_WEEKDAYS[day.lowercase()] ?: return@withContext Result.failure(
            IllegalArgumentException("Unknown weekday.")
        )
        val result = scheduleRepository.loadDay(weekday, dateForWeekDay(day).toString()).map { schedule ->
            schedule.busDepartures.map { departure ->
                BusSchedule(
                    id = departure.id,
                    time = departure.departureTime.take(5),
                    departureFrom = departure.origin,
                    busName = departure.routeName,
                    route = departure.notes.orEmpty(),
                    scheduleType = if (weekday in listOf(4, 5)) "off_day" else "class_day",
                    destination = departure.destination,
                    weekdays = departure.weekdays,
                    notes = departure.notes
                )
            }
        }
        result.onSuccess { repository.cacheBusSchedules(weekday, it) }
        result
    }

    suspend fun getCachedBusSchedules(day: String): List<BusSchedule> = withContext(Dispatchers.IO) {
        val weekday = POSTGRES_WEEKDAYS[day.lowercase()] ?: return@withContext emptyList()
        repository.getCachedBusSchedules(weekday)
    }

    private fun dateForWeekDay(day: String): java.time.LocalDate {
        val today = java.time.LocalDate.now()
        val saturday = today.minusDays(((today.dayOfWeek.value + 1) % 7).toLong())
        return saturday.plusDays(DAYS.indexOf(day.lowercase()).coerceAtLeast(0).toLong())
    }

    private companion object {
        val DAYS = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
        val POSTGRES_WEEKDAYS = mapOf(
            "sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3,
            "thursday" to 4, "friday" to 5, "saturday" to 6
        )
    }
}
