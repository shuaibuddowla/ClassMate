// com/shuaib/classmate/models/SeatPlan.kt
package com.shuaib.classmate.models

import com.google.firebase.Timestamp

data class SeatPlan(
    val id: String = "",
    val title: String = "",
    val telegramUrl: String = "",
    val fileId: String = "",
    val uploadedBy: String = "",
    val timestamp: Timestamp? = null
)
