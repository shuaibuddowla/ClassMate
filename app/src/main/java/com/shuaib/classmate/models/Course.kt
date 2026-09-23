package com.shuaib.classmate.models

import com.google.firebase.Timestamp
import com.shuaib.classmate.utils.Subject

data class Course(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val type: String = "regular",
    val batchId: String = "",
    val semesterId: String = "",
    val createdBy: String = "",
    val createdAt: Timestamp? = null
) {
    fun toSubject(): Subject = Subject(name, code, type)
}
