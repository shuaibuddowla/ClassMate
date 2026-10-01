package com.shuaib.classmate.data.remote.supabase

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.notices.NoticeEngagement
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

@Serializable
internal data class SupabaseNoticeFeedRow(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    val title: String,
    val body: String,
    val priority: String,
    @SerialName("globally_pinned") val globallyPinned: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("like_count") val likeCount: Long = 0,
    @SerialName("is_liked") val isLiked: Boolean = false,
    @SerialName("is_personally_pinned") val isPersonallyPinned: Boolean = false,
    @SerialName("is_cancellation") val isCancellation: Boolean = false,
    val subject: String? = null,
    @SerialName("attachment_storage_key") val attachmentStorageKey: String? = null,
    @SerialName("attachment_file_name") val attachmentFileName: String? = null,
    @SerialName("attachment_mime_type") val attachmentMimeType: String? = null,
    @SerialName("resource_reference") val resourceReference: String? = null,
    @SerialName("resource_title") val resourceTitle: String? = null,
    @SerialName("resource_subject") val resourceSubject: String? = null,
    @SerialName("resource_provider") val resourceProvider: String? = null
)

@Serializable
private data class NoticeMutationResult(
    val id: String,
    @SerialName("storage_key") val storageKey: String? = null
)

@Serializable
private data class SupabaseNoticeEngagementRow(
    @SerialName("notice_id") val noticeId: String,
    @SerialName("like_count") val likeCount: Long,
    @SerialName("is_liked") val isLiked: Boolean,
    @SerialName("globally_pinned") val globallyPinned: Boolean,
    @SerialName("is_personally_pinned") val isPersonallyPinned: Boolean
)

