package com.shuaib.classmate.notices

import android.content.Context
import android.util.Base64
import androidx.core.os.bundleOf
import androidx.navigation.NavDeepLinkBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.models.Notice
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class NoticeForwardPayload(
    val noticeId: String,
    val title: String,
    val contentPreview: String,
    val noticeType: String,
    val subject: String,
    val priority: String,
    val deadlineAt: Long?,
    val createdByName: String
)

object NoticeForwardManager {
    private const val MARKER = "CLASSMATE_NOTICE_FORWARD:"

    fun forwardToRoom(roomId: String, notice: Notice) {
        if (roomId.isBlank()) return
        ChatRepository.sendMessage(roomId, encode(notice))
    }

    fun encode(notice: Notice): String {
        val payload = JSONObject()
            .put("noticeId", notice.id)
            .put("title", notice.title)
            .put("contentPreview", notice.content.ifBlank { notice.topic })
            .put("noticeType", notice.displayType)
            .put("subject", notice.displaySubject)
            .put("priority", notice.displayPriority)
            .put("createdByName", notice.displayAuthor)
        notice.deadlineAt?.seconds?.let { payload.put("deadlineAt", it) }
        val encoded = Base64.encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return "${fallbackSummary(notice)}\n\n$MARKER$encoded"
    }

    fun decode(text: String): NoticeForwardPayload? {
        val markerIndex = text.indexOf(MARKER)
        if (markerIndex < 0) return null
        return runCatching {
            val raw = String(Base64.decode(text.substring(markerIndex + MARKER.length).trim(), Base64.NO_WRAP), StandardCharsets.UTF_8)
            val json = JSONObject(raw)
            NoticeForwardPayload(
                noticeId = json.optString("noticeId"),
                title = json.optString("title"),
                contentPreview = json.optString("contentPreview"),
                noticeType = json.optString("noticeType", "notice"),
                subject = json.optString("subject", "General"),
                priority = json.optString("priority", "normal"),
                deadlineAt = if (json.has("deadlineAt")) json.optLong("deadlineAt") else null,
                createdByName = json.optString("createdByName", "ClassMate")
            )
        }.getOrNull()?.takeIf { it.noticeId.isNotBlank() }
    }

    fun previewText(text: String): String {
        return decode(text)?.let { "Forwarded notice: ${it.title}" } ?: text
    }

    private fun fallbackSummary(notice: Notice): String {
        return buildString {
            append("Forwarded notice: ")
            appendLine(notice.title)
            appendLine("${NoticeUi.typeLabel(notice.displayType)} - ${notice.displaySubject}")
            val plainBody = NoticeTextFormatter.stripMarkdown(notice.content.ifBlank { notice.topic })
            if (plainBody.isNotBlank()) {
                appendLine(plainBody)
            }
            append("Open: classmate://notice/${notice.id}")
        }.trim()
    }

    fun shareText(notice: Notice): String {
        return buildString {
            appendLine(notice.title)
            appendLine("${NoticeUi.typeLabel(notice.displayType)} - ${notice.displaySubject}")
            val plainBody = NoticeTextFormatter.stripMarkdown(notice.content.ifBlank { notice.topic })
            if (plainBody.isNotBlank()) {
                val preview = if (plainBody.length <= 240) plainBody else plainBody.take(240).trimEnd() + "..."
                appendLine(preview)
            }
            append("classmate://notice/${notice.id}")
        }
    }

    fun openNotice(context: Context, noticeId: String) {
        // Since we moved to a Single Activity Architecture, we use NavDeepLinkBuilder 
        // or navigate via the NavController if we have a reference. 
        // For external context like this, a DeepLinkBuilder is safer.
        NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.nav_notices)
            .setArguments(bundleOf("noticeId" to noticeId))
            .createPendingIntent()
            .send()
    }
}
