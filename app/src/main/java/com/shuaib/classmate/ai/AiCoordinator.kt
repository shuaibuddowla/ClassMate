package com.shuaib.classmate.ai

import android.util.Log

class AiCoordinator(
    private val geminiProvider: GeminiAiProvider,
    private val groqProvider: GroqAiProvider
) {

    suspend fun summarizeNotice(input: NoticeSummaryInput): Result<AiResult<String>> {
        val groqKey = com.shuaib.classmate.utils.AppConstants.GROQ_API_KEY
        val groqBlank = groqKey.isBlank() || groqKey.startsWith("TODO") || groqKey == "null"

        if (groqBlank) {
            Log.w("AiCoordinator", "Groq API key is blank, skipping to Gemini (Summarize)")
            val geminiKey = com.shuaib.classmate.utils.AppConstants.GEMINI_API_KEY
            val geminiBlank = geminiKey.isBlank() || geminiKey.startsWith("TODO") || geminiKey == "null"
            if (geminiBlank) {
                Log.e("AiCoordinator", "Both AI API keys are blank")
                return Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
            Log.d("AiCoordinator", "Trying AI provider: Gemini")
            val geminiResult = geminiProvider.summarizeNotice(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, "Groq API key is blank"))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed (Groq key blank, Gemini failed)")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }

        Log.d("AiCoordinator", "Trying AI provider: Groq")
        val groqResult = groqProvider.summarizeNotice(input)

        if (groqResult.isSuccess) {
            Log.d("AiCoordinator", "AI provider used: Groq")
            return Result.success(AiResult(groqResult.getOrThrow(), "GROQ"))
        }

        val error = groqResult.exceptionOrNull()
        val statusCode = (error as? AiProviderError)?.statusCode
        val responseBody = (error as? AiProviderError)?.responseBody

        if (error != null && shouldFallbackToGemini(error, statusCode, responseBody)) {
            val geminiKey = com.shuaib.classmate.utils.AppConstants.GEMINI_API_KEY
            val geminiBlank = geminiKey.isBlank() || geminiKey.startsWith("TODO") || geminiKey == "null"
            if (geminiBlank) {
                Log.e("AiCoordinator", "Groq failed but Gemini API key is blank, cannot fallback")
                return Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
            Log.d("AiCoordinator", "Groq failed: quota exceeded, falling back to Gemini")
            Log.d("AiCoordinator", "Trying AI provider: Gemini")
            val geminiResult = geminiProvider.summarizeNotice(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }

        return Result.failure(error ?: Exception("Unknown Groq error"))
    }

    suspend fun generateNoticeDraft(input: NoticeDraftInput): Result<AiResult<com.shuaib.classmate.models.AiNoticeDraft>> {
        val groqKey = com.shuaib.classmate.utils.AppConstants.GROQ_API_KEY
        val groqBlank = groqKey.isBlank() || groqKey.startsWith("TODO") || groqKey == "null"

        if (groqBlank) {
            Log.w("AiCoordinator", "Groq API key is blank, skipping to Gemini (Draft)")
            val geminiKey = com.shuaib.classmate.utils.AppConstants.GEMINI_API_KEY
            val geminiBlank = geminiKey.isBlank() || geminiKey.startsWith("TODO") || geminiKey == "null"
            if (geminiBlank) {
                Log.e("AiCoordinator", "Both AI API keys are blank")
                return Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
            Log.d("AiCoordinator", "Trying AI provider: Gemini")
            val geminiResult = geminiProvider.generateNoticeDraft(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, "Groq API key is blank"))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed (Groq key blank, Gemini failed)")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }

        Log.d("AiCoordinator", "Trying AI provider: Groq")
        val groqResult = groqProvider.generateNoticeDraft(input)

        if (groqResult.isSuccess) {
            Log.d("AiCoordinator", "AI provider used: Groq")
            return Result.success(AiResult(groqResult.getOrThrow(), "GROQ"))
        }

        val error = groqResult.exceptionOrNull()
        val statusCode = (error as? AiProviderError)?.statusCode
        val responseBody = (error as? AiProviderError)?.responseBody

        if (error != null && shouldFallbackToGemini(error, statusCode, responseBody)) {
            val geminiKey = com.shuaib.classmate.utils.AppConstants.GEMINI_API_KEY
            val geminiBlank = geminiKey.isBlank() || geminiKey.startsWith("TODO") || geminiKey == "null"
            if (geminiBlank) {
                Log.e("AiCoordinator", "Groq failed but Gemini API key is blank, cannot fallback")
                return Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
            Log.d("AiCoordinator", "Groq failed: quota exceeded, falling back to Gemini")
            Log.d("AiCoordinator", "Trying AI provider: Gemini")
            val geminiResult = geminiProvider.generateNoticeDraft(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }

        return Result.failure(error ?: Exception("Unknown Groq error"))
    }

    private fun shouldFallbackToGemini(error: Throwable, statusCode: Int?, responseBody: String?): Boolean {
        // Network failures
        if (error is java.io.IOException || error is java.net.SocketTimeoutException || error is AiProviderError.Network) {
            return true
        }

        // HTTP status codes
        if (statusCode != null) {
            if (statusCode == 429) return true // Too many requests / rate limit
            if (statusCode == 403) {
                val body = responseBody?.lowercase() ?: ""
                if (body.contains("quota") || body.contains("billing") || body.contains("limit") || body.contains("exceeded") || body.contains("exhausted")) {
                    return true
                }
            }
            if (statusCode in 500..599) return true // Server error / gateway error
        }

        // Custom error types
        if (error is AiProviderError.QuotaExceeded || error is AiProviderError.RateLimited || error is AiProviderError.Server) {
            return true
        }

        // Error message checks
        val msg = (error.message ?: "").lowercase()
        val body = (responseBody ?: "").lowercase()

        fun checkText(text: String): Boolean {
            return text.contains("quota") ||
                   text.contains("rate limit") ||
                   text.contains("resource exhausted") ||
                   text.contains("billing") ||
                   text.contains("limit exceeded") ||
                   text.contains("free tier") ||
                   text.contains("too many requests") ||
                   text.contains("exhausted")
        }

        return checkText(msg) || checkText(body)
    }
}
