package com.shuaib.classmate.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "pending_attendance_scans")
data class PendingAttendanceScanEntity(
    @PrimaryKey val sessionId: String,
    val timeWindow: Long,
    val qrToken: String,
    val scannedAtMillis: Long,
    val retryCount: Int = 0
)

@Dao
interface PendingAttendanceDao {
    @Query("SELECT * FROM pending_attendance_scans ORDER BY scannedAtMillis ASC")
    suspend fun getAllPending(): List<PendingAttendanceScanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scan: PendingAttendanceScanEntity)

    @Query("DELETE FROM pending_attendance_scans WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("SELECT COUNT(*) FROM pending_attendance_scans")
    suspend fun countPending(): Int
}
