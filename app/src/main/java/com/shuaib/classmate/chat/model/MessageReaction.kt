package com.shuaib.classmate.chat.model

data class MessageReaction(
    val emoji: String,
    val userIds: List<String>
)
