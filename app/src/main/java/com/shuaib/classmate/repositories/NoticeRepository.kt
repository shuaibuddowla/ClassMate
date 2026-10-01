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
import com.shuaib.classmate.data.remote.supabase.SupabaseClientProvider
import com.shuaib.classmate.data.remote.supabase.SupabaseNoticeFeed
import com.shuaib.classmate.notices.NoticeEngagement
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NoticeRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val noticeDao = ClassMateDatabase.getInstance(appContext).noticeDao()
    private val db = FirestoreManager.db
    private val supabaseFeed = SupabaseNoticeFeed(
        SupabaseClientProvider(com.google.firebase.auth.FirebaseAuth.getInstance())
    )

    fun getNoticesCollection(batchId: String = AppContextManager.getBatchId()): CollectionReference {
        val norm = AppContextManager.normalizeBatch(batchId)
        return db.collection("batches").document(norm).collection("notices")
    }

    fun observeNotices(batchId: String = AppContextManager.getBatchId(), limit: Int = 80): Flow<List<Notice>> {
        return noticeDao.observeNotices(AppContextManager.normalizeBatch(batchId), limit).map { entities ->
            entities.map { it.toNotice() }
        }
    }

    fun observeCurrentWeekNotices(batchId: String = AppContextManager.getBatchId()): Flow<List<Notice>> {
        val startMillis = currentWeekStart().toEpochMilli()
        return noticeDao.observeNoticesSince(AppContextManager.normalizeBatch(batchId), startMillis).map { entities ->
            entities.map { it.toNotice() }
        }
    }

    fun observeNotice(noticeId: String): Flow<Notice?> {
        return noticeDao.observeNotice(noticeId).map { entity -> entity?.toNotice() }
    }

    fun startRealtimeSync(scope: CoroutineScope, batchId: String = AppContextManager.getBatchId()): ListenerRegistration {
        val normBatch = AppContextManager.normalizeBatch(batchId)
        return getNoticesCollection(normBatch)
            .whereGreaterThanOrEqualTo("timestamp", com.google.firebase.Timestamp(java.util.Date.from(currentWeekStart())))
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(60)
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
                .whereGreaterThanOrEqualTo("timestamp", com.google.firebase.Timestamp(java.util.Date.from(currentWeekStart())))
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(60)
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

    suspend fun syncFromSupabase(batchId: String = AppContextManager.getBatchId()) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (!supabaseFeed.isConfigured) return@withContext
            val normBatch = AppContextManager.normalizeBatch(batchId)
            val notices = supabaseFeed.load(normBatch, since = currentWeekStart(), limit = 60)
            if (notices.isNotEmpty()) {
                noticeDao.upsertAll(notices.map { NoticeEntity.fromNotice(it) })
                WidgetUpdater.refresh(appContext, syncTodayTimetable = false)
            }
        }

    suspend fun syncOlderNotices(
        batchId: String = AppContextManager.getBatchId(),
        before: Instant = currentWeekStart()
    ): Instant? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val normBatch = AppContextManager.normalizeBatch(batchId)
            val loaded = mutableListOf<Notice>()
            if (supabaseFeed.isConfigured) {
                val notices = supabaseFeed.load(normBatch, before = before, limit = 40)
                loaded += notices
                if (notices.isNotEmpty()) noticeDao.upsertAll(notices.map { NoticeEntity.fromNotice(it) })
            }
            runCatching {
                val snapshot = getNoticesCollection(normBatch)
                    .whereLessThan("timestamp", com.google.firebase.Timestamp(java.util.Date.from(before)))
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(40)
                    .get(Source.SERVER)
                    .await()
                val notices = snapshot.documents.map { NoticeUi.parseNotice(it).copy(batchId = normBatch) }
                    .filterNot { it.isDeleted }
                loaded += notices
                if (notices.isNotEmpty()) noticeDao.upsertAll(notices.map { NoticeEntity.fromNotice(it) })
            }
            loaded.mapNotNull { it.timestamp?.toDate()?.toInstant() }.minOrNull()
        }

    suspend fun loadSupabaseEngagement(noticeIds: Set<String>): Map<String, NoticeEngagement> =
        supabaseFeed.loadEngagement(noticeIds)

    suspend fun setSupabaseLike(noticeId: String, liked: Boolean) =
        supabaseFeed.setLiked(noticeId, liked)

    suspend fun setSupabasePersonalPin(noticeId: String, pinned: Boolean) =
        supabaseFeed.setPersonalPin(noticeId, pinned)

    suspend fun setSupabaseGlobalPin(noticeId: String, pinned: Boolean) =
        supabaseFeed.setGlobalPin(noticeId, pinned)

    suspend fun deleteSupabaseNotice(noticeId: String) {
        supabaseFeed.deleteNotice(noticeId)
        noticeDao.markDeleted(noticeId)
    }

    suspend fun publishSupabaseBatchNotice(batchCode: String, title: String, body: String) =
        supabaseFeed.publishBatchNotice(batchCode, title, body)

    suspend fun updateSupabaseNotice(noticeId: String, title: String, body: String) =
        supabaseFeed.updateNotice(noticeId, title, body)

    suspend fun publishSupabaseResourceNotice(
        batchCode: String,
        title: String,
        body: String,
        resourceId: String,
        resourceTitle: String,
        subject: String,
        provider: String = "archive"
    ) = supabaseFeed.publishResourceNotice(batchCode, title, body, resourceId, resourceTitle, subject, provider)

    suspend fun uploadSupabaseNoticeImage(
        context: Context,
        noticeId: String,
        uri: android.net.Uri,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        onProgress: (Int) -> Unit = {}
    ) = supabaseFeed.uploadNoticeImage(context, noticeId, uri, fileName, mimeType, sizeBytes, onProgress)

    suspend fun publishSupabaseClassCancellation(
        batchCode: String, offeringId: String, date: String, title: String, body: String
    ) = supabaseFeed.publishClassCancellation(batchCode, offeringId, date, title, body)

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

        fun currentWeekStart(zoneId: ZoneId = ZoneId.systemDefault()): Instant =
            LocalDate.now(zoneId)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
                .atStartOfDay(zoneId)
                .toInstant()
    }
}
