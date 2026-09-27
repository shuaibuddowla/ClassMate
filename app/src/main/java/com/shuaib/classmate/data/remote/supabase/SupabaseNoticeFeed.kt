package com.shuaib.classmate.data.remote.supabase

import com.google.firebase.Timestamp
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.notices.NoticeEngagement
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    @SerialName("is_personally_pinned") val isPersonallyPinned: Boolean = false
)

internal class SupabaseNoticeFeed(
    private val clientProvider: SupabaseClientProvider
) {
    val isConfigured: Boolean
        get() = clientProvider.isConfigured

    suspend fun load(batchRoute: String): List<Notice> {
        return loadRows().map { it.toNotice(batchRoute) }
    }

    suspend fun loadEngagement(noticeIds: Set<String>): Map<String, NoticeEngagement> {
        if (noticeIds.isEmpty()) return emptyMap()
        return loadRows().asSequence()
            .filter { it.id in noticeIds }
            .associate { row ->
                row.id to NoticeEngagement(
                    noticeId = row.id,
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
        ).decodeSingle<String>()
    }

    private suspend fun loadRows(): List<SupabaseNoticeFeedRow> {
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest
            .rpc("list_visible_notices")
            .decodeList<SupabaseNoticeFeedRow>()
    }
}

internal fun SupabaseNoticeFeedRow.toNotice(batchRoute: String): Notice = Notice(
    id = id,
    title = title,
    body = body,
    postedBy = authorName,
    timestamp = (publishedAt ?: createdAt).toFirebaseTimestamp(),
    priority = priority,
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
