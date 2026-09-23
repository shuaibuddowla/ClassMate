package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_periods WHERE batchId = :batchId AND semesterId = :semesterId AND day = :day ORDER BY startTime")
    fun observePeriods(batchId: String, semesterId: String, day: String): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetable_periods WHERE batchId = :batchId AND semesterId = :semesterId AND day = :day ORDER BY startTime")
    fun getPeriodsSync(batchId: String, semesterId: String, day: String): List<TimetableEntity>

    @Upsert
    suspend fun upsertAll(periods: List<TimetableEntity>)

    @Query("DELETE FROM timetable_periods WHERE batchId = :batchId AND semesterId = :semesterId AND day = :day AND cacheKey NOT IN (:activeKeys)")
    suspend fun deleteMissingForDay(batchId: String, semesterId: String, day: String, activeKeys: List<String>)

    @Query("DELETE FROM timetable_periods WHERE batchId = :batchId AND semesterId = :semesterId AND day = :day")
    suspend fun clearDay(batchId: String, semesterId: String, day: String)

    @Query("DELETE FROM timetable_periods WHERE batchId = :batchId AND semesterId = :semesterId")
    suspend fun clearSemester(batchId: String, semesterId: String)

    @Query("DELETE FROM timetable_periods")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceDay(batchId: String, semesterId: String, day: String, periods: List<TimetableEntity>) {
        if (periods.isEmpty()) {
            clearDay(batchId, semesterId, day)
        } else {
            upsertAll(periods)
            deleteMissingForDay(batchId, semesterId, day, periods.map { it.cacheKey })
        }
    }
}
