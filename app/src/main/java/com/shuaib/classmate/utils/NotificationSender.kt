package com.shuaib.classmate.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.network.BackendApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.shuaib.classmate.notices.NoticeTextFormatter

object NotificationSender {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient()

    private fun getChannelIdForType(type: String): String {
        return when (type) {
            "chat_message" -> "chat_messages"
            "notice" -> "classmate_notices"
            "cancellation", "substitute" -> "classmate_cancellations"
            else -> "classmate_notifications"
        }
    }

    fun sendToBatch(
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String> = emptyMap(),
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                val dataObj = JSONObject().apply {
                    put("type", type)
                    put("batchId", batchId)
                    put("semester", SemesterManager.getActiveSemester())
                    extraData.forEach { (k, v) -> put(k, v) }
                }

                val filters = JSONArray().apply {
                    put(JSONObject().apply {
                        put("field", "tag")
                        put("key", "batchId")
                        put("relation", "=")
                        put("value", batchId)
                    })
                }

                val body = JSONObject().apply {
                    put("app_id", AppConstants.ONESIGNAL_APP_ID)
                    put("target_channel", "push")
                    put("filters", filters)
                    put("headings", JSONObject().put("en", NoticeTextFormatter.stripMarkdown(title)))
                    put("contents", JSONObject().put("en", NoticeTextFormatter.stripMarkdown(message)))
                    put("data", dataObj)
                    put("android_accent_color", "FF4D9FFF")
                    put("priority", 10)
                    put("existing_android_channel_id", getChannelIdForType(type))
                    put("android_visibility", 1)
                }.toString()

                val responseCode = postNotification(body)
                android.util.Log.d("ONESIGNAL", "Response: $responseCode")

                withContext(Dispatchers.Main) {
                    if (responseCode == 200 || responseCode == 201 || responseCode == 204) onSuccess()
                    else onFailure("HTTP $responseCode")
                }

            } catch (e: Exception) {
                android.util.Log.e("ONESIGNAL", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onFailure(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun sendToAll(
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String> = emptyMap(),
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        // Default to current batch
        sendToBatch(
            batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
            title = title,
            message = message,
            type = type,
            extraData = extraData,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun sendToPlayers(
        playerIds: List<String>,
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String> = emptyMap(),
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        if (playerIds.isEmpty()) return
        sendOneSignal(
            title = title,
            message = message,
            type = type,
            extraData = extraData,
            targetBuilder = { put("include_player_ids", org.json.JSONArray(playerIds)) },
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun sendChatMessageAlert(
        roomId: String,
        senderId: String,
        senderName: String,
        messageText: String,
        targetUserId: String? = null,
        onFailure: (String) -> Unit = {}
    ) {
        if (roomId in ChatRepository.activeRooms) return
        val bodyText = messageText.ifBlank { "Photo" }.take(100)
        val data = mapOf("roomId" to roomId, "senderId" to senderId)
        if (roomId == "group_main") {
            sendToBatch(
                batchId = AppContextManager.getBatchId(),
                title = "CODRIX-22",
                message = "$senderName: $bodyText",
                type = "chat_message",
                extraData = data,
                onFailure = onFailure
            )
            return
        }

        val uid = targetUserId ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val playerId = doc.getString("oneSignalPlayerId")
                    ?: doc.getString("onesignalPlayerId")
                    ?: doc.getString("playerId")
                if (!playerId.isNullOrBlank()) {
                    sendToPlayers(
                        playerIds = listOf(playerId),
                        title = senderName,
                        message = bodyText,
                        type = "chat_message",
                        extraData = data,
                        onFailure = onFailure
                    )
                } else {
                    sendToExternalUser(
                        externalUserId = uid,
                        title = senderName,
                        message = bodyText,
                        type = "chat_message",
                        extraData = data,
                        onFailure = onFailure
                    )
                }
            }
            .addOnFailureListener { onFailure(it.message ?: "Failed to load target user") }
    }

    private fun sendToExternalUser(
        externalUserId: String,
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String> = emptyMap(),
        onFailure: (String) -> Unit = {}
    ) {
        sendOneSignal(
            title = title,
            message = message,
            type = type,
            extraData = extraData,
            targetBuilder = {
                put("include_aliases", JSONObject().put("external_id", org.json.JSONArray().put(externalUserId)))
            },
            onFailure = onFailure
        )
    }

    private fun sendOneSignal(
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String>,
        targetBuilder: JSONObject.() -> Unit,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                val dataObj = JSONObject().apply {
                    put("type", type)
                    put("batchId", AppContextManager.getBatchId())
                    put("semester", SemesterManager.getActiveSemester())
                    extraData.forEach { (k, v) -> put(k, v) }
                }
                val body = JSONObject().apply {
                    put("app_id", AppConstants.ONESIGNAL_APP_ID)
                    put("target_channel", "push")
                    targetBuilder()
                    put("headings", JSONObject().put("en", NoticeTextFormatter.stripMarkdown(title)))
                    put("contents", JSONObject().put("en", NoticeTextFormatter.stripMarkdown(message)))
                    put("data", dataObj)
                    put("android_accent_color", "FF4D9FFF")
                    put("priority", 10)
                    put("existing_android_channel_id", getChannelIdForType(type))
                    put("android_visibility", 1)
                }.toString()
                val responseCode = postNotification(body)
                withContext(Dispatchers.Main) {
                    if (responseCode == 200 || responseCode == 201 || responseCode == 204) onSuccess()
                    else onFailure("HTTP $responseCode")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailure(e.message ?: "Unknown error") }
            }
        }
    }

    private fun postNotification(body: String): Int {
        val request = BackendApiClient.authenticated(
            Request.Builder()
                .url(BackendApiClient.url("/v1/notifications"))
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        )
        return client.newCall(request).execute().use { response -> response.code }
    }

    // New Assignment Alert
    fun sendAssignmentAlert(
        subject: String,
        topic: String,
        dueDate: String,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = "📝 New Assignment Posted",
        message = "Subject: $subject\nTopic: $topic\nDeadline: $dueDate\n\nClick to add a live countdown to your home screen!",
        type = "assignment",
        extraData = mapOf("subject" to subject, "topic" to topic),
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    // New Poll Alert
    fun sendPollAlert(
        question: String,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = "📊 New Poll Added",
        message = "Question: $question\n\nClick to view and vote in the Notices tab!",
        type = "poll",
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    // New resource alert
    fun sendResourceAlert(
        title: String,
        subject: String,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = title,
        message = "New study material has been posted for $subject.",
        type = "resource",
        extraData = mapOf("subject" to subject),
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    // Normal notice
    fun sendNoticeAlert(
        title: String,
        body: String,
        noticeId: String? = null,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = "📢 $title",
        message = body,
        type = "notice",
        extraData = if (noticeId != null) mapOf("noticeId" to noticeId) else emptyMap(),
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    // Class cancellation
    fun sendCancellationAlert(
        subject: String,
        whenText: String,
        noticeId: String? = null,
        day: String = "",
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = "Class Cancelled",
        message = "$subject class cancelled for $whenText",
        type = "cancellation",
        extraData = mutableMapOf("subject" to subject, "day" to day).apply {
            if (noticeId != null) put("noticeId", noticeId)
        },
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    // Substitute
    fun sendSubstituteAlert(
        subject: String,
        substituteTeacher: String,
        whenText: String,
        noticeId: String? = null,
        day: String = "",
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToBatch(
        batchId = batchId,
        title = "🔄 Substitute Class",
        message = "$subject will be taken by $substituteTeacher $whenText",
        type = "substitute",
        extraData = mutableMapOf(
            "subject" to subject,
            "teacher" to substituteTeacher,
            "day" to day
        ).apply {
            if (noticeId != null) put("noticeId", noticeId)
        },
        onSuccess = onSuccess,
        onFailure = onFailure
    )
}
