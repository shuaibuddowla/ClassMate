package com.shuaib.classmate.utils

import java.util.concurrent.ConcurrentHashMap

/** Firestore-backed compatibility cache. No course names are bundled in the APK. */
object SubjectList {
    private val subjectsByContext = ConcurrentHashMap<String, List<Subject>>()

    val subjects: List<Subject>
        get() = getSubjectsForSemester(AppContextManager.getSemesterId())

    fun replaceForContext(batchId: String, semesterId: String, subjects: List<Subject>) {
        subjectsByContext[key(batchId, semesterId)] = subjects
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    fun getSubjectsForSemester(semester: String): List<Subject> =
        subjectsByContext[key(AppContextManager.getBatchId(), semester)].orEmpty()

    fun getAllKnownSubjects(): List<Subject> = subjectsByContext.values.flatten()
        .distinctBy { it.name.lowercase() }

    fun codeFor(subjectName: String, semester: String = AppContextManager.getSemesterId()): String =
        getSubjectsForSemester(semester)
            .firstOrNull { it.name.equals(subjectName, ignoreCase = true) }
            ?.code.orEmpty()

    private fun key(batchId: String, semesterId: String): String =
        "${batchId.lowercase()}:${SemesterManager.normalizeSemester(semesterId).lowercase()}"
}

data class Subject(
    val name: String,
    val code: String = "",
    val type: String = "regular"
)
