package com.shuaib.classmate.chat.model

data class AiMessage(
    val id: String = "",
    val text: String = "",
    val sender: String = "", // "user" or "ai"
    val timestamp: Long = 0L
)
