package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_periods WHERE day = :day ORDER BY startTime")
    fun observePeriods(day: String): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetable_periods WHERE day = :day ORDER BY startTime")
    fun getPeriodsSync(day: String): List<TimetableEntity>

    @Upsert
    suspend fun upsertAll(periods: List<TimetableEntity>)

    @Query("DELETE FROM timetable_periods WHERE day = :day AND cacheKey NOT IN (:activeKeys)")
    suspend fun deleteMissingForDay(day: String, activeKeys: List<String>)

    @Query("DELETE FROM timetable_periods WHERE day = :day")
    suspend fun clearDay(day: String)

    @Transaction
    suspend fun replaceDay(day: String, periods: List<TimetableEntity>) {
        if (periods.isEmpty()) {
            clearDay(day)
        } else {
            upsertAll(periods)
            deleteMissingForDay(day, periods.map { it.cacheKey })
        }
    }
}
