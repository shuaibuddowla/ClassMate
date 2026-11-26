package com.shuaib.classmate.workers

import android.content.Context
import androidx.work.*
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.repositories.NoticeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingNoticeAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isPdf: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        put("mime_type", mimeType)
        put("size_bytes", sizeBytes)
        put("is_pdf", isPdf)
    }

    companion object {
        fun fromJson(json: JSONObject): PendingNoticeAttachment = PendingNoticeAttachment(
            uri = json.optString("uri"),
            name = json.optString("name", "attachment"),
            mimeType = json.optString("mime_type", "image/jpeg"),
            sizeBytes = json.optLong("size_bytes", 0L),
            isPdf = json.optBoolean("is_pdf", false)
        )
    }
}

data class PendingNotice(
    val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val title: String?,
    val body: String,
    val courseId: String? = null,
    val isTeacher: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<PendingNoticeAttachment> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("batch_id", batchId)
        put("title", title ?: "")
        put("body", body)
        put("course_id", courseId ?: "")
        put("is_teacher", isTeacher)
        put("timestamp", timestamp)
        put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): PendingNotice {
            val attArr = json.optJSONArray("attachments")
            val atts = buildList {
                if (attArr != null) {
                    for (i in 0 until attArr.length()) {
                        attArr.optJSONObject(i)?.let { obj ->
                            val u = obj.optString("uri")
                            if (u.isNotBlank()) add(PendingNoticeAttachment.fromJson(obj))
                        }
                    }
                }
            }
            return PendingNotice(
                id = json.optString("id", UUID.randomUUID().toString()),
                batchId = json.optString("batch_id"),
                title = json.optString("title").takeIf { it.isNotBlank() },
                body = json.optString("body"),
                courseId = json.optString("course_id").takeIf { it.isNotBlank() },
                isTeacher = json.optBoolean("is_teacher", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                attachments = atts
            )
        }
    }
}

object OfflineNoticeQueue {
    private const val PREFS_NAME = "cm_offline_notices"
    private const val KEY_QUEUE = "pending_notices_queue"

    @Synchronized
    fun enqueue(context: Context, notice: PendingNotice) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getAll(context).toMutableList()
        list.removeAll { it.id == notice.id }
        list.add(notice)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()

        // Trigger WorkManager job
        scheduleWork(context)
    }

    @Synchronized
    fun getAll(context: Context): List<PendingNotice> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        val list = mutableListOf<PendingNotice>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(PendingNotice.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getAll(context).filterNot { it.id == id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    fun scheduleWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<OfflineNoticeUploadWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "OfflineNoticeUploadWork",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}

class OfflineNoticeUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val queue = OfflineNoticeQueue.getAll(applicationContext)
        if (queue.isEmpty()) return@withContext Result.success()

        ClassMateAuthApi.attach(applicationContext)
        var anyFailed = false

        for (item in queue) {
            try {
                var finalTitle = item.title
                var finalBody = item.body

                // If title was not detected, try AI title detection
                if (finalTitle.isNullOrBlank()) {
                    runCatching {
                        val reqId = UUID.randomUUID().toString()
                        val response = ClassMateAuthApi.ai(
                            JSONObject()
                                .put("mode", "auto_notice")
                                .put("batch_id", item.batchId)
                                .put("request_id", reqId)
                                .put("text", item.body)
                        )
                        val res = response.optJSONObject("result")
                        finalTitle = res?.optString("title")?.trim()
                        val newBody = res?.optString("body")?.trim()
                        if (!newBody.isNullOrBlank()) finalBody = newBody
                    }
                }

                if (finalTitle.isNullOrBlank()) {
                    finalTitle = item.body.lines().firstOrNull()?.trim()?.take(50) ?: "Notice"
                }

                val stagedAttachments = JSONArray()
                for (att in item.attachments) {
                    val parsedUri = runCatching { android.net.Uri.parse(att.uri) }.getOrNull() ?: continue
                    val staged = ClassMateAuthApi.uploadStagedNoticeAttachment(
                        context = applicationContext,
                        uri = parsedUri,
                        fileName = att.name,
                        mimeType = att.mimeType,
                        sizeHint = att.sizeBytes
                    )
                    stagedAttachments.put(staged)
                }

                ClassMateAuthApi.rpc(
                    "post_notice",
                    JSONObject().put("target_batch", item.batchId)
                        .put("target_course", if (item.isTeacher && !item.courseId.isNullOrBlank()) item.courseId else JSONObject.NULL)
                        .put("notice_title", finalTitle)
                        .put("notice_body", finalBody)
                        .put("target_attachments", stagedAttachments)
                        .put("client_request_id", item.id)
                )

                OfflineNoticeQueue.remove(applicationContext, item.id)
            } catch (e: Exception) {
                anyFailed = true
            }
        }

        runCatching {
            NoticeRepository.getInstance(applicationContext).syncFromSupabase()
        }

        if (anyFailed) Result.retry() else Result.success()
    }
}
