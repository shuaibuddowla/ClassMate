package com.shuaib.classmate.domain.academic

enum class CourseKind { THEORY, LAB }

enum class SemesterState { DRAFT, PUBLISHED, ARCHIVED }

data class UniversityInfo(
    val id: String,
    val code: String,
    val name: String,
    val emailDomain: String
)

data class DepartmentInfo(
    val id: String,
    val universityId: String,
    val code: String,
    val name: String,
    val emailPrefix: String
)

data class BatchInfo(
    val id: String,
    val departmentId: String,
    val cohortCode: String,
    val displayName: String,
    val admissionYear: Int?,
    val activeBatchSemesterId: String?
)

data class SectionInfo(
    val id: String,
    val batchId: String,
    val code: String,
    val name: String
)

data class SemesterInfo(
    val id: String,
    val universityId: String,
    val ordinal: Int,
    val name: String
)

data class BatchSemesterInfo(
    val id: String,
    val batchId: String,
    val semesterId: String,
    val state: SemesterState,
    val startsOn: String?,
    val endsOn: String?
)

data class CourseInfo(
    val id: String,
    val departmentId: String,
    val code: String,
    val name: String,
    val kind: CourseKind
)

data class CourseOfferingInfo(
    val id: String,
    val batchSemesterId: String,
    val courseId: String,
    val sectionId: String?
)

data class AcademicCatalog(
    val university: UniversityInfo,
    val departments: List<DepartmentInfo>,
    val batches: List<BatchInfo>,
    val sections: List<SectionInfo>,
    val semesters: List<SemesterInfo>,
    val batchSemesters: List<BatchSemesterInfo>,
    val courses: List<CourseInfo>,
    val courseOfferings: List<CourseOfferingInfo>
)
