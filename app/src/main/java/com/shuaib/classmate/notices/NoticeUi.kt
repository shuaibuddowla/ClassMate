package com.shuaib.classmate.notices

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.shuaib.classmate.R
import com.shuaib.classmate.models.Notice
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object NoticeUi {
    const val BG = "#F7FAFF"
    const val SURFACE = "#FFFFFF"
    const val CARD = "#FFFFFF"
    const val ELEVATED = "#F8FBFF"
    const val BORDER = "#D7E3F5"
    const val PRIMARY = "#2563EB"
    const val ACTION = "#2563EB"
    const val TEXT_PRIMARY = "#0F172A"
    const val TEXT_SECONDARY = "#334155"
    const val TEXT_MUTED = "#64748B"

    fun parseNotice(doc: DocumentSnapshot): Notice {
        val legacyType = when {
            doc.getBoolean("isResource") == true -> "resource"
            doc.getBoolean("isAssignment") == true -> "deadline"
            doc.getBoolean("isClassTest") == true -> "exam"
            else -> "notice"
        }
        val attachments = (doc.get("attachments") as? List<*>)
            ?.mapNotNull { it as? Map<String, Any> }
            .orEmpty()
        return Notice(
            id = doc.id,
            title = doc.getString("title").orEmpty(),
            body = doc.getString("content") ?: doc.getString("body").orEmpty(),
            postedBy = doc.getString("postedBy").orEmpty(),
            timestamp = doc.getTimestamp("createdAt") ?: doc.getTimestamp("timestamp"),
            type = doc.getString("type") ?: legacyType,
            priority = doc.getString("priority") ?: "normal",
            createdBy = doc.getString("createdBy").orEmpty(),
            createdByName = doc.getString("createdByName") ?: doc.getString("postedBy").orEmpty(),
            updatedAt = doc.getTimestamp("updatedAt"),
            deadlineAt = doc.getTimestamp("deadlineAt") ?: parseDeadline(doc.getString("submissionDate")),
            attachments = attachments,
            discussionRoomId = doc.getString("discussionRoomId").orEmpty(),
            isPinned = doc.getBoolean("isPinned") ?: false,
            isDeleted = doc.getBoolean("isDeleted") ?: false,
            discussionCount = (doc.getLong("discussionCount") ?: 0L).toInt(),
            isCancel = doc.getBoolean("isCancel") ?: false,
            isSub = doc.getBoolean("isSub") ?: false,
            isAssignment = doc.getBoolean("isAssignment") ?: false,
            isClassTest = doc.getBoolean("isClassTest") ?: false,
            isResource = doc.getBoolean("isResource") ?: false,
            attachmentType = doc.getString("attachmentType") ?: "none",
            attachmentUrl = doc.getString("attachmentUrl").orEmpty(),
            attachmentName = doc.getString("attachmentName").orEmpty(),
            fileId = doc.getString("fileId").orEmpty(),
            assignmentId = doc.getString("assignmentId").orEmpty(),
            subject = doc.getString("subject") ?: doc.getString("targetSubject").orEmpty(),
            topic = doc.getString("topic").orEmpty(),
            submissionDate = doc.getString("submissionDate").orEmpty(),
            deadlineType = doc.getString("deadlineType") ?: "assignment",
            pdfId = doc.getString("pdfId").orEmpty()
        )
    }

    private fun parseDeadline(value: String?): Timestamp? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)
            date?.let { Timestamp(it) }
        }.getOrNull()
    }

    fun typeLabel(type: String): String = when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> "ASSIGNMENT"
        "exam", "class_test", "class_test_deadline" -> "CLASS TEST"
        "poll" -> "POLL"
        "resource" -> "RESOURCE"
        "cancellation", "cancelled", "canceled" -> "CANCELLATION"
        else -> "GENERAL NOTICE"
    }

    fun headerSubject(notice: Notice): String {
        val subject = notice.subject.trim()
        return if (subject.equals("null", ignoreCase = true)) "" else subject
    }

    fun shouldShowHeaderSubject(notice: Notice): Boolean {
        val header = headerSubject(notice)
        if (header.isBlank()) return false

        val type = notice.displayType.trim().lowercase(Locale.US)
        val normalizedHeader = normalizeHeaderLabel(header)
        if (normalizedHeader.isBlank()) return false

        val normalizedType = normalizeHeaderLabel(type)
        val normalizedTypeLabel = normalizeHeaderLabel(typeLabel(type))
        if (normalizedHeader == normalizedType || normalizedHeader == normalizedTypeLabel) return false

        val isNormalNotice = type in setOf("notice", "normal", "general", "general_notice")
        if (isNormalNotice && normalizedHeader in setOf("general", "notice", "generalnotice")) {
            return false
        }

        return true
    }

    private fun normalizeHeaderLabel(value: String): String {
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "")
    }

    fun typeIcon(type: String): String = when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> "!"
        "exam", "class_test", "class_test_deadline" -> "EX"
        "poll" -> "%"
        "resource" -> "PDF"
        "cancellation", "cancelled", "canceled" -> "X"
        else -> "i"
    }
    fun accent(type: String): Int = when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> Color.rgb(249, 115, 22)
        "exam", "class_test", "class_test_deadline" -> Color.rgb(59, 130, 246)
        "poll" -> Color.rgb(34, 197, 94)
        "resource" -> Color.rgb(168, 85, 247)
        "cancellation", "cancelled", "canceled" -> Color.rgb(239, 68, 68)
        else -> Color.rgb(56, 189, 248)
    }

    fun accent(context: Context, type: String): Int = ContextCompat.getColor(context, when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> R.color.cm_notice_assignment_text
        "exam", "class_test", "class_test_deadline" -> R.color.cm_notice_class_test_text
        "poll" -> R.color.cm_success
        "resource" -> R.color.cm_notice_resource_text
        "cancellation", "cancelled", "canceled" -> R.color.cm_notice_cancel_text
        else -> R.color.cm_notice_general_text
    })

    fun badgeBackground(type: String): Int = when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> Color.rgb(46, 20, 5)
        "exam", "class_test", "class_test_deadline" -> Color.rgb(15, 30, 54)
        "poll" -> Color.rgb(15, 47, 29)
        "resource" -> Color.rgb(36, 24, 63)
        "cancellation", "cancelled", "canceled" -> Color.rgb(45, 11, 15)
        else -> Color.rgb(8, 32, 51)
    }

    fun badgeBackground(context: Context, type: String): Int = ContextCompat.getColor(context, when (type.lowercase()) {
        "deadline", "assignment", "assignment_deadline" -> R.color.cm_notice_assignment_bg
        "exam", "class_test", "class_test_deadline" -> R.color.cm_notice_class_test_bg
        "poll" -> R.color.cm_primary_container
        "resource" -> R.color.cm_notice_resource_bg
        "cancellation", "cancelled", "canceled" -> R.color.cm_notice_cancel_bg
        else -> R.color.cm_notice_general_bg
    })

    fun priorityLabel(notice: Notice): String {
        val deadline = notice.deadlineAt?.toDate()?.time
        if (deadline != null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                val days = TimeUnit.MILLISECONDS.toDays(remaining).coerceAtLeast(0)
                return if (days == 0L) "Due today" else "$days days left"
            }
        }
        return when (notice.displayPriority) {
            "high" -> "High Priority"
            "low" -> "Low"
            else -> "Normal"
        }
    }

    fun priorityColor(notice: Notice): Int =
        if (notice.displayPriority == "high") Color.rgb(239, 68, 68) else Color.rgb(148, 163, 184)

    fun priorityColor(context: Context, notice: Notice): Int {
        return ContextCompat.getColor(
            context,
            if (notice.displayPriority == "high") R.color.cm_error else R.color.cm_text_muted
        )
    }

    fun preview(notice: Notice, maxLength: Int = 150): String {
        val text = notice.content.ifBlank { notice.topic }.replace(Regex("\\s+"), " ").trim()
        return if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "..."
    }

    fun formatDate(timestamp: Timestamp?): String {
        return timestamp?.toDate()?.let {
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(it)
        } ?: "No date"
    }

    fun formatTime(timestamp: Timestamp?): String {
        return timestamp?.toDate()?.let {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(it)
        } ?: "No time"
    }

    fun relative(timestamp: Timestamp?): String {
        val millis = timestamp?.toDate()?.time ?: return "Recently"
        val diff = System.currentTimeMillis() - millis
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
            else -> formatDate(timestamp)
        }
    }

    fun attachmentMaps(notice: Notice): List<Map<String, Any>> {
        val modern = notice.attachments
        val legacy = if (notice.attachmentUrl.isNotBlank()) {
            val url = notice.attachmentUrl
            // Check if this URL is already in modern attachments to avoid duplication
            val alreadyPresent = modern.any { it["url"] == url }
            if (!alreadyPresent) {
                listOf(
                    mapOf(
                        "name" to notice.attachmentName.ifBlank { "Attachment" },
                        "url" to url,
                        "type" to notice.attachmentType.ifBlank { "file" },
                        "size" to ""
                    )
                )
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        return modern + legacy
    }

    fun timestampFromMillis(millis: Long): Timestamp = Timestamp(Date(millis))

    fun sectionFor(millis: Long): String {
        return when {
            isToday(millis) -> "Today"
            isYesterday(millis) -> "Yesterday"
            isThisWeek(millis) -> "Earlier This Week"
            else -> "Older"
        }
    }

    fun isToday(millis: Long): Boolean {
        if (millis <= 0) return false
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = millis }
        return now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
    }

    fun isYesterday(millis: Long): Boolean {
        if (millis <= 0) return false
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val date = Calendar.getInstance().apply { timeInMillis = millis }
        return yesterday.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
    }

    fun isThisWeek(millis: Long): Boolean {
        if (millis <= 0) return false
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = millis }
        return now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
            now.get(Calendar.WEEK_OF_YEAR) == date.get(Calendar.WEEK_OF_YEAR)
    }
}
