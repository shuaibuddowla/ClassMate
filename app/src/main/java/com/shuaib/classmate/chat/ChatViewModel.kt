package com.shuaib.classmate.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.chat.model.ChatMessage
import com.shuaib.classmate.chat.model.ChatRoom
import com.shuaib.classmate.notices.NoticeForwardManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val repository = ChatRepository

    var currentRoomId: String? = null
        private set

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    val connectionState = repository.connectionState
    private val _rooms = MutableStateFlow<List<ChatRoom>>(emptyList())
    val rooms: StateFlow<List<ChatRoom>> = _rooms
    val users = repository.users
    val onlineUsers = repository.onlineUsers
    val typingEvent = repository.typingEvent
    val searchResults = repository.searchResults
    val pinnedMessages = repository.pinnedMessages
    val historyLoaded = repository.historyLoaded
    val roomsLoaded = repository.roomsLoaded
    val usersLoaded = repository.usersLoaded
    val connectionError = repository.connectionError

    private val _dmCreated = MutableSharedFlow<ChatRoom>(extraBufferCapacity = 4)
    val dmCreated: SharedFlow<ChatRoom> = _dmCreated

    init {
        viewModelScope.launch {
            repository.rooms.collect { rooms ->
                _rooms.value = rooms
            }
        }

        viewModelScope.launch {
            repository.incomingMessage.collect { message ->
                if (message == null) return@collect
                updateRoomPreview(message)
                val roomId = currentRoomId ?: return@collect
                if (message.roomId != roomId) return@collect
                _messages.value = if (message.isDeleted) {
                    _messages.value.map { existing ->
                        if (existing.id == message.id) {
                            existing.copy(text = "", isDeleted = true)
                        } else {
                            existing
                        }
                    }
                } else {
                    val tempMatch = _messages.value.firstOrNull { existing ->
                        existing.id.startsWith(TEMP_MESSAGE_PREFIX) &&
                            existing.roomId == message.roomId &&
                            existing.senderId == message.senderId &&
                            existing.text == message.text &&
                            kotlin.math.abs(message.timestamp - existing.timestamp) <= TEMP_REPLACE_WINDOW_MS
                    }
                    if (tempMatch != null) {
                        _messages.value.map { existing ->
                            if (existing.id == tempMatch.id) message else existing
                        }
                    } else {
                        val withoutDuplicate = _messages.value.filterNot { it.id == message.id }
                        (withoutDuplicate + message).sortedBy { it.timestamp }
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.historyMessages.collect { history ->
                val roomId = currentRoomId ?: return@collect
                val current = _messages.value
                val merged = (history.filter { it.roomId == roomId } + current)
                    .distinctBy { it.id }
                    .sortedBy { it.timestamp }
                _messages.value = merged
            }
        }

        viewModelScope.launch {
            repository.dmCreated.collect { room ->
                _dmCreated.emit(room)
            }
        }

        viewModelScope.launch {
            repository.messageUpdate.collect { message ->
                _messages.value = _messages.value.map { existing ->
                    if (existing.id == message.id) mergeMessageUpdate(existing, message) else existing
                }
            }
        }
    }

    fun openRoom(roomId: String) {
        val changedRoom = currentRoomId != roomId
        currentRoomId = roomId
        if (changedRoom) {
            _messages.value = emptyList()
        }
        repository.enterRoom(roomId)
        repository.setRoom(roomId)
        repository.getHistory(roomId)
        repository.getPinned(roomId)
    }

    fun sendMessage(
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null
    ) {
        val roomId = currentRoomId ?: return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val currentUser = FirebaseAuth.getInstance().currentUser
        val timestamp = System.currentTimeMillis()
        val message = ChatMessage(
            id = "$TEMP_MESSAGE_PREFIX$text-$timestamp",
            roomId = roomId,
            senderId = currentUser?.uid.orEmpty(),
            senderName = currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "User",
            senderAvatarUrl = currentUser?.photoUrl?.toString().orEmpty(),
            text = trimmed,
            timestamp = timestamp,
            isDeleted = false,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender
        )
        _messages.value = (_messages.value + message).sortedBy { it.timestamp }
        updateRoomPreview(message)
        repository.sendMessage(roomId, trimmed, replyToId, replyToText, replyToSender)
    }

    fun deleteMessage(messageId: String, isAdmin: Boolean) {
        val roomId = currentRoomId ?: return
        repository.deleteMessage(messageId, roomId, isAdmin)
    }

    fun loadMoreHistory() {
        currentRoomId?.let { repository.getHistory(it) }
    }

    fun refreshCurrentRoom() {
        currentRoomId?.let { roomId ->
            repository.setRoom(roomId)
            repository.getHistory(roomId)
            repository.getPinned(roomId)
        }
    }

    fun startDm(targetUserId: String, targetUserName: String) {
        if (targetUserId.isBlank()) return
        repository.createDm(targetUserId, targetUserName)
    }

    fun refreshUsers() {
        repository.getUsers()
    }

    fun refreshRooms() {
        repository.getRooms()
    }

    fun sendTyping(roomId: String) {
        repository.sendTyping(roomId)
    }

    fun editMessage(messageId: String, newText: String) {
        val roomId = currentRoomId ?: return
        repository.editMessage(messageId, roomId, newText)
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(text = newText, editedAt = System.currentTimeMillis()) else it
        }
    }

    fun reactMessage(messageId: String, emoji: String) {
        currentRoomId?.let { repository.reactMessage(messageId, it, emoji) }
    }

    fun forwardMessage(messageId: String, targetRoomId: String) {
        repository.forwardMessage(messageId, targetRoomId)
    }

    fun pinMessage(messageId: String, pin: Boolean) {
        val roomId = currentRoomId ?: return
        repository.pinMessage(messageId, roomId, pin)
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(isPinned = pin) else it
        }
        repository.getPinned(roomId)
    }

    fun searchMessages(query: String) {
        val roomId = currentRoomId ?: return
        repository.searchMessages(roomId, query)
    }

    fun sendImage(roomId: String, imageUrl: String, caption: String) {
        repository.sendImage(roomId, imageUrl, caption)
        val currentUser = FirebaseAuth.getInstance().currentUser
        val timestamp = System.currentTimeMillis()
        val message = ChatMessage(
            id = "$TEMP_MESSAGE_PREFIX$imageUrl-$timestamp",
            roomId = roomId,
            senderId = currentUser?.uid.orEmpty(),
            senderName = currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "User",
            senderAvatarUrl = currentUser?.photoUrl?.toString().orEmpty(),
            text = caption.trim(),
            timestamp = timestamp,
            isDeleted = false,
            imageUrl = imageUrl
        )
        if (currentRoomId == roomId) _messages.value = _messages.value + message
        updateRoomPreview(message)
    }

    fun loadPinnedMessages() {
        currentRoomId?.let { repository.getPinned(it) }
    }

    fun markSeen(messageId: String) {
        currentRoomId?.let { repository.seenMessage(messageId, it) }
    }

    private fun updateRoomPreview(message: ChatMessage) {
        if (message.isDeleted) return
        val preview = if (message.text.isNotBlank()) NoticeForwardManager.previewText(message.text) else "Photo"
        val current = _rooms.value
        val updated = current.map {
            if (it.id == message.roomId) {
                it.copy(lastMessage = preview, lastMessageTime = message.timestamp)
            } else {
                it
            }
        }
        _rooms.value = if (updated.any { it.id == message.roomId }) {
            updated
        } else {
            current + ChatRoom(
                id = message.roomId,
                type = if (message.roomId == "group_main") "group" else "dm",
                name = if (message.roomId == "group_main") "CODRIX-22" else "",
                member1Id = "",
                member2Id = "",
                lastMessage = preview,
                lastMessageTime = message.timestamp
            )
        }
    }

    private fun mergeMessageUpdate(existing: ChatMessage, update: ChatMessage): ChatMessage {
        return existing.copy(
            text = update.text.ifBlank { existing.text },
            timestamp = if (kotlin.math.abs(update.timestamp - System.currentTimeMillis()) < 1_000L && existing.timestamp > 0) existing.timestamp else update.timestamp,
            isDeleted = update.isDeleted || existing.isDeleted,
            editedAt = update.editedAt ?: existing.editedAt,
            replyToId = update.replyToId ?: existing.replyToId,
            replyToText = update.replyToText ?: existing.replyToText,
            replyToSender = update.replyToSender ?: existing.replyToSender,
            reactions = if (update.reactions.isNotEmpty() || existing.reactions.isNotEmpty()) update.reactions else existing.reactions,
            seenBy = if (update.seenBy.isNotEmpty() || existing.seenBy.isNotEmpty()) update.seenBy else existing.seenBy,
            forwardedFrom = update.forwardedFrom ?: existing.forwardedFrom,
            isPinned = update.isPinned,
            imageUrl = update.imageUrl ?: existing.imageUrl
        )
    }

    companion object {
        private const val TEMP_MESSAGE_PREFIX = "temp_"
        private const val TEMP_REPLACE_WINDOW_MS = 10_000L
    }
}
