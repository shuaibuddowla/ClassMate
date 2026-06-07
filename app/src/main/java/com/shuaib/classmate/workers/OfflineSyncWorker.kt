package com.shuaib.classmate.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.Source
import com.shuaib.classmate.repositories.NoticeRepository
import com.shuaib.classmate.repositories.TimetableRepository

class OfflineSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            NoticeRepository.getInstance(applicationContext).syncFromFirestore(Source.SERVER)
            TimetableRepository.getInstance(applicationContext).syncAllFromFirestore(Source.SERVER)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "OfflineFirstSync"
    }
}
