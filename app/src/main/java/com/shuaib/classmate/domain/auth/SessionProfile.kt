package com.shuaib.classmate.domain.auth

enum class UserRole {
    STUDENT,
    CR,
    TEACHER,
    ADMIN
}

enum class ProfileStatus {
    ACTIVE,
    PENDING_SETUP,
    GRADUATED,
    SUSPENDED,
    BLOCKED
}

data class RoleGrant(
    val id: String,
    val role: UserRole,
    val scope: AcademicScope,
    val startsAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null
)

/** A backend-neutral replacement target for the Firebase-specific User model. */
data class SessionProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: ProfileStatus,
    val studentScope: AcademicScope? = null,
    val roleGrants: List<RoleGrant> = emptyList()
)
