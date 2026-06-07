// com/shuaib/classmate/models/Assignment.kt
package com.shuaib.classmate.models

data class Assignment(
    val id: String = "",
    val subject: String = "",
    val topic: String = "",
    val submissionDate: String = "", // "yyyy-MM-dd"
    val submissionTime: String = "23:59", // default end of day
    val type: String = "assignment",
    val postedBy: String = "",
    val timestamp: com.google.firebase.Timestamp? = null
)
