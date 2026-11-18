package com.shuaib.classmate.workers

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import java.io.File
import java.util.concurrent.TimeUnit

class LibraryUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val tasks = LibraryUploadManager.getPendingTasks(applicationContext)
        if (tasks.isEmpty()) {
            return Result.success()
        }

        try {
            ClassMateAuthApi.attach(applicationContext)
            if (ClassMateAuthApi.hasSavedSession()) {
                ClassMateAuthApi.restoreSession()
            }
        } catch (_: Exception) {}

        var hasTransientError = false

        for (task in tasks) {
            val file = File(task.filePath)
            if (!file.exists()) {
                LibraryUploadManager.failTask(applicationContext, task.id, "Local file no longer exists")
                continue
            }

            val notifId = (task.id.hashCode() and 0x7FFFFFFF)
            showProgressNotification(notifId, task.title, 0, 0, task.sizeBytes)

            try {
                var lastProgressUpdate = 0L

                ClassMateAuthApi.uploadResource(
                    resolver = null,
                    uri = null,
                    localFile = file,
                    batchId = task.batchId,
                    semesterCourseId = task.courseId,
                    title = task.title,
                    mimeType = task.mimeType,
                    size = task.sizeBytes,
                    category = task.category,
                    onProgress = { uploaded, total ->
                        val percent = if (total > 0) ((uploaded * 100) / total).toInt() else 0
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 300 || percent == 100) {
                            lastProgressUpdate = now
                            LibraryUploadManager.notifyProgress(task.id, task.title, percent, uploaded, total)
                            showProgressNotification(notifId, task.title, percent, uploaded, total)
                        }
                    }
                )

                LibraryUploadManager.completeTask(applicationContext, task.id)
                showCompletedNotification(notifId, task.title)
            } catch (e: Throwable) {
                val msg = e.message ?: "Upload failed"
                if (isFatalError(e)) {
                    LibraryUploadManager.failTask(applicationContext, task.id, msg)
                    showFailedNotification(notifId, task.title, msg)
                } else {
                    val retryCount = LibraryUploadManager.recordRetry(applicationContext, task.id, msg)
                    if (retryCount >= 5) {
                        val fatalMsg = "Upload stopped after 5 failed attempts: $msg"
                        LibraryUploadManager.failTask(applicationContext, task.id, fatalMsg)
                        showFailedNotification(notifId, task.title, fatalMsg)
                    } else {
                        hasTransientError = true
                        LibraryUploadManager.notifyWaitingForNetwork(task.id, task.title)
                        showWaitingNotification(notifId, task.title)
                        break // Pause queue on network disconnect so other files aren't burned while offline
                    }
                }
            }
        }

        return if (hasTransientError) Result.retry() else Result.success()
    }

    private fun isFatalError(e: Throwable): Boolean {
        if (e is IllegalArgumentException || e is IllegalStateException) return true
        val msg = e.message.orEmpty()
        if (msg.contains("Session expired", ignoreCase = true)) return true
        if (msg.contains("Selected file unavailable", ignoreCase = true)) return true
        if (msg.contains("exceeds limit", ignoreCase = true) || msg.contains("between 1 byte and 500 MB", ignoreCase = true)) return true

        val httpCodeRegex = Regex("""(?:HTTP\s+|:\s*|^)(\d{3})""", RegexOption.IGNORE_CASE)
        val match = httpCodeRegex.find(msg)
        if (match != null) {
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 400..499 && code != 408 && code != 429) {
                return true
            }
        }
        return false
    }

    private fun showProgressNotification(notifId: Int, title: String, percent: Int, uploaded: Long, total: Long) {
        val uploadedMb = String.format(java.util.Locale.US, "%.1f", uploaded / (1024.0 * 1024.0))
        val totalMb = String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0))
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_generic)
            .setContentTitle("Uploading to Library")
            .setContentText("$title · $percent% ($uploadedMb / $totalMb MB)")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notificationManager.notify(notifId, builder.build())
    }

    private fun showWaitingNotification(notifId: Int, title: String) {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_generic)
            .setContentTitle("Upload paused")
            .setContentText("$title · Waiting for internet connection to resume")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notificationManager.notify(notifId, builder.build())
    }

    private fun showCompletedNotification(notifId: Int, title: String) {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_generic)
            .setContentTitle("Upload complete")
            .setContentText("\"$title\" has been added to the course library")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notificationManager.notify(notifId, builder.build())
    }

    private fun showFailedNotification(notifId: Int, title: String, error: String) {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_generic)
            .setContentTitle("Upload failed")
            .setContentText("$title: $error")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notificationManager.notify(notifId, builder.build())
    }

    companion object {
        const val CHANNEL_ID = "classmate_library_uploads"
        const val UNIQUE_WORK_NAME = "LibraryUploadWorker"

        fun enqueue(context: Context, immediate: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<LibraryUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
