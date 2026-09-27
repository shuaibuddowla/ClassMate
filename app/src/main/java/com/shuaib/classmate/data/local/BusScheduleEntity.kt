package com.shuaib.classmate.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shuaib.classmate.models.BusSchedule

@Entity(tableName = "bus_schedule_cache", indices = [Index(value = ["weekday", "time"])])
data class BusScheduleEntity(
    @PrimaryKey val cacheKey: String,
    val weekday: Int,
    val id: String,
    val time: String,
    val departureFrom: String,
    val busName: String,
    val route: String,
    val destination: String,
    val weekdaysCsv: String,
    val notes: String?,
    val cachedAtMillis: Long = System.currentTimeMillis()
) {
    fun toBusSchedule(): BusSchedule = BusSchedule(
        id = id,
        time = time,
        departureFrom = departureFrom,
        busName = busName,
        route = route,
        scheduleType = if (weekday in listOf(4, 5)) "off_day" else "class_day",
        destination = destination,
        weekdays = weekdaysCsv.split(',').mapNotNull(String::toIntOrNull),
        notes = notes
    )

    companion object {
        fun fromBusSchedule(weekday: Int, bus: BusSchedule) = BusScheduleEntity(
            cacheKey = "$weekday:${bus.id}",
            weekday = weekday,
            id = bus.id,
            time = bus.time,
            departureFrom = bus.departureFrom,
            busName = bus.busName,
            route = bus.route,
            destination = bus.destination,
            weekdaysCsv = bus.weekdays.joinToString(","),
            notes = bus.notes
        )
    }
}
