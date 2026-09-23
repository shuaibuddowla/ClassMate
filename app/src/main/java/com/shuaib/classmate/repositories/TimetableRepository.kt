package com.shuaib.classmate.repositories

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.Source
import com.shuaib.classmate.data.FirestoreManager
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.local.TimetableEntity
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.SemesterManager
import com.shuaib.classmate.workers.OfflineSyncWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TimetableRepository private constructor(private val context: Context) {
    private val timetableDao = ClassMateDatabase.getInstance(context).timetableDao()
    private val db = FirestoreManager.db

    fun observePeriods(
        day: String,
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ): Flow<List<Period>> {
        val normalizedDay = day.lowercase()
        val normalizedSem = SemesterManager.normalizeSemester(semester)
        val normalizedBatch = batch.lowercase()
        return timetableDao.observePeriods(normalizedBatch, normalizedSem, normalizedDay)
            .map { entities -> entities.map { it.toPeriod(DateHelper.today()) } }
    }

    fun getPeriodsCollection(
        day: String,
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ): CollectionReference {
        val normalizedDay = day.lowercase()
        val normalizedSem = SemesterManager.normalizeSemester(semester)
        val normalizedBatch = batch.lowercase()
        return db.collection("batches")
            .document(normalizedBatch)
            .collection("semesters")
            .document(normalizedSem)
            .collection("timetable")
            .document(normalizedDay)
            .collection("periods")
    }

    suspend fun clearCache() {
        timetableDao.clearAll()
    }

    suspend fun syncDayFromFirestore(
        day: String,
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId(),
        source: Source = Source.DEFAULT
    ) {
        val normalizedDay = day.lowercase()
        val normalizedSem = SemesterManager.normalizeSemester(semester)
        val normalizedBatch = batch.lowercase()
        val snapshot = getPeriodsCollection(normalizedDay, normalizedSem, normalizedBatch)
            .orderBy("startTime")
            .get(source)
            .await()
        val periods = snapshot.documents.map { doc ->
            Period(
                id = doc.id,
                subject = doc.getString("subject") ?: "",
                teacher = doc.getString("teacher") ?: "",
                startTime = doc.getString("startTime") ?: "",
                endTime = doc.getString("endTime") ?: "",
                cancelDate = doc.getString("cancelDate") ?: "",
                substituteTeacher = doc.getString("substituteTeacher") ?: "",
                substituteDate = doc.getString("substituteDate") ?: ""
            )
        }
        val entities = periods.map { TimetableEntity.fromPeriod(normalizedBatch, normalizedSem, normalizedDay, it) }
        if (snapshot.metadata.isFromCache) {
            if (entities.isNotEmpty()) timetableDao.upsertAll(entities)
            return
        }
        timetableDao.replaceDay(normalizedBatch, normalizedSem, normalizedDay, entities)
    }

    suspend fun syncAllFromFirestore(
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId(),
        source: Source = Source.DEFAULT
    ) = coroutineScope {
        val normalizedSem = SemesterManager.normalizeSemester(semester)
        val normalizedBatch = batch.lowercase()
        DAYS.map { day ->
            async {
                runCatching { syncDayFromFirestore(day, normalizedSem, normalizedBatch, source) }
            }
        }.forEach { it.await() }
    }

    fun enqueueNetworkSync() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            OfflineSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        val DAYS = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")

        @Volatile private var instance: TimetableRepository? = null

        fun getInstance(context: Context): TimetableRepository {
            return instance ?: synchronized(this) {
                instance ?: TimetableRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
