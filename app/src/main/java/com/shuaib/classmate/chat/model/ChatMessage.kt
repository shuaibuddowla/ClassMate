package com.shuaib.classmate.chat.model

data class ChatMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String = "",
    val text: String,
    val timestamp: Long,
    val isDeleted: Boolean,
    val editedAt: Long? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val seenBy: List<String> = emptyList(),
    val forwardedFrom: String? = null,
    val isPinned: Boolean = false,
    val imageUrl: String? = null
)
