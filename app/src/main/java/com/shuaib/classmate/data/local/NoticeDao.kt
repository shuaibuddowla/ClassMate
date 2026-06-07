package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices WHERE isDeleted = 0 ORDER BY isPinned DESC, timestampMillis DESC LIMIT :limit")
    fun observeNotices(limit: Int = 80): Flow<List<NoticeEntity>>

    @Query("SELECT * FROM notices WHERE id = :noticeId AND isDeleted = 0 LIMIT 1")
    fun observeNotice(noticeId: String): Flow<NoticeEntity?>

    @Upsert
    suspend fun upsertAll(notices: List<NoticeEntity>)

    @Query("UPDATE notices SET isDeleted = 1 WHERE id NOT IN (:activeIds)")
    suspend fun markMissingDeleted(activeIds: List<String>)

    @Query("UPDATE notices SET isDeleted = 1 WHERE id = :noticeId")
    suspend fun markDeleted(noticeId: String)

    @Query("UPDATE notices SET isDeleted = 1")
    suspend fun markAllDeleted()
}
