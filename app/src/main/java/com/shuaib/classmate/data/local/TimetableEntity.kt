package com.shuaib.classmate.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shuaib.classmate.models.Period

@Entity(
    tableName = "timetable_periods",
    indices = [
        Index(value = ["day", "startTime"])
    ]
)
data class TimetableEntity(
    @PrimaryKey val cacheKey: String,
    val day: String,
    val id: String,
    val subject: String,
    val teacher: String,
    val startTime: String,
    val endTime: String,
    val cancelDate: String,
    val substituteTeacher: String,
    val substituteDate: String,
    val cachedAtMillis: Long = System.currentTimeMillis()
) {
    fun toPeriod(today: String): Period {
        val isActuallyCancelled = cancelDate == today
        val isActuallySubstitute = substituteDate == today
        return Period(
            id = id,
            subject = subject,
            teacher = teacher,
            startTime = startTime,
            endTime = endTime,
            isCancelled = isActuallyCancelled,
            cancelDate = cancelDate,
            isSubstitute = isActuallySubstitute,
            substituteTeacher = if (isActuallySubstitute) substituteTeacher else "",
            substituteDate = substituteDate
        )
    }

    companion object {
        fun fromPeriod(day: String, period: Period): TimetableEntity = TimetableEntity(
            cacheKey = cacheKey(day, period.id),
            day = day,
            id = period.id,
            subject = period.subject,
            teacher = period.teacher,
            startTime = period.startTime,
            endTime = period.endTime,
            cancelDate = period.cancelDate,
            substituteTeacher = period.substituteTeacher,
            substituteDate = period.substituteDate
        )

        fun cacheKey(day: String, id: String): String = "${day.lowercase()}:$id"
    }
}
