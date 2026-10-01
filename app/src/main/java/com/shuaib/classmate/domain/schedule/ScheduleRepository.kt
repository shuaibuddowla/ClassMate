package com.shuaib.classmate.domain.schedule

interface ScheduleRepository {
    suspend fun loadAllBusDepartures(): Result<List<BusDeparture>>

    suspend fun deleteRoutine(id: String): Result<Unit>

    /** Weekday follows PostgreSQL DOW: 0=Sunday through 6=Saturday. */
    suspend fun loadDay(
        weekday: Int,
        effectiveDate: String,
        batchRoute: String? = null,
        semesterRoute: String? = null,
        includeBusDepartures: Boolean = true
    ): Result<DailySchedule>
}
