package com.shuaib.classmate.workers

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object LibraryUploadManager {
    data class Task(
        val id: String,
        val filePath: String,
        val fileName: String,
        val batchId: String,
        val courseId: String,
        val courseName: String,
        val title: String,
        val mimeType: String,
        val sizeBytes: Long,
        val category: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    enum class Status {
        QUEUED,
        UPLOADING,
        COMPLETED,
        FAILED,
        WAITING_FOR_NETWORK
    }

    data class Progress(
        val taskId: String,
        val title: String,
        val percent: Int,
        val uploadedBytes: Long,
        val totalBytes: Long,
        val status: Status,
        val error: String? = null
    )

    private val _uploadState = MutableStateFlow<Progress?>(null)
    val uploadState: StateFlow<Progress?> = _uploadState.asStateFlow()

    private const val QUEUE_FILE = "pending_library_uploads/queue.json"

    private fun queueFile(context: Context): File {
        val dir = File(context.filesDir, "pending_library_uploads")
        if (!dir.exists()) dir.mkdirs()
        return File(context.filesDir, QUEUE_FILE)
    }

    @Synchronized
    fun getPendingTasks(context: Context): List<Task> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Task>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Task(
                    id = obj.getString("id"),
                    filePath = obj.getString("file_path"),
                    fileName = obj.getString("file_name"),
                    batchId = obj.getString("batch_id"),
                    courseId = obj.getString("course_id"),
                    courseName = obj.optString("course_name"),
                    title = obj.getString("title"),
                    mimeType = obj.getString("mime_type"),
                    sizeBytes = obj.getLong("size_bytes"),
                    category = obj.getString("category"),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis())
                ))
            }
            list
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveTasks(context: Context, tasks: List<Task>) {
        val file = queueFile(context)
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("file_path", t.filePath)
                put("file_name", t.fileName)
                put("batch_id", t.batchId)
                put("course_id", t.courseId)
                put("course_name", t.courseName)
                put("title", t.title)
                put("mime_type", t.mimeType)
                put("size_bytes", t.sizeBytes)
                put("category", t.category)
                put("created_at", t.createdAt)
            })
        }
        file.writeText(arr.toString())
    }

    fun enqueueUpload(
        context: Context,
        uri: Uri,
        fileName: String,
        batchId: String,
        courseId: String,
        courseName: String,
        title: String,
        mimeType: String,
        sizeBytes: Long,
        category: String = "notes"
    ): String {
        val taskId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "pending_library_uploads").apply { mkdirs() }
        val ext = fileName.substringAfterLast('.', "dat")
        val localFile = File(dir, "${taskId}_upload.$ext")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Failed to read file from storage")

        val task = Task(
            id = taskId,
            filePath = localFile.absolutePath,
            fileName = fileName,
            batchId = batchId,
            courseId = courseId,
            courseName = courseName,
            title = title,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            category = category
        )

        synchronized(this) {
            val tasks = getPendingTasks(context).toMutableList()
            tasks.add(task)
            saveTasks(context, tasks)
        }

        _uploadState.value = Progress(
            taskId = taskId,
            title = title,
            percent = 0,
            uploadedBytes = 0L,
            totalBytes = sizeBytes,
            status = Status.QUEUED
        )

        LibraryUploadWorker.enqueue(context)
        return taskId
    }

    fun notifyProgress(taskId: String, title: String, percent: Int, uploadedBytes: Long, totalBytes: Long) {
        _uploadState.value = Progress(
            taskId = taskId,
            title = title,
            percent = percent,
            uploadedBytes = uploadedBytes,
            totalBytes = totalBytes,
            status = Status.UPLOADING
        )
    }

    fun notifyWaitingForNetwork(taskId: String, title: String) {
        val current = _uploadState.value
        _uploadState.value = Progress(
            taskId = taskId,
            title = title,
            percent = current?.percent ?: 0,
            uploadedBytes = current?.uploadedBytes ?: 0L,
            totalBytes = current?.totalBytes ?: 0L,
            status = Status.WAITING_FOR_NETWORK
        )
    }

    fun completeTask(context: Context, taskId: String) {
        val task = synchronized(this) {
            val tasks = getPendingTasks(context).toMutableList()
            val target = tasks.firstOrNull { it.id == taskId }
            if (target != null) {
                tasks.remove(target)
                saveTasks(context, tasks)
                runCatching { File(target.filePath).delete() }
            }
            target
        }
        val current = _uploadState.value
        _uploadState.value = Progress(
            taskId = taskId,
            title = task?.title ?: current?.title.orEmpty(),
            percent = 100,
            uploadedBytes = current?.totalBytes ?: 0L,
            totalBytes = current?.totalBytes ?: 0L,
            status = Status.COMPLETED
        )
    }

    fun failTask(context: Context, taskId: String, error: String?) {
        val task = synchronized(this) {
            val tasks = getPendingTasks(context).toMutableList()
            val target = tasks.firstOrNull { it.id == taskId }
            if (target != null) {
                tasks.remove(target)
                saveTasks(context, tasks)
                runCatching { File(target.filePath).delete() }
            }
            target
        }
        _uploadState.value = Progress(
            taskId = taskId,
            title = task?.title.orEmpty(),
            percent = 0,
            uploadedBytes = 0L,
            totalBytes = 0L,
            status = Status.FAILED,
            error = error
        )
    }

    fun clearState() {
        _uploadState.value = null
    }
}