internal class SupabaseNoticeFeed(
    private val clientProvider: SupabaseClientProvider
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
    private val gson = Gson()

    val isConfigured: Boolean
        get() = clientProvider.isConfigured

    suspend fun load(
        batchRoute: String,
        since: Instant? = null,
        before: Instant? = null,
        limit: Int = 40
    ): List<Notice> {
        return loadRows(since, before, limit).map { row ->
            val imageUrl = row.attachmentStorageKey?.let { key -> runCatching { resolveImageUrl(key) }.getOrNull() }
            row.toNotice(batchRoute, imageUrl)
        }
    }

    suspend fun loadEngagement(noticeIds: Set<String>): Map<String, NoticeEngagement> {
        if (noticeIds.isEmpty()) return emptyMap()
        val rows = clientProvider.client.postgrest.rpc(
            "list_notice_engagement",
            parameters = buildJsonObject {
                put("target_notices", kotlinx.serialization.json.buildJsonArray {
                    noticeIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        ).decodeList<SupabaseNoticeEngagementRow>()
        return rows.asSequence()
            .associate { row ->
                row.noticeId to NoticeEngagement(
                    noticeId = row.noticeId,
                    likeCount = row.likeCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    isLiked = row.isLiked,
                    isPinned = row.globallyPinned || row.isPersonallyPinned,
                    isGloballyPinned = row.globallyPinned,
                    isPersonallyPinned = row.isPersonallyPinned
                )
            }
    }

    suspend fun setLiked(noticeId: String, liked: Boolean) {
        clientProvider.client.postgrest.rpc("set_notice_like", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("liked", liked)
        })
    }

    suspend fun setPersonalPin(noticeId: String, pinned: Boolean) {
        clientProvider.client.postgrest.rpc("set_notice_personal_pin", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("pinned", pinned)
        })
    }

    suspend fun setGlobalPin(noticeId: String, pinned: Boolean) {
        clientProvider.client.postgrest.rpc("set_notice_global_pin", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("pinned", pinned)
        })
    }

    suspend fun publishBatchNotice(batchCode: String, title: String, body: String): String {
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest.rpc(
            "publish_batch_notice",
            parameters = buildJsonObject {
                put("target_batch_code", batchCode)
                put("notice_title", title)
                put("notice_body", body)
                put("notice_priority", "normal")
            }
        ).decodeAs<NoticeMutationResult>().id
    }

    suspend fun updateNotice(noticeId: String, title: String, body: String) {
        check(isConfigured) { "Supabase is not configured." }
        clientProvider.client.postgrest.rpc("update_notice", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("notice_title", title)
            put("notice_body", body)
        })
    }

    suspend fun deleteNotice(noticeId: String) {
        check(isConfigured) { "Supabase is not configured." }
        clientProvider.client.postgrest.rpc("delete_notice", parameters = buildJsonObject {
            put("target_notice", noticeId)
        })
    }

    suspend fun publishResourceNotice(
        batchCode: String,
        title: String,
        body: String,
        resourceId: String,
        resourceTitle: String,
        subject: String,
        provider: String = "archive"
    ): String {
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest.rpc("publish_resource_notice", parameters = buildJsonObject {
            put("target_batch_code", batchCode)
            put("notice_title", title)
            put("notice_body", body)
            put("target_resource_reference", resourceId)
            put("target_resource_title", resourceTitle)
            put("target_resource_subject", subject)
            put("target_resource_provider", provider)
        }).decodeAs<NoticeMutationResult>().id
    }

    suspend fun uploadNoticeImage(
        context: Context,
        noticeId: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        onProgress: (Int) -> Unit = {}
    ) {
        require(mimeType in SUPPORTED_IMAGE_TYPES) { "Only JPG, PNG, or WebP images are supported." }
        require(sizeBytes in 1..MAX_IMAGE_BYTES) { "Images must be 10 MB or smaller." }
        val prepared = clientProvider.client.postgrest.rpc("prepare_notice_image", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("image_file_name", fileName)
            put("image_mime_type", mimeType)
            put("image_size_bytes", sizeBytes)
        }).decodeAs<NoticeMutationResult>()
        val storageKey = prepared.storageKey ?: throw IOException("Supabase did not prepare the image upload.")
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = sizeBytes
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        onProgress(((sent * 100L) / sizeBytes).toInt().coerceIn(0, 100))
                    }
                } ?: throw IOException("Unable to read the selected image.")
            }
        }
        try {
            executeStorage(
                storageRequest("object/notice-images/${encodePath(storageKey)}")
                    .header("Content-Type", mimeType)
                    .header("x-upsert", "false")
                    .post(body)
                    .build()
            )
        } catch (error: Exception) {
            runCatching {
                clientProvider.client.postgrest.rpc("discard_failed_notice_upload", parameters = buildJsonObject {
                    put("target_notice", noticeId)
                })
            }
            throw error
        }
    }

    suspend fun publishClassCancellation(
        batchCode: String,
        offeringId: String,
        date: String,
        title: String,
        body: String
    ): String {
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest.rpc(
            "publish_class_cancellation",
            parameters = buildJsonObject {
                put("target_batch_code", batchCode)
                put("target_offering", offeringId)
                put("target_date", date)
                put("notice_title", title)
                put("notice_body", body)
            }
        ).decodeAs<NoticeMutationResult>().id
    }

    private suspend fun loadRows(
        since: Instant? = null,
        before: Instant? = null,
        limit: Int = 40
    ): List<SupabaseNoticeFeedRow> {
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest
            .rpc("list_visible_notices", parameters = buildJsonObject {
                since?.let { put("since_time", it.toString()) }
                before?.let { put("before_time", it.toString()) }
                put("page_size", limit.coerceIn(1, 100))
            })
            .decodeList<SupabaseNoticeFeedRow>()
    }

    private suspend fun resolveImageUrl(storageKey: String): String {
        val request = storageRequest("object/sign/notice-images/${encodePath(storageKey)}")
            .post("{\"expiresIn\":3600}".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val json = executeStorage(request)
        val signedPath = json.get("signedURL")?.asString.orEmpty()
        if (signedPath.isBlank()) throw IOException("Supabase did not return an image link.")
        return if (signedPath.startsWith("http")) signedPath else {
            "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/storage/v1${if (signedPath.startsWith('/')) signedPath else "/$signedPath"}"
        }
    }

    private suspend fun storageRequest(path: String): Request.Builder {
        val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IOException("Please sign in again.")
        return Request.Builder()
            .url("${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/storage/v1/$path")
            .header("Authorization", "Bearer $token")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim())
    }

    private fun executeStorage(request: Request): JsonObject =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { gson.fromJson(body, JsonObject::class.java).get("message")?.asString }.getOrNull()
                throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Notice image transfer failed (${response.code}).")
            }
            runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: JsonObject()
        }

    private fun encodePath(value: String): String = value.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

internal fun SupabaseNoticeFeedRow.toNotice(batchRoute: String, imageUrl: String? = null): Notice = Notice(
    id = id,
    title = title,
    body = body,
    postedBy = authorName,
    timestamp = (publishedAt ?: createdAt).toFirebaseTimestamp(),
    priority = priority,
    isCancel = isCancellation,
    isResource = !resourceReference.isNullOrBlank(),
    subject = subject.orEmpty(),
    attachments = if (!imageUrl.isNullOrBlank()) listOf(
        mapOf(
            "name" to attachmentFileName.orEmpty(),
            "url" to imageUrl,
            "type" to "image",
            "mimeType" to attachmentMimeType.orEmpty()
        )
    ) else emptyList(),
    attachmentType = if (!imageUrl.isNullOrBlank()) "image" else "none",
    attachmentUrl = imageUrl.orEmpty(),
    attachmentName = attachmentFileName.orEmpty(),
    pdfId = resourceReference.orEmpty(),
    createdBy = authorId,
    createdByName = authorName,
    updatedAt = updatedAt.toFirebaseTimestamp(),
    isPinned = globallyPinned,
    batchId = batchRoute
)

private fun String.toFirebaseTimestamp(): Timestamp? = runCatching {
    val instant = runCatching { Instant.parse(this) }
        .getOrElse { OffsetDateTime.parse(this).toInstant() }
    Timestamp(Date.from(instant))
}.getOrNull()
