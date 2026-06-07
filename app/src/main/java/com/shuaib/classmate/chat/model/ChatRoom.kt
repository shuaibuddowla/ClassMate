package com.shuaib.classmate.chat.model

data class ChatRoom(
    val id: String,
    val type: String,
    val name: String,
    val member1Id: String = "",
    val member2Id: String = "",
    val otherUserId: String = "",
    val otherUserName: String = "",
    val otherUserAvatar: String = "",
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val memberCount: Int = 24,
    val avatarUrl: String = ""
) {
    val lastTimestamp: Long
        get() = lastMessageTime
}
