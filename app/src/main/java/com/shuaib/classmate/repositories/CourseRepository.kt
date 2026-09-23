package com.shuaib.classmate.repositories

import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.SemesterManager
import com.shuaib.classmate.utils.SubjectList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Shared course catalogue backed by MBSTU CSE Archive. */
object CourseRepository {
    private data class Observer(
        val onChanged: (List<Course>) -> Unit,
        val onError: (Exception) -> Unit
    )

    private val observers = ConcurrentHashMap<String, CopyOnWriteArrayList<Observer>>()
    private val cache = ConcurrentHashMap<String, List<Course>>()

    fun listen(
        batchId: String,
        semesterId: String,
        onChanged: (List<Course>) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration {
        val key = key(batchId, semesterId)
        val observer = Observer(onChanged, onError)
        observers.getOrPut(key) { CopyOnWriteArrayList() }.add(observer)
        cache[key]?.let(onChanged)
        refresh(batchId, semesterId)
        return ListenerRegistration { observers[key]?.remove(observer) }
    }

    fun add(
        batchId: String,
        semesterId: String,
        name: String,
        code: String,
        type: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val normalizedType = type.lowercase().takeIf { it in COURSE_TYPES } ?: "regular"
        ArchiveLibraryRepository.addCourse(
            batchId,
            semesterId,
            name,
            code,
            normalizedType,
            onSuccess = { refresh(batchId, semesterId, onSuccess) },
            onFailure = onFailure
        )
    }

    fun refresh(batchId: String, semesterId: String, afterRefresh: (() -> Unit)? = null) {
        val key = key(batchId, semesterId)
        ArchiveLibraryRepository.load(batchId, semesterId, { courses, _ ->
            val sorted = courses.filter { it.name.isNotBlank() }.sortedBy { it.name.lowercase() }
            cache[key] = sorted
            SubjectList.replaceForContext(batchId, semesterId, sorted.map { it.toSubject() })
            observers[key]?.forEach { it.onChanged(sorted) }
            afterRefresh?.invoke()
        }, { error ->
            observers[key]?.forEach { it.onError(error) }
        })
    }

    private fun key(batchId: String, semesterId: String): String =
        "${AppContextManager.normalizeBatch(batchId)}:${SemesterManager.normalizeSemester(semesterId)}"

    val COURSE_TYPES = setOf("regular", "lab", "syllabus")
}
