package com.shuaib.classmate.workers

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shuaib.classmate.data.offline.OfflineFundQueue
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfflineFundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pendingList = OfflineFundQueue.getAll(applicationContext)
        if (pendingList.isEmpty()) {
            return@withContext Result.success()
        }

        try {
            ClassMateAuthApi.attach(applicationContext)
            if (!ClassMateAuthApi.restoreSession()) {
                if (ClassMateAuthApi.accessToken.isNullOrBlank()) {
                    return@withContext Result.retry()
                }
            }

            var syncedCount = 0
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            for (tx in pendingList) {
                try {
                    val dateStr = dateFormat.format(Date(tx.timestamp))
                    val params = JSONObject().apply {
                        put("target_batch", tx.batchId)
                        put("trans_type", tx.type)
                        put("trans_amount", tx.amount)
                        put("trans_title", tx.title)
                        put("trans_student_name", tx.studentName ?: JSONObject.NULL)
                        put("trans_student_id", tx.studentId ?: JSONObject.NULL)
                        put("trans_date", dateStr)
                        if (!tx.studentProfileId.isNullOrBlank()) {
                            put("trans_student_profile_id", tx.studentProfileId)
                        }
                    }

                    ClassMateAuthApi.rpc("record_batch_fund_transaction", params)
                    OfflineFundQueue.remove(applicationContext, tx.id)
                    syncedCount++
                } catch (e: Exception) {
                    // Stop or continue next; if network issue, retry worker later
                    val isNetwork = e is java.io.IOException || e.message?.contains("network", ignoreCase = true) == true
                    if (isNetwork) {
                        return@withContext Result.retry()
                    }
                }
            }

            if (syncedCount > 0) {
                val title = "Batch Fund Synced"
                val body = if (syncedCount == 1) {
                    "1 offline transaction was recorded to the database."
                } else {
                    "$syncedCount offline transactions were recorded to the database."
                }
                NotificationHelper.showFundSyncNotification(applicationContext, title, body)

                // Broadcast local event to refresh activity if currently active
                val intent = Intent(ACTION_FUND_SYNCED).apply {
                    setPackage(applicationContext.packageName)
                    putExtra("synced_count", syncedCount)
                }
                applicationContext.sendBroadcast(intent)
            }

            if (OfflineFundQueue.getAll(applicationContext).isNotEmpty()) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "ClassMateOfflineFundSyncWorker"
        const val ACTION_FUND_SYNCED = "com.shuaib.classmate.ACTION_FUND_SYNCED"
    }
}
