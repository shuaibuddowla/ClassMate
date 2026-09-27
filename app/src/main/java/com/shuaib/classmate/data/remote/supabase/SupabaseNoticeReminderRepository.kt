package com.shuaib.classmate.data.remote.supabase

import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

internal class SupabaseNoticeReminderRepository {
    private val clientProvider = SupabaseClientProvider(FirebaseAuth.getInstance())

    val isConfigured: Boolean
        get() = clientProvider.isConfigured

    suspend fun current(noticeId: String): Long? = clientProvider.client.postgrest
        .rpc("list_my_notice_reminders")
        .decodeList<ReminderRow>()
        .firstOrNull { it.noticeId == noticeId }
        ?.remindAt
        ?.let { Instant.parse(it).toEpochMilli() }

    suspend fun set(noticeId: String, remindAtMillis: Long) {
        clientProvider.client.postgrest.rpc("set_notice_reminder", parameters = buildJsonObject {
            put("target_notice", noticeId)
            put("target_remind_at", Instant.ofEpochMilli(remindAtMillis).toString())
        })
    }

    suspend fun remove(noticeId: String) {
        clientProvider.client.postgrest.rpc("remove_notice_reminder", parameters = buildJsonObject {
            put("target_notice", noticeId)
        })
    }

    suspend fun active(): List<Pair<String, Long>> = clientProvider.client.postgrest
        .rpc("list_my_notice_reminders")
        .decodeList<ReminderRow>()
        .mapNotNull { row ->
            runCatching { row.noticeId to Instant.parse(row.remindAt).toEpochMilli() }.getOrNull()
        }

    @Serializable
    private data class ReminderRow(
        @SerialName("notice_id") val noticeId: String,
        @SerialName("remind_at") val remindAt: String
    )
}
