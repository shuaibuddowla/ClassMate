package com.shuaib.classmate.utils

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

object SemesterManager {
    val AVAILABLE_SEMESTERS = listOf("1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th")
    const val DEFAULT_SEMESTER = "2nd"

    val activeSemesterFlow: StateFlow<String>
        get() = AppContextManager.activeSemesterFlow

    fun init(context: Context) {
        AppContextManager.init(context)
    }

    fun getActiveSemester(): String {
        return AppContextManager.getActiveSemester()
    }

    fun getActiveSemesterDisplay(): String {
        return formatDisplay(getActiveSemester())
    }

    fun formatDisplay(semester: String): String {
        val norm = normalizeSemester(semester)
        return when {
            norm.endsWith("Semester", ignoreCase = true) -> norm
            norm.isNotBlank() -> "$norm Semester"
            else -> "$DEFAULT_SEMESTER Semester"
        }
    }

    fun normalizeSemester(semester: String?): String {
        val raw = semester?.trim().orEmpty()
        return when {
            raw.startsWith("1", ignoreCase = true) -> "1st"
            raw.startsWith("2", ignoreCase = true) -> "2nd"
            raw.startsWith("3", ignoreCase = true) -> "3rd"
            raw.startsWith("4", ignoreCase = true) -> "4th"
            raw.startsWith("5", ignoreCase = true) -> "5th"
            raw.startsWith("6", ignoreCase = true) -> "6th"
            raw.startsWith("7", ignoreCase = true) -> "7th"
            raw.startsWith("8", ignoreCase = true) -> "8th"
            raw.isNotBlank() -> raw
            else -> DEFAULT_SEMESTER
        }
    }

    fun updateActiveSemester(
        newSemester: String,
        batchId: String = AppContextManager.getManagedBatchId(),
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        AppContextManager.updateBatchActiveSemester(batchId, newSemester, onSuccess, onFailure)
    }
}
