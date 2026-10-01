package com.shuaib.classmate.models

import com.google.firebase.Timestamp
import com.shuaib.classmate.utils.Subject

data class Course(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val teacherName: String = "",
    val type: String = "regular",
    /** Archive catalogue id used only by the Cloudflare/R2 upload workflow. */
    val archiveId: String = "",
    val batchId: String = "",
    val semesterId: String = "",
    val createdBy: String = "",
    val canManage: Boolean = false,
    val createdAt: Timestamp? = null
) {
    fun toSubject(): Subject = Subject(name, code, type)
}
