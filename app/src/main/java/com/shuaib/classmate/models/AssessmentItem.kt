package com.shuaib.classmate.models

import com.shuaib.classmate.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class UrgencyLevel {
    PAST,       // Deadline has passed (Muted Gray)
    IMMINENT,   // < 12 hours (Rose / Crimson)
    SOON,       // 12 to 24 hours (Warm Amber)
    UPCOMING,   // 24 to 72 hours (Focused Indigo)
    LATER       // > 72 hours (Neutral Slate)
}

enum class AssessmentType(
    val code: String,
    val displayName: String,
    val shortName: String,
    val colorHex: Long,
    val emoji: String,
    val iconRes: Int
) {
    CLASS_TEST("class_test", "CT", "CT", 0xFF8B5CF6, "📝", R.drawable.ic_filter_ct),
    ASSIGNMENT("assignment", "Assignment", "Assignment", 0xFF3B82F6, "📚", R.drawable.ic_filter_assignment),
    QUIZ("quiz", "Quiz", "Quiz", 0xFFF59E0B, "⚡", R.drawable.ic_filter_quiz),
    LAB_REPORT("lab_report", "Lab", "Lab", 0xFF10B981, "🔬", R.drawable.ic_filter_lab),
    PRESENTATION("presentation", "Presentation", "Presentation", 0xFFEC4899, "📊", R.drawable.ic_filter_presentation);

    companion object {
        fun fromCode(code: String?): AssessmentType {
            val normalized = code?.trim()?.lowercase() ?: return CLASS_TEST
            return values().firstOrNull { 
                it.code == normalized || it.name.lowercase() == normalized || it.shortName.lowercase() == normalized 
            } ?: CLASS_TEST
        }
    }
}

data class AssessmentItem(
    val id: String = "",
    val batchId: String = "",
    val courseId: String = "",
    val courseCode: String = "",
    val courseTitle: String = "",
    val type: String = AssessmentType.CLASS_TEST.code,
    val title: String = "",
    val deadlineDate: String = "", // "yyyy-MM-dd"
    val deadlineTime: String = "", // "HH:mm" or "10:30 AM"
    val room: String = "",
    val syllabus: String = "",
    val noticeId: String = "",
    val postedBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isMock: Boolean = false
) {
    val assessmentType: AssessmentType
        get() = AssessmentType.fromCode(type)

    fun getDeadlineDateTime(): LocalDateTime? {
        if (deadlineDate.isBlank()) return null
        return try {
            val date = LocalDate.parse(deadlineDate.trim())
            val time = parseTime(deadlineTime.trim()) ?: LocalTime.of(23, 59)
            LocalDateTime.of(date, time)
        } catch (e: Exception) {
            null
        }
    }

    fun isPast(): Boolean {
        if (deadlineDate.isBlank()) return false
        val dt = getDeadlineDateTime()
        if (dt != null) {
            return dt.isBefore(LocalDateTime.now())
        }
        return try {
            LocalDate.parse(deadlineDate.trim()).isBefore(LocalDate.now())
        } catch (_: Exception) {
            false
        }
    }

    fun getUrgencyLevel(now: LocalDateTime = LocalDateTime.now()): UrgencyLevel {
        if (isPast()) return UrgencyLevel.PAST
        val dt = getDeadlineDateTime() ?: return UrgencyLevel.UPCOMING
        val minutesRemaining = ChronoUnit.MINUTES.between(now, dt)
        if (minutesRemaining < 0) return UrgencyLevel.PAST
        val hoursRemaining = minutesRemaining / 60
        return when {
            hoursRemaining < 12 -> UrgencyLevel.IMMINENT
            hoursRemaining < 24 -> UrgencyLevel.SOON
            hoursRemaining < 72 -> UrgencyLevel.UPCOMING
            else -> UrgencyLevel.LATER
        }
    }

    fun getFormattedRoom(): String {
        val trimmed = room.trim()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
        return when {
            trimmed.contains("room", ignoreCase = true) ||
            trimmed.contains("lab", ignoreCase = true) ||
            trimmed.contains("hall", ignoreCase = true) ||
            trimmed.contains("class", ignoreCase = true) ||
            trimmed.contains("online", ignoreCase = true) -> trimmed

            trimmed.matches(Regex("""^\d+([A-Za-z]|\s*-\s*[A-Za-z0-9]+)?$""")) -> "Room $trimmed"

            else -> trimmed
        }
    }

    fun getFormattedCountdown(now: LocalDateTime = LocalDateTime.now()): String {
        val dt = getDeadlineDateTime() ?: return "Upcoming"
        if (dt.isBefore(now)) return "Past"

        val totalMinutes = ChronoUnit.MINUTES.between(now, dt)
        if (totalMinutes <= 0) return "Due now"

        val totalHours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val days = totalHours / 24
        val remHours = totalHours % 24

        return when {
            days > 0 && remHours > 0 -> "In ${days}d ${remHours}h"
            days > 0 -> "In ${days}d"
            totalHours > 0 && minutes > 0 -> "In ${totalHours}h ${minutes}m"
            totalHours > 0 -> "In ${totalHours}h"
            minutes > 0 -> "In ${minutes}m"
            else -> "Due now"
        }
    }

    /**
     * Relative label ("Today", "Tomorrow", "Wed, 25 Nov") eliminates mental math.
     */
    fun getFormattedRelativeDate(): String {
        if (deadlineDate.isBlank()) return "TBA"
        return try {
            val date = LocalDate.parse(deadlineDate.trim())
            val today = LocalDate.now()
            val diff = ChronoUnit.DAYS.between(today, date)
            when {
                diff == 0L -> "Today"
                diff == 1L -> "Tomorrow"
                diff in 2L..6L -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US))
                date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
                else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
            }
        } catch (e: Exception) {
            deadlineDate
        }
    }

    /**
     * Combined relative date and time: "Today · 03:15 PM" or "Tomorrow · 02:30 PM".
     */
    fun getFormattedRelativeDateTime(): String {
        val relDate = getFormattedRelativeDate()
        return if (deadlineTime.isNotBlank()) {
            "$relDate · $deadlineTime"
        } else {
            relDate
        }
    }

    fun getFormattedDate(): String {
        return getFormattedRelativeDate()
    }

    private fun parseTime(raw: String): LocalTime? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim().uppercase(Locale.US)
        val formats = listOf(
            DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.US),
            DateTimeFormatter.ofPattern("h:mma", Locale.US),
            DateTimeFormatter.ofPattern("hh:mma", Locale.US),
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("HH:mm", Locale.US),
            DateTimeFormatter.ofPattern("H:mm", Locale.US)
        )
        for (formatter in formats) {
            try {
                return LocalTime.parse(trimmed, formatter)
            } catch (_: Exception) {}
        }
        return null
    }
}
