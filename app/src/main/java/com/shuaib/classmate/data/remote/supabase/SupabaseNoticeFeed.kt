package com.shuaib.classmate.data.remote.supabase

import com.google.firebase.Timestamp
import com.shuaib.classmate.models.Notice
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
        check(isConfigured) { "Supabase is not configured." }
        return clientProvider.client.postgrest
            .rpc("list_visible_notices")
            .decodeList<SupabaseNoticeFeedRow>()
            .map { it.toNotice(batchRoute) }
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

