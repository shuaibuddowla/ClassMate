package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.domain.academic.AcademicCatalog
import com.shuaib.classmate.domain.academic.AcademicCatalogRepository
import com.shuaib.classmate.domain.academic.BatchInfo
import com.shuaib.classmate.domain.academic.BatchSemesterInfo
import com.shuaib.classmate.domain.academic.CourseInfo
import com.shuaib.classmate.domain.academic.CourseKind
import com.shuaib.classmate.domain.academic.CourseOfferingInfo
import com.shuaib.classmate.domain.academic.DepartmentInfo
import com.shuaib.classmate.domain.academic.SectionInfo
import com.shuaib.classmate.domain.academic.SemesterInfo
import com.shuaib.classmate.domain.academic.SemesterState
import com.shuaib.classmate.domain.academic.UniversityInfo
import com.shuaib.classmate.domain.auth.SessionRepository
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Singleton
class SupabaseAcademicCatalogRepository @Inject constructor(
    private val clientProvider: SupabaseClientProvider,
    private val sessionRepository: SessionRepository
) : AcademicCatalogRepository {

    override suspend fun loadAccessibleCatalog(): Result<AcademicCatalog> = runCatching {
        check(clientProvider.isConfigured) { "Supabase is not configured." }
        val session = sessionRepository.refresh().getOrThrow()
        val client = clientProvider.client

        coroutineScope {
            val university = async {
                client.from("universities")
                    .select { filter { eq("id", session.universityId) } }
                    .decodeSingle<UniversityRow>()
            }
            val departments = async {
                client.from("departments").select().decodeList<DepartmentRow>()
            }
            val batches = async {
                client.from("batches").select().decodeList<BatchRow>()
            }
            val sections = async {
                client.from("sections").select().decodeList<SectionRow>()
            }
            val semesters = async {
                client.from("semesters").select().decodeList<SemesterRow>()
            }
            val batchSemesters = async {
                client.from("batch_semesters").select().decodeList<BatchSemesterRow>()
            }
            val courses = async {
                client.from("courses").select().decodeList<CourseRow>()
            }
            val offerings = async {
                client.from("course_offerings").select().decodeList<CourseOfferingRow>()
            }

            AcademicCatalog(
                university = university.await().toDomain(),
                departments = departments.await().map { it.toDomain() },
                batches = batches.await().map { it.toDomain() },
                sections = sections.await().map { it.toDomain() },
                semesters = semesters.await().map { it.toDomain() },
                batchSemesters = batchSemesters.await().map { it.toDomain() },
                courses = courses.await().map { it.toDomain() },
                courseOfferings = offerings.await().map { it.toDomain() }
            )
        }
    }

    private fun UniversityRow.toDomain() = UniversityInfo(id, code, name, emailDomain)

    private fun DepartmentRow.toDomain() =
        DepartmentInfo(id, universityId, code, name, emailPrefix)

    private fun BatchRow.toDomain() =
        BatchInfo(id, departmentId, cohortCode, displayName, admissionYear, activeBatchSemesterId)

    private fun SectionRow.toDomain() = SectionInfo(id, batchId, code, name)

    private fun SemesterRow.toDomain() = SemesterInfo(id, universityId, ordinal, name)

    private fun BatchSemesterRow.toDomain() = BatchSemesterInfo(
        id, batchId, semesterId,
        when (state) {
            "draft" -> SemesterState.DRAFT
            "published" -> SemesterState.PUBLISHED
            "archived" -> SemesterState.ARCHIVED
            else -> error("Unsupported semester state: $state")
        },
        startsOn,
        endsOn
    )

    private fun CourseRow.toDomain() = CourseInfo(
        id, departmentId, code, name,
        when (kind) {
            "theory" -> CourseKind.THEORY
            "lab" -> CourseKind.LAB
            else -> error("Unsupported course kind: $kind")
        }
    )

    private fun CourseOfferingRow.toDomain() =
        CourseOfferingInfo(id, batchSemesterId, courseId, sectionId)
}
