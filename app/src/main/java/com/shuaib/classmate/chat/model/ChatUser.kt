package com.shuaib.classmate.chat.model

data class ChatUser(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val isOnline: Boolean = false
)
