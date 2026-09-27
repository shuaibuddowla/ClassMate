package com.shuaib.classmate.repositories

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.firebase.Timestamp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.data.remote.supabase.SupabaseAcademicResourceRepository
import com.shuaib.classmate.network.BackendApiClient
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.SemesterManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

object ArchiveLibraryRepository {
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val v2Resources by lazy { SupabaseAcademicResourceRepository() }

    val usesSupabaseCatalog: Boolean get() = v2Resources.isConfigured

    fun load(
        batchId: String,
        semesterId: String,
        onSuccess: (List<Course>, List<PdfFile>) -> Unit,
        onFailure: (Exception) -> Unit
    ) = runAsync(onFailure) {
        val useV2Catalog = usesSupabaseCatalog
        val v2 = if (useV2Catalog) runBlocking { v2Resources.load(batchId, semesterId) } else null

        val archiveResult = runCatching {
            val batch = archiveBatch(batchId)
            val semester = semesterNumber(semesterId)
            val request = BackendApiClient.authenticated(
                Request.Builder().url(
                    BackendApiClient.url("v1/archive/archive") +
                        "?batchId=$batch&semesterNumber=$semester"
                )
            )
            val root = executeJson(request)
            val courses = gson.fromJson(root.getAsJsonArray("courses"), Array<ArchiveCourse>::class.java)
                .orEmpty()
                .map { it.toCourse(semesterId) }
            val categories = courses.associate { it.id to it.type }
            val resources = gson.fromJson(root.getAsJsonArray("resources"), Array<ArchiveResource>::class.java)
                .orEmpty()
                .map { it.toPdfFile(semesterId, categories[it.courseId]) }
            courses to resources
        }
        // In V2, Supabase owns the academic course catalogue. Archive remains
        // an additional source only for already-published legacy resources.
        val data = if (useV2Catalog && v2 != null) {
            val legacyResources = archiveResult.getOrNull()?.second.orEmpty()
            val archiveCourses = archiveResult.getOrNull()?.first.orEmpty()
            val mirroredCourses = v2.first.map { course ->
                val archiveCourse = archiveCourses.firstOrNull {
                    (course.code.isNotBlank() && it.code.equals(course.code, true)) ||
                        it.name.equals(course.name, true)
                }
                course.copy(archiveId = archiveCourse?.id.orEmpty())
            }
            mirroredCourses to (v2.second + legacyResources).distinctBy { "${it.provider}:${it.id}" }
        } else archiveResult.getOrThrow()
        mainHandler.post { onSuccess(data.first, data.second) }
    }

    fun addCourse(
        batchId: String,
        semesterId: String,
        name: String,
        code: String,
        type: String,
        onSuccess: (Course) -> Unit,
        onFailure: (Exception) -> Unit
    ) = runAsync(onFailure) {
        if (usesSupabaseCatalog) {
            val course = runBlocking { v2Resources.addCourse(batchId, semesterId, name, code, type) }
            mainHandler.post { onSuccess(course) }
            return@runAsync
        }
        val payload = mapOf(
            "batchId" to archiveBatch(batchId),
            "semesterNumber" to semesterNumber(semesterId),
            "courseName" to name.trim(),
            "courseCode" to code.trim().uppercase(),
            "category" to type
        )
        val request = BackendApiClient.authenticated(
            Request.Builder()
                .url(BackendApiClient.url("v1/archive/courses"))
                .post(gson.toJson(payload).toRequestBody(JSON))
        )
        val course = gson.fromJson(executeJson(request).get("course"), ArchiveCourse::class.java)
        mainHandler.post { onSuccess(course.toCourse(semesterId)) }
    }

    fun deleteResource(id: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit, provider: String = "archive") =
        runAsync(onFailure) {
            if (provider == "supabase" && usesSupabaseCatalog) {
                runBlocking { v2Resources.delete(id) }
                mainHandler.post(onSuccess)
                return@runAsync
            }
            val request = BackendApiClient.authenticated(
                Request.Builder()
                    .url(BackendApiClient.url("v1/archive/resources/$id/delete"))
                    .post("{}".toRequestBody(JSON))
            )
            executeJson(request)
            mainHandler.post(onSuccess)
        }

    fun loadResource(
        id: String,
        semesterId: String,
        onSuccess: (PdfFile) -> Unit,
        onFailure: (Exception) -> Unit
    ) = runAsync(onFailure) {
        val request = BackendApiClient.authenticated(
            Request.Builder().url(BackendApiClient.url("v1/archive/resource/$id"))
        )
        val resource = gson.fromJson(
            executeJson(request).get("resource"),
            ArchiveResource::class.java
        )
        mainHandler.post { onSuccess(resource.toPdfFile(semesterId, null)) }
    }

    fun resolveDownloadUrl(id: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit, provider: String = "archive") =
        runAsync(onFailure) {
            if (provider == "supabase" && usesSupabaseCatalog) {
                val url = runBlocking { v2Resources.resolveDownloadUrl(id) }
                mainHandler.post { onSuccess(url) }
                return@runAsync
            }
            val request = BackendApiClient.authenticated(
                Request.Builder().url(BackendApiClient.url("v1/archive/download/$id"))
            )
            val url = executeJson(request).get("url")?.asString.orEmpty()
            mainHandler.post { onSuccess(url) }
        }

