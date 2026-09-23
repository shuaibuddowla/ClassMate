package com.shuaib.classmate.repositories

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.shuaib.classmate.data.FirestoreManager
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.local.NoticeEntity
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.notices.NoticeUi
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.WidgetUpdater
import com.shuaib.classmate.workers.OfflineSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class NoticeRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val noticeDao = ClassMateDatabase.getInstance(appContext).noticeDao()
    private val db = FirestoreManager.db

    fun getNoticesCollection(batchId: String = AppContextManager.getBatchId()): CollectionReference {
        val norm = AppContextManager.normalizeBatch(batchId)
        return db.collection("batches").document(norm).collection("notices")
    }

    fun observeNotices(batchId: String = AppContextManager.getBatchId()): Flow<List<Notice>> {
        return noticeDao.observeNotices(AppContextManager.normalizeBatch(batchId)).map { entities ->
            entities.map { it.toNotice() }
        }
    }

    fun observeNotice(noticeId: String): Flow<Notice?> {
        return noticeDao.observeNotice(noticeId).map { entity -> entity?.toNotice() }
    }

    fun startRealtimeSync(scope: CoroutineScope, batchId: String = AppContextManager.getBatchId()): ListenerRegistration {
        val normBatch = AppContextManager.normalizeBatch(batchId)
        return getNoticesCollection(normBatch)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                
                scope.launch(Dispatchers.IO) {
                    val parsedNotices = snapshot.documents.map { doc ->
                        NoticeUi.parseNotice(doc).copy(batchId = normBatch)
                    }
                    
                    val toUpsert = parsedNotices.filterNot { it.isDeleted }
                    if (toUpsert.isNotEmpty()) {
                        noticeDao.upsertAll(toUpsert.map { NoticeEntity.fromNotice(it) })
                    }

                    snapshot.documentChanges.forEach { change ->
                        val noticeId = change.document.id
                        when (change.type) {
                            DocumentChange.Type.REMOVED -> noticeDao.markDeleted(noticeId)
                            DocumentChange.Type.MODIFIED, DocumentChange.Type.ADDED -> {
                                if (change.document.getBoolean("isDeleted") == true) {
                                    noticeDao.markDeleted(noticeId)
                                }
                            }
                        }
                    }
                    WidgetUpdater.refresh(appContext, syncTodayTimetable = false)
                }
            }
    }

    suspend fun cacheNotice(notice: Notice) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        noticeDao.upsertAll(listOf(NoticeEntity.fromNotice(notice)))
    }

    suspend fun syncNoticeFromFirestore(noticeId: String, batchId: String = AppContextManager.getBatchId(), source: Source = Source.DEFAULT) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val normBatch = AppContextManager.normalizeBatch(batchId)
            val snapshot = getNoticesCollection(normBatch)
                .document(noticeId)
                .get(source)
                .await()

            if (snapshot.exists()) {
                val notice = NoticeUi.parseNotice(snapshot).copy(batchId = normBatch)
                if (notice.isDeleted) {
                    if (!snapshot.metadata.isFromCache) noticeDao.markDeleted(noticeId)
                } else {
                    noticeDao.upsertAll(listOf(NoticeEntity.fromNotice(notice)))
                }
            } else if (!snapshot.metadata.isFromCache) {
                noticeDao.markDeleted(noticeId)
            }
        } catch (e: Exception) {
            android.util.Log.e("NoticeRepository", "Sync notice failed: ${e.message}")
        }
    }

    suspend fun syncFromFirestore(batchId: String = AppContextManager.getBatchId(), source: Source = Source.DEFAULT) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val normBatch = AppContextManager.normalizeBatch(batchId)
            val snapshot = getNoticesCollection(normBatch)
                .limit(80)
                .get(source)
                .await()
            val parsedNotices = snapshot.documents.map { doc ->
                NoticeUi.parseNotice(doc).copy(batchId = normBatch)
            }
            val notices = parsedNotices
                .filterNot { it.isDeleted }
                .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            noticeDao.upsertAll(notices.map { NoticeEntity.fromNotice(it) })
            if (snapshot.metadata.isFromCache) return@withContext
            parsedNotices
                .filter { it.isDeleted }
                .map { it.id }
                .filter { it.isNotBlank() }
                .forEach { noticeDao.markDeleted(it) }
            WidgetUpdater.refresh(appContext, syncTodayTimetable = false)
        } catch (e: Exception) {
            android.util.Log.e("NoticeRepository", "Sync failed: ${e.message}")
        }
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
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            OfflineSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        @Volatile private var instance: NoticeRepository? = null

        fun getInstance(context: Context): NoticeRepository {
            return instance ?: synchronized(this) {
                instance ?: NoticeRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
