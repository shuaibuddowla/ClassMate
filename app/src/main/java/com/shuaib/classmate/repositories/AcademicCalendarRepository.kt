package com.shuaib.classmate.repositories

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.shuaib.classmate.models.AcademicCalendarException
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

class AcademicCalendarRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun getActiveExceptionForDate(date: LocalDate): AcademicCalendarException? {
        return try {
            val dateText = date.toString()
            val snapshot = firestore.collection(AcademicCalendarException.COLLECTION)
                .whereEqualTo("isActive", true)
                .get()
                .await()

            snapshot.documents
                .mapNotNull { doc -> doc.toObject(AcademicCalendarException::class.java)?.copy(id = doc.id) }
                .filter { it.type in AcademicCalendarException.SUPPORTED_TYPES }
                .filter { exception ->
                    val start = exception.startDate.toLocalDateOrNull()
                    val end = exception.endDate.toLocalDateOrNull()
                    start != null && end != null && !date.isBefore(start) && !date.isAfter(end)
                }
                .sortedBy { it.startDate }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun areAllClassesSuspended(date: LocalDate): Boolean {
        return getActiveExceptionForDate(date)?.let {
            it.isActive && it.scope == AcademicCalendarException.SCOPE_ALL_CLASSES
        } == true
    }

    fun observeActiveExceptions(
        onResult: (List<AcademicCalendarException>) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration {
        return firestore.collection(AcademicCalendarException.COLLECTION)
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                onResult(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(AcademicCalendarException::class.java)?.copy(id = doc.id)
                }.sortedBy { it.startDate })
            }
    }

    fun observeAllExceptions(
        onResult: (List<AcademicCalendarException>) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration {
        return firestore.collection(AcademicCalendarException.COLLECTION)
            .orderBy("startDate", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                onResult(snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.toObject(AcademicCalendarException::class.java)?.copy(id = doc.id)
                })
            }
    }

    suspend fun saveException(exception: AcademicCalendarException): Result<Unit> {
        val validationError = validate(exception)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        return try {
            val now = Timestamp.now()
            val collection = firestore.collection(AcademicCalendarException.COLLECTION)
            val payload = mutableMapOf<String, Any>(
                "title" to exception.title.trim(),
                "type" to exception.type,
                "startDate" to exception.startDate,
                "endDate" to exception.endDate,
                "scope" to AcademicCalendarException.SCOPE_ALL_CLASSES,
                "reason" to exception.reason.trim(),
                "isActive" to exception.isActive,
                "showHolidayBriefing" to exception.showHolidayBriefing,
                "createdBy" to exception.createdBy,
                "updatedAt" to now
            )

            if (exception.id.isBlank()) {
                payload["createdAt"] = now
                collection.add(payload).await()
            } else {
                collection.document(exception.id).update(payload).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setActive(id: String, isActive: Boolean): Result<Unit> {
        return try {
            firestore.collection(AcademicCalendarException.COLLECTION)
                .document(id)
                .update(
                    mapOf(
                        "isActive" to isActive,
                        "updatedAt" to Timestamp.now()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteException(id: String): Result<Unit> {
        return try {
            firestore.collection(AcademicCalendarException.COLLECTION).document(id).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validate(exception: AcademicCalendarException): String? {
        if (exception.title.isBlank()) return "Title is required"
        if (exception.startDate.isBlank()) return "Start date is required"
        if (exception.endDate.isBlank()) return "End date is required"
        if (exception.type !in AcademicCalendarException.SUPPORTED_TYPES) return "Invalid exception type"

        val start = exception.startDate.toLocalDateOrNull() ?: return "Start date must use yyyy-MM-dd"
        val end = exception.endDate.toLocalDateOrNull() ?: return "End date must use yyyy-MM-dd"
        if (end.isBefore(start)) return "End date cannot be before start date"
        return null
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return try {
            LocalDate.parse(this)
        } catch (_: Exception) {
            null
        }
    }
}
