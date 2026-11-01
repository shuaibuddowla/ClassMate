package com.shuaib.classmate.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.shuaib.classmate.activities.ClassMateAcademicCache
import org.json.JSONArray
import org.json.JSONObject

internal object ClassMateRoutineExporter {

    private val dayNames = listOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    )

    fun exportRoutine(context: Context, accountId: String, batchId: String, batchLabel: String) {
        val data = ClassMateAcademicCache.read(context, accountId, batchId, "routine")
            ?: ClassMateAcademicCache.read(context, accountId, batchId, "timetable")
        if (data == null) {
            Toast.makeText(context, "Routine data not available offline yet. Open Timetable to sync first.", Toast.LENGTH_SHORT).show()
            return
        }

        val entries = data.optJSONArray("entries") ?: JSONArray()
        val namesObj = data.optJSONObject("names") ?: JSONObject()
        val codesObj = data.optJSONObject("codes") ?: JSONObject()
        val detailsArr = data.optJSONArray("details") ?: JSONArray()

        val detailsMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until detailsArr.length()) {
            val d = detailsArr.optJSONObject(i) ?: continue
            val cid = d.optString("course_id")
            if (cid.isNotBlank()) detailsMap[cid] = d
        }

        val sb = StringBuilder()
        val titleBatch = batchLabel.ifBlank { "MBSTU" }
        sb.append("📅 ").append(titleBatch).append(" · Class Routine\n")
        sb.append("═".repeat(34)).append("\n\n")

        var totalClasses = 0

        for (dayIndex in 0 until 7) {
            val dayName = dayNames[dayIndex]
            val dayEntries = mutableListOf<JSONObject>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                if (e.optInt("day_of_week") == dayIndex) {
                    dayEntries.add(e)
                }
            }

            if (dayEntries.isEmpty()) continue

            dayEntries.sortBy { it.optString("start_time") }

            sb.append("📌 ").append(dayName.uppercase()).append(":\n")
            for (item in dayEntries) {
                totalClasses++
                val scId = item.optString("semester_course_id")
                val courseCode = codesObj.optString(scId).ifBlank { item.optString("code_override") }
                val courseTitle = namesObj.optString(scId).ifBlank { item.optString("title_override") }
                val room = item.optString("room").takeUnless { it.isBlank() || it.equals("null", true) } ?: "TBA"
                val startTime = formatTime(item.optString("start_time"))
                val endTime = formatTime(item.optString("end_time"))
                val detail = detailsMap[scId]
                val teacherName = detail?.optString("teacher_name").takeUnless { it.isNullOrBlank() || it.equals("null", true) }

                sb.append("  • ").append(startTime).append(" - ").append(endTime).append("\n")
                sb.append("    ").append(courseCode).append(if (courseTitle.isNotBlank()) " · $courseTitle" else "").append("\n")
                sb.append("    Room: ").append(room)
                if (!teacherName.isNullOrBlank()) {
                    sb.append(" | ").append(teacherName)
                }
                sb.append("\n\n")
            }
        }

        if (totalClasses == 0) {
            sb.append("No active routine periods scheduled yet for this batch.\n")
        }

        sb.append("───────────────\n")
        sb.append("Shared via ClassMate · MBSTU Student Companion")

        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, sb.toString())
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Weekly Class Routine"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open share sheet: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(raw: String): String {
        if (raw.isBlank()) return ""
        val parts = raw.split(":")
        if (parts.size < 2) return raw
        val h = parts[0].toIntOrNull() ?: return raw
        val m = parts[1].toIntOrNull() ?: 0
        val amPm = if (h >= 12) "PM" else "AM"
        val hour12 = when (h % 12) {
            0 -> 12
            else -> h % 12
        }
        return String.format("%d:%02d %s", hour12, m, amPm)
    }
}
