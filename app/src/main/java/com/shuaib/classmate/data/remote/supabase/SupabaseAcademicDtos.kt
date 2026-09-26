package com.shuaib.classmate.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UniversityRow(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("email_domain") val emailDomain: String
)

@Serializable
internal data class DepartmentRow(
    val id: String,
    @SerialName("university_id") val universityId: String,
    val code: String,
    val name: String,
    @SerialName("email_prefix") val emailPrefix: String
)

@Serializable
internal data class BatchRow(
    val id: String,
    @SerialName("department_id") val departmentId: String,
    @SerialName("cohort_code") val cohortCode: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("admission_year") val admissionYear: Int? = null,
    @SerialName("active_batch_semester_id") val activeBatchSemesterId: String? = null
)

@Serializable
internal data class SectionRow(
    val id: String,
    @SerialName("batch_id") val batchId: String,
    val code: String,
    val name: String
)

@Serializable
internal data class SemesterRow(
    val id: String,
    @SerialName("university_id") val universityId: String,
    val ordinal: Int,
    val name: String
)

@Serializable
internal data class BatchSemesterRow(
    val id: String,
    @SerialName("batch_id") val batchId: String,
    @SerialName("semester_id") val semesterId: String,
    val state: String,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null
)

@Serializable
internal data class CourseRow(
    val id: String,
    @SerialName("department_id") val departmentId: String,
    val code: String,
    val name: String,
    val kind: String
)

@Serializable
internal data class CourseOfferingRow(
    val id: String,
    @SerialName("batch_semester_id") val batchSemesterId: String,
    @SerialName("course_id") val courseId: String,
    @SerialName("section_id") val sectionId: String? = null
)
