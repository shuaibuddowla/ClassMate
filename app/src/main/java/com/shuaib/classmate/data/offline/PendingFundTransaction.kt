package com.shuaib.classmate.data.offline

import org.json.JSONObject
import java.util.UUID

data class PendingFundTransaction(
    val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val type: String, // "inflow" (deposit) or "outflow" (expense)
    val amount: Double,
    val title: String,
    val studentName: String? = null,
    val studentId: String? = null,
    val studentProfileId: String? = null,
    val collectionId: String? = null,
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val syncStatus: String = "pending" // "pending", "syncing", "failed"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("batch_id", batchId)
        put("type", type)
        put("amount", amount)
        put("title", title)
        put("student_name", studentName ?: JSONObject.NULL)
        put("student_id", studentId ?: JSONObject.NULL)
        put("student_profile_id", studentProfileId ?: JSONObject.NULL)
        put("collection_id", collectionId ?: JSONObject.NULL)
        put("notes", notes ?: JSONObject.NULL)
        put("timestamp", timestamp)
        put("sync_status", syncStatus)
    }

    fun toLedgerJsonObject(): JSONObject = JSONObject().apply {
        put("id", "offline_$id")
        put("offline_id", id)
        put("type", type)
        put("amount", amount)
        put("title", title)
        put("student_name", studentName ?: "")
        put("student_id", studentId ?: "")
        put("student_profile_id", studentProfileId ?: "")
        put("collection_id", collectionId ?: "")
        put("notes", notes ?: "")
        val todayStr = java.time.LocalDate.now().toString()
        put("transacted_at", todayStr)
        put("created_at", todayStr)
        put("is_offline_pending", true)
    }

    companion object {
        fun fromJson(json: JSONObject): PendingFundTransaction = PendingFundTransaction(
            id = json.optString("id", UUID.randomUUID().toString()),
            batchId = json.optString("batch_id"),
            type = json.optString("type", "inflow"),
            amount = json.optDouble("amount", 0.0),
            title = json.optString("title"),
            studentName = json.optString("student_name").takeIf { it.isNotBlank() && it != "null" },
            studentId = json.optString("student_id").takeIf { it.isNotBlank() && it != "null" },
            studentProfileId = json.optString("student_profile_id").takeIf { it.isNotBlank() && it != "null" },
            collectionId = json.optString("collection_id").takeIf { it.isNotBlank() && it != "null" },
            notes = json.optString("notes").takeIf { it.isNotBlank() && it != "null" },
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            syncStatus = json.optString("sync_status", "pending")
        )
    }
}
