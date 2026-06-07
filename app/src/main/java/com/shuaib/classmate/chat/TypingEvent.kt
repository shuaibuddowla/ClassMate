package com.shuaib.classmate.chat

data class TypingEvent(
    val roomId: String,
    val userId: String,
    val userName: String,
    val timestamp: Long = System.currentTimeMillis()
)
