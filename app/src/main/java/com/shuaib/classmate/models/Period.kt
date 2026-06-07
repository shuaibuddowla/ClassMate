// com/shuaib/classmate/models/Period.kt
package com.shuaib.classmate.models

data class Period(
    val id: String = "",
    val subject: String = "",
    val teacher: String = "",
    val startTime: String = "",
    val endTime: String = "",

    // Cancellation fields
    val isCancelled: Boolean = false,
    val cancelDate: String = "",
    // Format: "2026-04-11" (yyyy-MM-dd)
    // Empty string means not cancelled

    // Substitute fields
    val isSubstitute: Boolean = false,
    val substituteTeacher: String = "",
    val substituteDate: String = ""
    // Format: "2026-04-11" (yyyy-MM-dd)
)
