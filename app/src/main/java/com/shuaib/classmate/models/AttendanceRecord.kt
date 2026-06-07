package com.shuaib.classmate.models

import com.google.firebase.Timestamp

data class AttendanceRecord(
    val studentName: String,
    val status: String,
    val detectedAt: Timestamp?
)