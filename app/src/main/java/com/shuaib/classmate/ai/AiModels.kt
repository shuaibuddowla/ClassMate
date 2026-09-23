package com.shuaib.classmate.ai

import com.shuaib.classmate.models.AiNoticeDraft

data class NoticeSummaryInput(
    val title: String,
    val body: String,
    val type: String? = null,
    val subject: String? = null,
    val date: String? = null
)

data class NoticeDraftInput(
    val rawInput: String,
    val currentDate: String,
    val timezone: String = "Asia/Dhaka",
    val supportedTypes: List<String>,
    val knownSubjects: List<String>,
    val teacherContext: String? = null
)

data class AiResult<T>(
    val data: T,
    val providerUsed: String,
    val fallbackUsed: Boolean = false,
    val reason: String? = null
)

sealed class AiProviderError(
    val statusCode: Int? = null,
    val responseBody: String? = null,
    override val message: String
) : Throwable(message) {
    class QuotaExceeded(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class RateLimited(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class InvalidApiKey(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class Network(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class Server(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class InvalidResponse(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
    class Unknown(message: String, statusCode: Int? = null, responseBody: String? = null) : AiProviderError(statusCode, responseBody, message)
}
