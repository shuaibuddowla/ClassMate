package com.shuaib.classmate.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONObject
import java.io.IOException

class AttendanceSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = ClassMateDatabase.getInstance(applicationContext)
        val dao = db.pendingAttendanceDao()
        val pendingScans = dao.getAllPending()

        if (pendingScans.isEmpty()) return Result.success()

        var hasTransientError = false

        for (scan in pendingScans) {
            try {
                val params = JSONObject().apply {
                    put("target_session", scan.sessionId)
                    put("qr_token", scan.qrToken)
                    put("time_window", scan.timeWindow)
                }
                ClassMateAuthApi.rpc("mark_attendance", params)
                // Successfully synced
                dao.delete(scan.sessionId)
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                // If it's a permanent HTTP error (e.g. 400, 401, 403, 404, 422, or already marked), delete it
                if (msg.startsWith("400") || msg.startsWith("401") || msg.startsWith("403") ||
                    msg.startsWith("404") || msg.startsWith("422") || msg.contains("Invalid or expired") ||
                    msg.contains("not enrolled")
                ) {
                    dao.delete(scan.sessionId)
                } else {
                    hasTransientError = true
                }
            } catch (e: Exception) {
                // Unknown exception, remove to prevent endless poisoning
                dao.delete(scan.sessionId)
            }
        }

        return if (hasTransientError) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "AttendanceSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
