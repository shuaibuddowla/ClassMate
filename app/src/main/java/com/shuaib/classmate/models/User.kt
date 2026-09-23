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
    val batchId: String = "",
    val adminBatchIds: List<String> = emptyList(),
    val permissions: Map<String, Boolean> = DEFAULT_PERMISSIONS
) {
    fun isProfileComplete(): Boolean {
        // Only check mandatory fields: studentId, department, and batchId.
        return studentId.isNotBlank() && department.isNotBlank() && batchId.isNotBlank()
    }

    fun isGlobalSuperAdmin(): Boolean = role == "global_super_admin" || role == "global_superadmin"

    fun isBatchAdmin(batch: String): Boolean {
        if (isGlobalSuperAdmin()) return true
        if (role == "superadmin" || role == "super_admin" || role == "admin") {
            return adminBatchIds.map { it.lowercase() }.contains(batch.lowercase())
        }
        return false
    }

    fun hasPermission(permissionKey: String): Boolean {
        if (isGlobalSuperAdmin() || role == "superadmin" || role == "super_admin") return true
        return permissions[permissionKey] == true
    }

    fun canPostNotices(): Boolean = hasPermission("canPostNotices")
    fun canEditTimetable(): Boolean = hasPermission("canEditTimetable")
    fun canCreatePolls(): Boolean = hasPermission("canCreatePolls")
    fun canSendClassCancel(): Boolean = hasPermission("canSendClassCancel")
    fun canUploadPDF(): Boolean = hasPermission("canUploadPDF")
    fun canUploadLibrary(): Boolean = hasPermission("canUploadLibrary")
    fun canUploadResult(): Boolean = hasPermission("canUploadResult")
    fun canUploadSeatPlan(): Boolean = hasPermission("canUploadSeatPlan")
    fun canManageUsers(): Boolean = hasPermission("canManageUsers")
    fun canManageAdmins(): Boolean = hasPermission("canManageAdmins")

    fun isAdmin(): Boolean {
        return isGlobalSuperAdmin() || role == "superadmin" || role == "super_admin" || role == "admin" || permissions.values.any { it }
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
