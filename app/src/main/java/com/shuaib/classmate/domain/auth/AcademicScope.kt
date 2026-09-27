package com.shuaib.classmate.domain.auth

/**
 * Backend-neutral academic context for the signed-in user.
 *
 * IDs are opaque server identifiers. The Android client may select among scopes
 * returned by the backend, but it must never use this model as proof of access.
 * PostgreSQL RLS remains authoritative.
 */
data class AcademicScope(
    val universityId: String,
    val departmentId: String? = null,
    val batchId: String? = null,
    val sectionId: String? = null,
    val batchSemesterId: String? = null,
    val courseOfferingId: String? = null,
    // Display/routing metadata only. PostgreSQL RLS still authorizes by the IDs above.
    val departmentCode: String? = null,
    val batchCode: String? = null,
    val semesterOrdinal: Int? = null
)
