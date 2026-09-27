package com.shuaib.classmate.data.remote.supabase

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.SemesterManager
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class SupabaseAcademicResourceRepository {
    private val clientProvider = SupabaseClientProvider(FirebaseAuth.getInstance())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val gson = Gson()

    val isConfigured: Boolean
        get() = clientProvider.isConfigured

    suspend fun load(batchRoute: String, semesterRoute: String): Pair<List<Course>, List<PdfFile>> {
        check(isConfigured) { "Supabase is not configured." }
        val client = clientProvider.client
        val departments = client.from("departments").select().decodeList<DepartmentRow>()
        val batches = client.from("batches").select().decodeList<BatchRow>()
        val semesters = client.from("semesters").select().decodeList<SemesterRow>()
        val batchSemesters = client.from("batch_semesters").select().decodeList<BatchSemesterRow>()
        val courses = client.from("courses").select().decodeList<CourseRow>()
        val offerings = client.from("course_offerings").select().decodeList<CourseOfferingRow>()
        val resources = client.from("resources").select().decodeList<ResourceRow>()

        val departmentById = departments.associateBy { it.id }
        val semesterById = semesters.associateBy { it.id }
        val targetBatch = batches.firstOrNull { batch ->
            val department = departmentById[batch.departmentId] ?: return@firstOrNull false
            normalizeCode(department.code + batch.cohortCode) == normalizeCode(batchRoute)
        } ?: return emptyList<Course>() to emptyList()
        val targetBatchSemesterIds = batchSemesters.asSequence()
            .filter { it.batchId == targetBatch.id && it.state == "published" }
            .filter { semesterById[it.semesterId]?.ordinal == semesterOrdinal(semesterRoute) }
            .map { it.id }
            .toSet()
        if (targetBatchSemesterIds.isEmpty()) return emptyList<Course>() to emptyList()

        val courseById = courses.associateBy { it.id }
        val activeOfferings = offerings.filter { it.batchSemesterId in targetBatchSemesterIds }
        val offeringById = activeOfferings.associateBy { it.id }
        val accessibleCourses = activeOfferings.mapNotNull { offering ->
            courseById[offering.courseId]?.let { course ->
                Course(
                    id = offering.id,
                    name = course.name,
                    code = course.code,
                    type = if (course.kind.equals("lab", true)) "lab" else "regular",
                    batchId = batchRoute,
                    semesterId = semesterRoute
                )
            }
        }.distinctBy { it.code.ifBlank { it.name }.lowercase() }
        val courseByOffering = activeOfferings.mapNotNull { offering ->
            courseById[offering.courseId]?.let { offering.id to it }
        }.toMap()
        val visibleResources = resources.mapNotNull { row ->
            if (row.courseOfferingId !in offeringById) return@mapNotNull null
            val course = courseByOffering[row.courseOfferingId] ?: return@mapNotNull null
            row.toPdfFile(course, semesterRoute)
        }.sortedByDescending { it.timestamp ?: it.createdAt }
        return accessibleCourses to visibleResources
    }

    suspend fun upload(
        context: Context,
        uri: Uri,
        batchRoute: String,
        semesterRoute: String,
        course: Course,
        title: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        category: String,
        description: String,
        onProgress: (Int) -> Unit
    ): String {
        check(isConfigured) { "Supabase is not configured." }
        val client = clientProvider.client
        // Re-run the trusted identity bootstrap before upload. This turns the
        // owner allowlist entry into the global-admin role grant checked by RLS.
        client.postgrest.rpc("bootstrap_firebase_profile")
        val firebaseUid = checkNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Please sign in again." }
        val profile = client.from("profiles").select()
            .decodeList<ProfileRow>()
            .firstOrNull { it.firebaseUid == firebaseUid }
            ?: throw IOException("Your account does not have a Supabase profile yet. Sign in again.")
        if (profile.status !in setOf("active", "graduated")) {
            throw IOException("Your Supabase profile is not active. Ask the owner to allowlist your university email.")
        }
        val now = Instant.now()
        val hasPublishingGrant = client.from("role_grants").select()
            .decodeList<RoleGrantRow>()
            .any { grant ->
                grant.profileId == profile.id && grant.role in setOf("admin", "teacher", "cr") &&
                    grant.revokedAt == null && Instant.parse(grant.startsAt).let { !it.isAfter(now) } &&
                    (grant.expiresAt == null || Instant.parse(grant.expiresAt).isAfter(now))
            }
        if (!hasPublishingGrant) {
            throw IOException("Your Supabase account has no publishing role. Add the owner email to Supabase staff allowlist, then sign in again.")
        }
        val departments = client.from("departments").select().decodeList<DepartmentRow>()
        val batches = client.from("batches").select().decodeList<BatchRow>()
        val semesters = client.from("semesters").select().decodeList<SemesterRow>()
        val batchSemesters = client.from("batch_semesters").select().decodeList<BatchSemesterRow>()
        val courses = client.from("courses").select().decodeList<CourseRow>()
        val offerings = client.from("course_offerings").select().decodeList<CourseOfferingRow>()
        val departmentById = departments.associateBy { it.id }
        val semesterById = semesters.associateBy { it.id }
        val targetBatch = batches.firstOrNull { batch ->
            val department = departmentById[batch.departmentId] ?: return@firstOrNull false
            normalizeCode(department.code + batch.cohortCode) == normalizeCode(batchRoute)
        } ?: throw IOException("This batch is not configured in ClassMate V2 yet.")
        val batchSemesterIds = batchSemesters.asSequence()
            .filter { it.batchId == targetBatch.id && it.state == "published" }
            .filter { semesterById[it.semesterId]?.ordinal == semesterOrdinal(semesterRoute) }
            .map { it.id }
            .toSet()
        if (batchSemesterIds.isEmpty()) {
            throw IOException("${SemesterManager.formatDisplay(semesterRoute)} for this batch has not been published in Supabase V2 yet. Firestore semester publishing does not copy it into Supabase.")
        }
        val courseRecord = courses.firstOrNull {
            it.code.equals(course.code, true) || it.name.equals(course.name, true)
        } ?: throw IOException("The selected course is not configured in ClassMate V2 yet.")
        val offering = offerings.firstOrNull {
            it.batchSemesterId in batchSemesterIds && it.courseId == courseRecord.id
        } ?: throw IOException("This course has no published V2 offering for the selected semester.")
        val resourceId = UUID.randomUUID().toString()
        val storageKey = "${offering.id}/$resourceId/${safeFileName(fileName)}"
        client.from("resources").insert(
            ResourceInsert(
                id = resourceId,
                courseOfferingId = offering.id,
                uploaderId = profile.id,
                logicalKey = "${courseRecord.code.ifBlank { courseRecord.name }}-${UUID.randomUUID()}",
                title = title.trim(),
                description = description.trim(),
                category = category.lowercase(),
                examType = null,
                fileName = safeFileName(fileName),
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                storageProvider = "supabase",
                storageKey = storageKey,
                state = "active"
            )
        )

        try {
            uploadObject(context, uri, storageKey, mimeType, sizeBytes, onProgress)
        } catch (error: Exception) {
            runCatching {
                client.postgrest.rpc("archive_academic_resource", parameters = kotlinx.serialization.json.buildJsonObject {
                    put("target_resource", resourceId)
                })
            }
            throw error
        }
        return resourceId
    }

    suspend fun delete(resourceId: String) {
        val client = clientProvider.client
        val resource = client.from("resources").select().decodeList<ResourceRow>()
            .firstOrNull { it.id == resourceId }
            ?: throw IOException("This resource is no longer available.")
        client.postgrest.rpc("archive_academic_resource", parameters = kotlinx.serialization.json.buildJsonObject {
            put("target_resource", resourceId)
        })
        runCatching { removeObject(resource.storageKey) }
    }

    suspend fun resolveDownloadUrl(resourceId: String): String {
        val resource = clientProvider.client.from("resources").select()
            .decodeList<ResourceRow>().firstOrNull { it.id == resourceId }
            ?: throw IOException("This resource is no longer available.")
        val request = storageRequest("object/sign/academic-resources/${encodePath(resource.storageKey)}")
            .post("{\"expiresIn\":3600}".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val json = executeStorage(request)
        val signedPath = json.get("signedURL")?.asString.orEmpty()
        if (signedPath.isBlank()) throw IOException("Supabase did not return a download link.")
        return if (signedPath.startsWith("http")) signedPath else {
            "${BuildConfigValue.baseUrl()}/storage/v1${if (signedPath.startsWith('/')) signedPath else "/$signedPath"}"
        }
    }

    private suspend fun uploadObject(
        context: Context,
        uri: Uri,
        storageKey: String,
        mimeType: String,
        sizeBytes: Long,
        onProgress: (Int) -> Unit
    ) {
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = sizeBytes
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var transferred = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        transferred += count
                        onProgress(((transferred * 100L) / sizeBytes).toInt().coerceIn(0, 100))
                    }
                } ?: throw IOException("Unable to read the selected file.")
            }
        }
        val request = storageRequest("object/academic-resources/${encodePath(storageKey)}")
            .header("Content-Type", mimeType)
            .header("x-upsert", "false")
            .post(body)
            .build()
        executeStorage(request)
    }

    private suspend fun removeObject(storageKey: String) {
        val body = gson.toJson(mapOf("prefixes" to listOf(storageKey)))
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = storageRequest("object/remove/academic-resources")
            .post(body)
            .build()
        executeStorage(request)
    }

    private suspend fun storageRequest(path: String): Request.Builder {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IOException("Please sign in again.")
        return Request.Builder()
            .url("${BuildConfigValue.baseUrl()}/storage/v1/$path")
            .header("Authorization", "Bearer $token")
            .header("apikey", BuildConfigValue.publishableKey())
    }

    private fun executeStorage(request: Request): JsonObject =
        http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { gson.fromJson(body, JsonObject::class.java).get("message")?.asString }.getOrNull()
                    throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Secure file transfer failed (${response.code}).")
                }
                runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: JsonObject()
            }

    private fun ResourceRow.toPdfFile(course: CourseRow, semesterRoute: String) = PdfFile(
        id = id,
        title = title,
        description = description,
        subject = course.name,
        uploadedBy = "ClassMate user",
        fileId = id,
        timestamp = createdAt.toTimestamp(),
        createdAt = createdAt.toTimestamp(),
        updatedAt = updatedAt.toTimestamp(),
        courseCode = course.code,
        courseType = when {
            category.equals("syllabus", true) -> "syllabus"
            course.kind.equals("lab", true) -> "lab"
            else -> "regular"
        },
        fileType = fileName.substringAfterLast('.', "").lowercase().ifBlank { "other" },
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        provider = "supabase",
        semester = semesterRoute
    )

    private fun String.toTimestamp(): Timestamp? = runCatching {
        Timestamp(Date.from(Instant.parse(this)))
    }.getOrNull()

    private fun semesterOrdinal(value: String): Int? = Regex("[1-8]").find(value)?.value?.toIntOrNull()

    private fun normalizeCode(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    private fun safeFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "resource.bin" }

    private fun encodePath(value: String): String = value.split('/').joinToString("/") { Uri.encode(it) }

    @Serializable
    private data class ResourceInsert(
        val id: String,
        @SerialName("course_offering_id") val courseOfferingId: String,
        @SerialName("uploader_id") val uploaderId: String,
        @SerialName("logical_key") val logicalKey: String,
        val title: String,
        val description: String,
        val category: String,
        @SerialName("exam_type") val examType: String?,
        @SerialName("file_name") val fileName: String,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("size_bytes") val sizeBytes: Long,
        @SerialName("storage_provider") val storageProvider: String,
        @SerialName("storage_key") val storageKey: String,
        val state: String
    )

    @Serializable
    private data class ResourceRow(
        val id: String,
        @SerialName("course_offering_id") val courseOfferingId: String,
        @SerialName("uploader_id") val uploaderId: String,
        @SerialName("logical_key") val logicalKey: String,
        @SerialName("version_number") val versionNumber: Int,
        @SerialName("replaces_resource_id") val replacesResourceId: String? = null,
        val title: String,
        val description: String,
        val category: String,
        @SerialName("exam_type") val examType: String? = null,
        @SerialName("file_name") val fileName: String,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("size_bytes") val sizeBytes: Long,
        @SerialName("storage_provider") val storageProvider: String,
        @SerialName("storage_key") val storageKey: String,
        val state: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        @SerialName("deleted_by") val deletedBy: String? = null
    )

    @Serializable
    private data class ProfileRow(
        val id: String,
        @SerialName("firebase_uid") val firebaseUid: String,
        @SerialName("university_id") val universityId: String? = null,
        val email: String,
        @SerialName("display_name") val displayName: String,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        val status: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class RoleGrantRow(
        val id: String,
        @SerialName("profile_id") val profileId: String,
        val role: String,
        @SerialName("starts_at") val startsAt: String,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("revoked_at") val revokedAt: String? = null
    )

    private object BuildConfigValue {
        fun baseUrl() = com.shuaib.classmate.BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        fun publishableKey() = com.shuaib.classmate.BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
    }
}
