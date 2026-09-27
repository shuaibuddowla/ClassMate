package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BusScheduleDao {
    @Query("SELECT * FROM bus_schedule_cache WHERE weekday = :weekday ORDER BY time")
    suspend fun getForWeekday(weekday: Int): List<BusScheduleEntity>

    @Query("DELETE FROM bus_schedule_cache WHERE weekday = :weekday")
    suspend fun clearWeekday(weekday: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<BusScheduleEntity>)

    @Transaction
    suspend fun replaceWeekday(weekday: Int, schedules: List<BusScheduleEntity>) {
        clearWeekday(weekday)
        if (schedules.isNotEmpty()) insertAll(schedules)
    }
}
