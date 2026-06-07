package com.shuaib.classmate.chat

import com.shuaib.classmate.chat.model.ChatRoom

sealed class ChatListItem {
    data class Header(val title: String) : ChatListItem()
    
    data class Room(
        val room: ChatRoom,
        val title: String,
        val subtitle: String,
        val avatarUrl: String = "",
        val isGroup: Boolean = false,
        val isOnline: Boolean = false,
        val otherUserId: String = ""
    ) : ChatListItem()
}