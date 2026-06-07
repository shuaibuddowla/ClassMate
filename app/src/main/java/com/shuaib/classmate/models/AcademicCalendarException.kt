package com.shuaib.classmate.models

import com.google.firebase.Timestamp

data class AcademicCalendarException(
    val id: String = "",
    val title: String = "",
    val type: String = TYPE_VACATION,
    val startDate: String = "",
    val endDate: String = "",
    val scope: String = SCOPE_ALL_CLASSES,
    val reason: String = "",
    val isActive: Boolean = true,
    val showHolidayBriefing: Boolean = false,
    val createdBy: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    companion object {
        const val COLLECTION = "academic_calendar_exceptions"
        const val TYPE_VACATION = "VACATION"
        const val TYPE_HOLIDAY = "HOLIDAY"
        const val TYPE_CLASS_SUSPENDED = "CLASS_SUSPENDED"
        const val SCOPE_ALL_CLASSES = "ALL_CLASSES"

        val SUPPORTED_TYPES = setOf(TYPE_VACATION, TYPE_HOLIDAY, TYPE_CLASS_SUSPENDED)
    }
}