    fun upload(
        context: Context,
        uri: Uri,
        batchId: String,
        semesterId: String,
        course: Course,
        title: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        materialType: String,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        description: String = ""
    ) = runAsync(onFailure) {
        // Library bytes and upload metadata are deliberately sent through the
        // existing Archive API: it writes the R2 object and the archive website
        // database record in one authorized flow.
        val archiveCourseId = course.archiveId.takeIf(String::isNotBlank) ?: run {
            val archivePayload = mapOf(
                "batchId" to archiveBatch(batchId),
                "semesterNumber" to semesterNumber(semesterId),
                "courseName" to course.name.trim(),
                "courseCode" to course.code.trim().uppercase(),
                "category" to if (course.type == "lab") "lab" else "regular"
            )
            val archiveRequest = BackendApiClient.authenticated(
                Request.Builder().url(BackendApiClient.url("v1/archive/courses"))
                    .post(gson.toJson(archivePayload).toRequestBody(JSON))
            )
            gson.fromJson(executeJson(archiveRequest).get("course"), ArchiveCourse::class.java)?.id
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("Archive did not create a course reference for this upload.")
        }
        val payload = mapOf(
            "batchId" to archiveBatch(batchId),
            "semesterNumber" to semesterNumber(semesterId),
            "courseId" to archiveCourseId,
            "title" to title.trim(),
            "materialType" to materialType,
            "fileName" to fileName,
            "fileSize" to sizeBytes,
            "mimeType" to mimeType,
            "tags" to emptyList<String>()
        )
        val create = BackendApiClient.authenticated(
            Request.Builder().url(BackendApiClient.url("v1/archive/uploads"))
                .post(gson.toJson(payload).toRequestBody(JSON))
        )
        val session = executeJson(create)
        val id = session.get("id")?.asString ?: throw IOException("Archive did not return an upload id.")
        val signedUrl = session.get("url")?.asString ?: throw IOException("Archive did not return a secure upload URL.")
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = sizeBytes
            override fun writeTo(sink: okio.BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        if (sizeBytes > 0) onProgress(((sent * 100) / sizeBytes).toInt().coerceIn(0, 100))
                    }
                } ?: throw IOException("Unable to read the selected file.")
            }
        }
        client.newCall(Request.Builder().url(signedUrl).put(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("File transfer failed (${response.code}).")
        }
        val complete = BackendApiClient.authenticated(
            Request.Builder().url(BackendApiClient.url("v1/archive/uploads/$id/complete"))
                .post("{}".toRequestBody(JSON))
        )
        executeJson(complete)
        mainHandler.post { onSuccess(id) }
    }

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { gson.fromJson(text, JsonObject::class.java).get("error")?.asString }
                .getOrNull()
                .orEmpty()
            throw IOException(message.ifBlank { "Archive request failed (${response.code})" })
        }
        gson.fromJson(text, JsonObject::class.java) ?: JsonObject()
    }

    private fun runAsync(onFailure: (Exception) -> Unit, action: () -> Unit) {
        Thread {
            try {
                action()
            } catch (error: Exception) {
                mainHandler.post { onFailure(error) }
            }
        }.start()
    }

    fun archiveBatch(batchId: String): String {
        val match = Regex("^([a-zA-Z]+)[-_ ]?(\\d+)$").matchEntire(batchId.trim())
        return match?.let { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" } ?: batchId
    }

    fun semesterNumber(semesterId: String): Int =
        Regex("[1-8]").find(SemesterManager.normalizeSemester(semesterId))?.value?.toIntOrNull() ?: 1

    private fun timestamp(value: String?): Timestamp? = runCatching {
        value?.takeIf { it.isNotBlank() }?.let { Timestamp(Date.from(Instant.parse(it))) }
    }.getOrNull()

    private data class ArchiveCourse(
        val id: String = "",
        val batchId: String = "",
        val semesterNumber: Int = 0,
        val courseName: String = "",
        val courseCode: String = "",
        val category: String = "regular"
    ) {
        fun toCourse(semesterId: String) = Course(
            id = id,
            archiveId = id,
            name = courseName,
            code = courseCode,
            type = category.ifBlank { if (courseName.contains("lab", true)) "lab" else "regular" },
            batchId = batchId,
            semesterId = semesterId
        )
    }

    private data class ArchiveResource(
        val id: String = "",
        val title: String = "",
        val courseId: String = "",
        val courseName: String = "",
        val courseCode: String = "",
        val materialType: String = "",
        val uploaderName: String = "",
        val fileName: String = "",
        val fileSize: Long = 0,
        val mimeType: String = "application/octet-stream",
        val createdAt: String = ""
    ) {
        fun toPdfFile(semesterId: String, category: String?): PdfFile {
            val type = when {
                materialType.equals("Syllabus", true) -> "syllabus"
                materialType.equals("Lab", true) -> "lab"
                else -> category ?: "regular"
            }
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return PdfFile(
                id = id,
                title = title,
                subject = courseName,
                uploadedBy = uploaderName,
                fileId = id,
                timestamp = timestamp(createdAt),
                courseCode = courseCode,
                courseType = type,
                fileType = extension.ifBlank { "other" },
                mimeType = mimeType,
                sizeBytes = fileSize,
                provider = "archive",
                createdAt = timestamp(createdAt),
                semester = semesterId
            )
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
}
