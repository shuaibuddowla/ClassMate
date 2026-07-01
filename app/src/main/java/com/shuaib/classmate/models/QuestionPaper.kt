package com.shuaib.classmate.models

data class QuestionPaper(
    val id: String = "",
    val title: String = "",
    val courseCode: String = "",
    val subject: String = "",
    val examType: String = "",       // "Final" | "CT1" | "CT2" | "CT3" | "Mid"
    val semester: String = "",       // e.g. "1st", "2nd", …
    val year: String = "",           // e.g. "2022", "2023"
    val uploadedBy: String = "",
    val uploadedByUid: String = "",
    val telegramUrl: String = "",
    val driveUrl: String = "",
    val downloadUrl: String = "",
    val provider: String = "",
    val sizeBytes: Long = 0L,
    val downloadCount: Long = 0L,
    val timestamp: com.google.firebase.Timestamp? = null,
    val isDeleted: Boolean = false
)
