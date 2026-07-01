package com.shuaib.classmate.chat

import android.content.Context
import android.util.Log
import com.shuaib.classmate.chat.model.ChatMessage
import com.shuaib.classmate.chat.model.ChatRoom
import com.shuaib.classmate.chat.model.ChatUser
import com.shuaib.classmate.chat.model.MessageReaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ChatRepository {
    private var serverUrl = "wss://hobby-monitors-committees-providence.trycloudflare.com"
    private const val RECONNECT_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var context: Context? = null
    private var userId: String = ""
    private var userName: String = "User"
    private var avatarUrl: String = ""
    private var reconnectScheduled = false
    private var manuallyClosed = false
    private var configJob: kotlinx.coroutines.Job? = null
    private var pendingGetRooms = false
    private var pendingGetUsers = false
    private val pendingSetRooms: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingHistoryRooms: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val pendingOutbound = ConcurrentLinkedQueue<String>()
    val activeRooms: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val _incomingMessage = MutableStateFlow<ChatMessage?>(null)
    val incomingMessage: StateFlow<ChatMessage?> = _incomingMessage

    private val _typingEvent = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 8)
    val typingEvent: SharedFlow<TypingEvent> = _typingEvent

    private val _dmCreated = MutableSharedFlow<ChatRoom>(extraBufferCapacity = 8)
    val dmCreated: SharedFlow<ChatRoom> = _dmCreated

    private val _historyMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val historyMessages: StateFlow<List<ChatMessage>> = _historyMessages

    private val _messageUpdate = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 16)
    val messageUpdate: SharedFlow<ChatMessage> = _messageUpdate

    private val _searchResults = MutableStateFlow<List<ChatMessage>>(emptyList())
    val searchResults: StateFlow<List<ChatMessage>> = _searchResults

    private val _pinnedMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val pinnedMessages: StateFlow<List<ChatMessage>> = _pinnedMessages

    private val _onlineUsers = MutableStateFlow<List<String>>(emptyList())
    val onlineUsers: StateFlow<List<String>> = _onlineUsers

    private val _rooms = MutableStateFlow<List<ChatRoom>>(emptyList())
    val rooms: StateFlow<List<ChatRoom>> = _rooms

    private val _users = MutableStateFlow<List<ChatUser>>(emptyList())
    val users: StateFlow<List<ChatUser>> = _users

    private val _historyLoaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val historyLoaded: SharedFlow<String> = _historyLoaded

    private val _roomsLoaded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val roomsLoaded: SharedFlow<Unit> = _roomsLoaded

    private val _usersLoaded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val usersLoaded: SharedFlow<Unit> = _usersLoaded

    private val _connectionError = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val connectionError: SharedFlow<Unit> = _connectionError

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    fun init(context: Context, userId: String, userName: String?, avatarUrl: String?) {
        if (userId.isBlank()) return
        this.context = context.applicationContext

        // Start observing server config from Firestore reactively
        observeServerConfig()

        val wasConnected = _connectionState.value == ConnectionState.CONNECTED
        if (this.userId == userId && wasConnected) {
            // User already initialized and connected, just resend auth with updated name/avatar
            this.userName = userName?.takeIf { it.isNotBlank() } ?: "User"
            this.avatarUrl = avatarUrl.orEmpty()
            sendAuth()
            return
        }
        this.userId = userId
        this.userName = userName?.takeIf { it.isNotBlank() } ?: "User"
        this.avatarUrl = avatarUrl.orEmpty()
        manuallyClosed = false
        connect()
    }

    fun close() {
        manuallyClosed = true
        reconnectScheduled = false
        val socketToCancel = webSocket
        webSocket = null
        try {
            socketToCancel?.close(1000, "User logged out")
        } catch (_: Exception) {}
        userId = ""
        userName = "User"
        avatarUrl = ""
        activeRooms.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun connect() {
        if (userId.isBlank() || _connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) return

        val ctx = context
        if (ctx != null && !com.shuaib.classmate.utils.NetworkMonitor.isOnline(ctx)) {
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        // Nullify reference before cancel to ensure the guard in listener works
        val socketToCancel = webSocket
        webSocket = null
        socketToCancel?.cancel()

        val request = runCatching { Request.Builder().url(serverUrl).build() }
            .getOrElse { error ->
                Log.e("ChatDebug", "Invalid chat server URL: $serverUrl", error)
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
                return
            }
        webSocket = client.newWebSocket(request, listener)
    }

    fun updateServerUrl(newUrl: String) {
        val normalizedUrl = normalizeServerUrl(newUrl)
        if (normalizedUrl.isBlank() || serverUrl == normalizedUrl) return
        serverUrl = normalizedUrl
        manuallyClosed = false
        reconnectScheduled = false
        _connectionState.value = ConnectionState.DISCONNECTED

        val socketToCancel = webSocket
        webSocket = null
        socketToCancel?.cancel()

        connect()
    }

    fun sendMessage(
        roomId: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null
    ) {
        val trimmed = text.trim()
        if (roomId.isBlank() || trimmed.isBlank()) return
        sendJson(
            JSONObject()
                .put("type", "send_message")
                .put("roomId", roomId)
                .put("text", trimmed)
                .putNullable("replyToId", replyToId)
                .putNullable("replyToText", replyToText)
                .putNullable("replyToSender", replyToSender)
        )
        _incomingMessage.value = ChatMessage(
            id = "temp_${System.currentTimeMillis()}",
            roomId = roomId,
            senderId = userId,
            senderName = userName,
            senderAvatarUrl = avatarUrl,
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            isDeleted = false,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender
        )
    }

    fun sendImage(roomId: String, imageUrl: String, caption: String) {
        if (roomId.isBlank() || imageUrl.isBlank()) return
        sendJson(
            JSONObject()
                .put("type", "send_image")
                .put("roomId", roomId)
                .put("imageUrl", imageUrl)
                .put("caption", caption.trim())
        )
    }

    fun enterRoom(roomId: String) {
        if (roomId.isNotBlank()) activeRooms.add(roomId)
    }

    fun leaveRoom(roomId: String) {
        activeRooms.remove(roomId)
    }

    fun getHistory(roomId: String) {
        if (roomId.isBlank()) return
        if (_connectionState.value != ConnectionState.CONNECTED) {
            pendingSetRooms.add(roomId)
            pendingHistoryRooms.add(roomId)
            connectIfNeeded()
            return
        }
        sendRoomHistoryRequest(roomId)
    }

    fun setRoom(roomId: String) {
        if (roomId.isBlank()) return
        if (_connectionState.value != ConnectionState.CONNECTED) {
            pendingSetRooms.add(roomId)
            connectIfNeeded()
            return
        }
        sendJson(JSONObject().put("type", "set_room").put("roomId", roomId))
    }

    fun getRooms() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            pendingGetRooms = true
            connectIfNeeded()
            return
        }
        sendJson(JSONObject().put("type", "get_rooms"))
    }

    fun createDm(targetUserId: String, targetUserName: String) {
        if (userId.isBlank() || targetUserId.isBlank() || targetUserId == userId) return
        sendJson(
            JSONObject()
                .put("type", "create_dm")
                .put("targetUserId", targetUserId)
                .put("targetUserName", targetUserName.ifBlank { "User" })
        )
    }

    fun deleteMessage(messageId: String, roomId: String, isAdmin: Boolean) {
        sendJson(
            JSONObject()
                .put("type", "delete_message")
                .put("messageId", messageId)
                .put("roomId", roomId)
                .put("isAdmin", isAdmin)
        )
    }

    fun getUsers() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            pendingGetUsers = true
            connectIfNeeded()
            return
        }
        sendJson(JSONObject().put("type", "get_users"))
    }

    fun sendTyping(roomId: String) {
        if (roomId.isBlank()) return
        sendJson(JSONObject().put("type", "typing").put("roomId", roomId), queueWhenDisconnected = false)
    }

    fun editMessage(messageId: String, roomId: String, newText: String) {
        sendJson(JSONObject().put("type", "edit_message").put("messageId", messageId).put("roomId", roomId).put("newText", newText))
    }

    fun reactMessage(messageId: String, roomId: String, emoji: String) {
        sendJson(JSONObject().put("type", "react_message").put("messageId", messageId).put("roomId", roomId).put("emoji", emoji))
    }

    fun seenMessage(messageId: String, roomId: String) {
        sendJson(JSONObject().put("type", "seen_message").put("messageId", messageId).put("roomId", roomId))
    }

    fun forwardMessage(originalMessageId: String, targetRoomId: String) {
        sendJson(JSONObject().put("type", "forward_message").put("originalMessageId", originalMessageId).put("targetRoomId", targetRoomId))
    }

    fun pinMessage(messageId: String, roomId: String, pin: Boolean) {
        sendJson(JSONObject().put("type", "pin_message").put("messageId", messageId).put("roomId", roomId).put("pin", pin))
    }

    fun getPinned(roomId: String) {
        sendJson(JSONObject().put("type", "get_pinned").put("roomId", roomId))
    }

    fun searchMessages(roomId: String, query: String) {
        sendJson(JSONObject().put("type", "search_messages").put("roomId", roomId).put("query", query))
    }

    fun dmRoomIdFor(targetUserId: String): String {
        return listOf(userId, targetUserId).sorted().joinToString(separator = "_", prefix = "dm_")
    }

    private fun observeServerConfig() {
        if (configJob != null) return
        configJob = scope.launch {
            callbackFlow {
                val listener = FirebaseFirestore.getInstance()
                    .collection("config")
                    .document("chat")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) return@addSnapshotListener
                        snapshot?.getString("serverUrl")?.let { trySend(it) }
                    }
                awaitClose { listener.remove() }
            }.collect { newUrl ->
                updateServerUrl(newUrl)
            }
        }
    }

    private fun sendAuth() {
        val authJson = JSONObject()
            .put("type", "auth")
            .put("userId", userId)
            .put("userName", userName)
            .put("avatarUrl", avatarUrl)
        sendJson(authJson, queueWhenDisconnected = false)
        flushPendingRequests()
    }

    private fun sendJson(json: JSONObject, queueWhenDisconnected: Boolean = true): Boolean {
        val raw = json.toString()
        if (_connectionState.value != ConnectionState.CONNECTED) {
            if (queueWhenDisconnected) pendingOutbound.offer(raw)
            connectIfNeeded()
            return false
        }
        val sent = webSocket?.send(raw) == true
        if (!sent && queueWhenDisconnected) {
            pendingOutbound.offer(raw)
            connectIfNeeded()
        }
        return sent
    }

    private fun sendRaw(json: String, queueWhenDisconnected: Boolean = true): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            if (queueWhenDisconnected) pendingOutbound.offer(json)
            connectIfNeeded()
            return false
        }
        val sent = webSocket?.send(json) == true
        if (!sent && queueWhenDisconnected) {
            pendingOutbound.offer(json)
            connectIfNeeded()
        }
        return sent
    }

    private fun connectIfNeeded() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) {
            connect()
        }
    }

    private fun flushPendingRequests() {
        val roomsToSet = pendingSetRooms.toList()
        pendingSetRooms.clear()
        roomsToSet.forEach { roomId ->
            sendJson(JSONObject().put("type", "set_room").put("roomId", roomId))
        }

        val historiesToLoad = pendingHistoryRooms.toList()
        pendingHistoryRooms.clear()
        historiesToLoad.forEach { roomId -> sendRoomHistoryRequest(roomId) }

        if (pendingGetRooms) {
            pendingGetRooms = false
            getRooms()
        }
        if (pendingGetUsers) {
            pendingGetUsers = false
            getUsers()
        }

        while (true) {
            val raw = pendingOutbound.poll() ?: break
            sendRaw(raw, queueWhenDisconnected = false)
        }
    }

    private fun sendRoomHistoryRequest(roomId: String) {
        val escapedRoomId = JSONObject.quote(roomId)
        sendRaw("""{"type":"set_room","roomId":$escapedRoomId}""")
        sendRaw("""{"type":"get_history","roomId":$escapedRoomId,"limit":50}""")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== this@ChatRepository.webSocket) return
            reconnectScheduled = false
            _connectionState.value = ConnectionState.CONNECTED
            sendAuth()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== this@ChatRepository.webSocket) return
            runCatching {
                val jsonObject = JSONObject(text)
                if (jsonObject.optString("type") == "rooms") {
                    Log.d("ChatDebug", "RAW rooms JSON: $text")
                    val roomsArray = jsonObject.getJSONArray("rooms")
                    Log.d("ChatDebug", "Rooms array length: ${roomsArray.length()}")
                }
                handleMessage(jsonObject)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@ChatRepository.webSocket) return
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@ChatRepository.webSocket) return
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectionError.tryEmit(Unit)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== this@ChatRepository.webSocket) return

            val message = t.message ?: "Unknown error"
            // Socket closed is common during network switches or manual cancels
            if (message.contains("Socket closed", ignoreCase = true) ||
                message.contains("Canceled", ignoreCase = true) ||
                message.contains("Software caused connection abort", ignoreCase = true)) {
                Log.w("ChatDebug", "Chat WebSocket disconnected: $message")
            } else {
                Log.e("ChatDebug", "Chat WebSocket failure: $message", t)
            }

            _connectionState.value = ConnectionState.DISCONNECTED
            _connectionError.tryEmit(Unit)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed || reconnectScheduled || userId.isBlank()) return

        val ctx = context
        if (ctx != null && !com.shuaib.classmate.utils.NetworkMonitor.isOnline(ctx)) {
            // Delay longer if offline to save battery, but keep trying
            reconnectScheduled = true
            scope.launch {
                delay(10_000L)
                reconnectScheduled = false
                connect()
            }
            return
        }

        reconnectScheduled = true
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectScheduled = false
            connect()
        }
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "message", "new_message" -> {
                parseMessage(json)?.let { message ->
                    _incomingMessage.value = message

                    // Push Notification Logic
                    if (message.senderId != userId && message.roomId !in activeRooms) {
                        context?.let { ctx ->
                            val roomType = if (message.roomId.startsWith("dm_")) "dm" else "group"
                            ChatNotificationHelper.showMessageNotification(
                                context = ctx,
                                roomId = message.roomId,
                                roomType = roomType,
                                senderId = message.senderId,
                                senderName = message.senderName,
                                senderAvatar = message.senderAvatarUrl ?: "",
                                messageText = message.text,
                                timestamp = message.timestamp
                            )
                        }
                    }
                }
            }
            "message_deleted", "delete_message", "deleted" -> parseDeletedMessage(json)?.let { _incomingMessage.value = it }
            "message_edited", "message_reacted", "message_seen", "message_pinned" -> parseMessage(json)?.let { _messageUpdate.tryEmit(it) }
            "search_results" -> _searchResults.value = parseMessages(json.optJSONArray("messages") ?: json.optJSONArray("results") ?: json.optJSONArray("data"))
            "pinned_messages" -> _pinnedMessages.value = parseMessages(json.optJSONArray("messages") ?: json.optJSONArray("pinned") ?: json.optJSONArray("data"))
            "history" -> {
                val messages = parseMessages(json.optJSONArray("messages") ?: json.optJSONArray("data"))
                _historyMessages.value = messages
                val roomId = json.optString("roomId")
                    .ifBlank { json.optJSONObject("room")?.optString("id").orEmpty() }
                    .ifBlank { messages.firstOrNull()?.roomId.orEmpty() }
                if (roomId.isNotBlank()) _historyLoaded.tryEmit(roomId)
            }
            "rooms", "room_list" -> {
                val roomsArray = if (json.has("rooms")) json.getJSONArray("rooms") else json.optJSONArray("data")
                val roomsList = parseRooms(roomsArray)
                _rooms.value = roomsList
                _roomsLoaded.tryEmit(Unit)
            }
            "room", "dm_room", "dm_created" -> parseRoom(json.optJSONObject("room") ?: json.optJSONObject("data") ?: json)?.let {
                upsertRoom(it)
                _dmCreated.tryEmit(it)
            }
            "users", "user_list" -> {
                _users.value = parseUsers(json.optJSONArray("users") ?: json.optJSONArray("data"))
                _usersLoaded.tryEmit(Unit)
            }
            "online_users", "online" -> _onlineUsers.value = parseStrings(json.optJSONArray("userIds"))
            "user_typing", "typing" -> parseTypingEvent(json)?.let { _typingEvent.tryEmit(it) }
        }
    }

    private fun parseMessage(json: JSONObject): ChatMessage? {
        val source = json.optJSONObject("message") ?: json.optJSONObject("data") ?: json
        val id = source.optString("id", source.optString("messageId"))
        val roomId = source.optString("roomId")
        if (id.isBlank() || roomId.isBlank()) return null
        return ChatMessage(
            id = id,
            roomId = roomId,
            senderId = source.optString("senderId", source.optString("userId")),
            senderName = source.optString("senderName", source.optString("userName", "Unknown")),
            senderAvatarUrl = source.optString("senderAvatarUrl", source.optString("avatarUrl", "")),
            text = source.optString("text", source.optString("newText", source.optString("message"))),
            timestamp = normalizeTimestamp(source.optLong("timestamp", System.currentTimeMillis())),
            isDeleted = source.optBoolean("isDeleted", false),
            editedAt = source.optNullableLong("editedAt"),
            replyToId = source.optNullableString("replyToId"),
            replyToText = source.optNullableString("replyToText"),
            replyToSender = source.optNullableString("replyToSender"),
            reactions = parseReactions(source.optJSONArray("reactions")),
            seenBy = parseStrings(source.optJSONArray("seenBy")),
            forwardedFrom = source.optNullableString("forwardedFrom"),
            isPinned = source.optBoolean("isPinned", source.optBoolean("pin", false)),
            imageUrl = source.optNullableString("imageUrl")
        )
    }

    private fun parseDeletedMessage(json: JSONObject): ChatMessage? {
        val source = json.optJSONObject("message") ?: json.optJSONObject("data") ?: json
        val id = source.optString("id", source.optString("messageId"))
        val roomId = source.optString("roomId")
        if (id.isBlank() || roomId.isBlank()) return null
        return ChatMessage(
            id = id,
            roomId = roomId,
            senderId = source.optString("senderId"),
            senderName = source.optString("senderName"),
            senderAvatarUrl = source.optString("senderAvatarUrl", source.optString("avatarUrl", "")),
            text = source.optString("text"),
            timestamp = normalizeTimestamp(source.optLong("timestamp", System.currentTimeMillis())),
            isDeleted = true
        )
    }

    private fun parseMessages(array: JSONArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { parseMessage(it) }
        }
    }

    private fun parseRoom(json: JSONObject?): ChatRoom? {
        if (json == null) return null
        val id = json.optString("id", json.optString("roomId"))
        if (id.isBlank()) return null
        return ChatRoom(
            id = id,
            type = json.optString("type", "dm"),
            name = json.optString("name"),
            member1Id = json.optString("member1Id", json.optString("member1_id", "")),
            member2Id = json.optString("member2Id", json.optString("member2_id", "")),
            otherUserId = json.optString("otherUserId", json.optString("other_user_id", "")),
            otherUserName = json.optString("otherUserName", json.optString("other_user_name", "")),
            otherUserAvatar = json.optString("otherUserAvatar", json.optString("other_user_avatar", "")),
            lastMessage = json.optString("lastMessage", json.optString("last_message", "")),
            lastMessageTime = normalizeTimestamp(json.optLong("lastMessageTime", json.optLong("lastTimestamp", json.optLong("last_timestamp", 0L)))),
            unreadCount = json.optInt("unreadCount", 0),
            memberCount = json.optInt("memberCount", 24),
            avatarUrl = json.optString("avatarUrl", json.optString("avatar_url", ""))
        )
    }

    private fun parseRooms(array: JSONArray?): List<ChatRoom> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { parseRoom(it) }
        }
    }

    private fun parseUsers(array: JSONArray?): List<ChatUser> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val id = json.optString("id", json.optString("userId", json.optString("uid")))
            if (id.isBlank()) return@mapNotNull null
            val name = json.optString("name")
                .ifBlank { json.optString("userName") }
                .ifBlank { json.optString("fullName") }
                .ifBlank { json.optString("email").substringBefore("@") }
                .ifBlank { "User" }
            ChatUser(
                id = id,
                name = name,
                avatarUrl = json.optString("avatarUrl", json.optString("photoUrl")),
                isOnline = json.optBoolean("isOnline", json.optBoolean("online", _onlineUsers.value.contains(id)))
            )
        }
    }

    private fun parseStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun parseReactions(array: JSONArray?): List<MessageReaction> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            MessageReaction(
                emoji = json.optString("emoji"),
                userIds = parseStrings(json.optJSONArray("userIds"))
            )
        }
    }

    private fun parseTypingEvent(json: JSONObject): TypingEvent? {
        val roomId = json.optString("roomId")
        val typingUserId = json.optString("userId")
        if (roomId.isBlank() || typingUserId.isBlank()) return null
        return TypingEvent(
            roomId = roomId,
            userId = typingUserId,
            userName = json.optString("userName", "Someone")
        )
    }

    private fun upsertRoom(room: ChatRoom) {
        _rooms.value = _rooms.value.filterNot { it.id == room.id } + room
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        return if (timestamp in 1..9_999_999_999L) timestamp * 1000 else timestamp
    }

    private fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://${trimmed.removePrefix("https://")}"
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://${trimmed.removePrefix("http://")}"
            else -> trimmed
        }
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject {
        if (!value.isNullOrBlank()) put(name, value)
        return this
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return optString(name).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) normalizeTimestamp(optLong(name)) else null
    }
}
