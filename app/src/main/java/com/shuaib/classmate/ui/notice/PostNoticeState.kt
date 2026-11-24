package com.shuaib.classmate.ui.notice

import android.net.Uri

enum class NoticePostingType(val label: String) {
    GENERAL("General notice"),
    CANCELLATION("Class cancellation"),
    POLL("Interactive poll")
}

data class NoticeAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isPdf: Boolean = false
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mb)
            else String.format(java.util.Locale.US, "%.0f KB", kb)
        }
}

enum class VoiceRecordingState {
    IDLE,
    RECORDING,
    TRANSCRIBING
}

data class NoticeCourseChoice(
    val offeringId: String,
    val code: String,
    val title: String
) {
    val displayLabel: String get() = if (title.isNotBlank()) "$code · $title" else code
}

data class PollOptionDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String = ""
)

data class RecentNoticeItem(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val authorName: String,
    val timeAgo: String,
    val hasAttachment: Boolean
)

