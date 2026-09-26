package com.shuaib.classmate.domain.schedule

interface ScheduleRepository {
    /** Weekday follows PostgreSQL DOW: 0=Sunday through 6=Saturday. */
    suspend fun loadDay(weekday: Int, effectiveDate: String): Result<DailySchedule>
}
