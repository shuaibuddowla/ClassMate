package com.shuaib.classmate.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ProfileRow(
    val id: String,
    @SerialName("university_id") val universityId: String? = null,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String
)

@Serializable
internal data class StudentProfileRow(
    @SerialName("profile_id") val profileId: String,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("assigned_batch_id") val assignedBatchId: String? = null,
    @SerialName("section_id") val sectionId: String? = null
)

@Serializable
internal data class RoleGrantRow(
    val id: String,
    val role: String,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("section_id") val sectionId: String? = null,
    @SerialName("course_offering_id") val courseOfferingId: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null
)
