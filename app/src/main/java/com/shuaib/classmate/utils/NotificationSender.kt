/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/utils/NotificationSender.kt
 */
package com.shuaib.classmate.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shuaib.classmate.chat.ChatRepository
import org.json.JSONObject
import com.shuaib.classmate.notices.NoticeTextFormatter

object NotificationSender {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun sendToAll(
        title: String,
        message: String,
        type: String,
        extraData: Map<String, String> = emptyMap(),
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                val connection = java.net.URL(
                    "https://api.onesignal.com/notifications"
                ).openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8"
                )
                connection.setRequestProperty(
                    "Authorization",
                    "Key ${AppConstants.ONESIGNAL_REST_API_KEY}"
                )
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                // Build data payload
                val dataObj = JSONObject().apply {
                    put("type", type)
                    extraData.forEach { (k, v) -> put(k, v) }
                }

                val body = JSONObject().apply {
                    put("app_id", AppConstants.ONESIGNAL_APP_ID)
                    put("target_channel", "push")
                    put("included_segments",
                        org.json.JSONArray().put("All"))
                    put("headings",
                        JSONObject().put("en", NoticeTextFormatter.stripMarkdown(title)))
                    put("contents",
                        JSONObject().put("en", NoticeTextFormatter.stripMarkdown(message)))
                    put("data", dataObj)
                    put("android_accent_color", "FF4D9FFF")
                    put("priority", 10)
                }.toString()

                connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
                connection.outputStream.flush()

                val responseCode = connection.responseCode
                android.util.Log.d("ONESIGNAL",
                    "Response: $responseCode")

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
            sendToAll(
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
                val connection = java.net.URL("https://api.onesignal.com/notifications").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Key ${AppConstants.ONESIGNAL_REST_API_KEY}")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val dataObj = JSONObject().apply {
                    put("type", type)
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
                }.toString()
                connection.outputStream.write(body.toByteArray(Charsets.UTF_8))
                connection.outputStream.flush()
                val responseCode = connection.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 200 || responseCode == 201 || responseCode == 204) onSuccess()
                    else onFailure("HTTP $responseCode")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailure(e.message ?: "Unknown error") }
            }
        }
    }

    // New Assignment Alert
    fun sendAssignmentAlert(
        subject: String,
        topic: String,
        dueDate: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) = sendToAll(
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
