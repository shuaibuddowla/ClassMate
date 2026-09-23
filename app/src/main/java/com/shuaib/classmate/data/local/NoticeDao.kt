package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices WHERE isDeleted = 0 AND batchId = :batchId ORDER BY isPinned DESC, timestampMillis DESC LIMIT :limit")
    fun observeNotices(batchId: String, limit: Int = 80): Flow<List<NoticeEntity>>

    @Query("SELECT * FROM notices WHERE id = :noticeId AND isDeleted = 0 LIMIT 1")
    fun observeNotice(noticeId: String): Flow<NoticeEntity?>

    @Query("SELECT * FROM notices WHERE isDeleted = 0 AND batchId = :batchId ORDER BY timestampMillis DESC LIMIT 1")
    fun getLatestNoticeSync(batchId: String): NoticeEntity?

    @Upsert
    suspend fun upsertAll(notices: List<NoticeEntity>)

    @Query("UPDATE notices SET isDeleted = 1 WHERE batchId = :batchId AND id NOT IN (:activeIds)")
    suspend fun markMissingDeleted(batchId: String, activeIds: List<String>)

    @Query("UPDATE notices SET isDeleted = 1 WHERE id = :noticeId")
    suspend fun markDeleted(noticeId: String)

    @Query("UPDATE notices SET isDeleted = 1 WHERE batchId = :batchId")
    suspend fun markBatchDeleted(batchId: String)

    @Query("UPDATE notices SET isDeleted = 1")
    suspend fun markAllDeleted()

    @Query("DELETE FROM notices WHERE batchId = :batchId")
    suspend fun clearBatch(batchId: String)

    @Query("DELETE FROM notices")
    suspend fun clearAll()
}
