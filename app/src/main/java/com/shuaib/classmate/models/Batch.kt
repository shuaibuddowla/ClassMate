package com.shuaib.classmate.models

import com.google.firebase.Timestamp

data class Batch(
    val id: String = "",
    val name: String = "",
    val activeSemesterId: String = "1st",
    val department: String = "CSE",
    val createdAt: Timestamp? = null,
    val createdBy: String = ""
) {
    companion object {
        val PREDEFINED_BATCHES = listOf(
            Batch(id = "cse20", name = "CSE-20", activeSemesterId = "7th"),
            Batch(id = "cse21", name = "CSE-21", activeSemesterId = "5th"),
            Batch(id = "cse22", name = "CSE-22", activeSemesterId = "3rd"),
            Batch(id = "cse23", name = "CSE-23", activeSemesterId = "1st"),
            Batch(id = "cse24", name = "CSE-24", activeSemesterId = "1st"),
            Batch(id = "cse25", name = "CSE-25", activeSemesterId = "1st")
        )

        fun formatName(batchId: String): String {
            return PREDEFINED_BATCHES.firstOrNull { it.id.equals(batchId, ignoreCase = true) }?.name
                ?: batchId.uppercase()
        }
    }
}
