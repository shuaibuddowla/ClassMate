/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/models/User.kt
 */
package com.shuaib.classmate.models

import com.google.firebase.Timestamp

data class User(
    val uid: String = "",
    val name: String = "",
    val fullName: String = "",
    val studentId: String = "",
    val department: String = "",
    val email: String = "",
    val phone: String = "",
    val bloodGroup: String = "",
    val homeDistrict: String = "",
    val address: String = "",
    val role: String = "student",
    val photoUrl: String = "",
    val authProvider: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val oneSignalPlayerId: String = "",
    val favoriteSubjects: List<String> = emptyList(),
    val favoritePdfIds: List<String> = emptyList(),
    val permissions: Map<String, Boolean> = DEFAULT_PERMISSIONS
) {
    fun isProfileComplete(): Boolean {
        // Only check mandatory fields: studentId and department.
        // Phone and bloodGroup are optional (Step 2 can be skipped).
        return studentId.isNotBlank() && department.isNotBlank()
    }

    companion object {
        val DEFAULT_PERMISSIONS = mapOf(
            "canPostNotices" to false,
            "canEditTimetable" to false,
            "canCreatePolls" to false,
            "canSendClassCancel" to false,
            "canUploadPDF" to false,
            "canUploadLibrary" to false,
            "canUploadResult" to false,
            "canUploadSeatPlan" to false,
            "canManageUsers" to false,
            "canManageAdmins" to false
        )
    }
}
