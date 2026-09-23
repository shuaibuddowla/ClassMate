package com.shuaib.classmate.ai

import android.util.Log
import com.shuaib.classmate.ClassMateApp
import com.shuaib.classmate.utils.AppPreferences

class AiCoordinator(
    private val geminiProvider: GeminiAiProvider,
    private val groqProvider: GroqAiProvider
) {

    private fun isGeminiPrimary(): Boolean {
        return try {
            AppPreferences(ClassMateApp.instance).isGeminiPrimary()
        } catch (_: Throwable) {
            true
        }
    }

    suspend fun summarizeNotice(input: NoticeSummaryInput): Result<AiResult<String>> {
        val geminiPrimary = isGeminiPrimary()
        if (geminiPrimary) {
            Log.d("AiCoordinator", "Trying AI provider: Gemini (Primary - Summarize)")
            val geminiResult = geminiProvider.summarizeNotice(input)
            if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                return Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI"))
            }

            val error = geminiResult.exceptionOrNull() ?: Exception("Unknown Gemini error")
            Log.e("AiCoordinator", "Gemini failed: ${error.message}. Falling back to Groq...")

            Log.d("AiCoordinator", "Trying AI provider: Groq (Fallback)")
            val groqResult = groqProvider.summarizeNotice(input)
            return if (groqResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Groq")
                Result.success(AiResult(groqResult.getOrThrow(), "GROQ", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        } else {
            Log.d("AiCoordinator", "Trying AI provider: Groq (Primary - Summarize)")
            val groqResult = groqProvider.summarizeNotice(input)
            if (groqResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Groq")
                return Result.success(AiResult(groqResult.getOrThrow(), "GROQ"))
            }

            val error = groqResult.exceptionOrNull() ?: Exception("Unknown Groq error")
            Log.e("AiCoordinator", "Groq failed: ${error.message}. Falling back to Gemini...")

            Log.d("AiCoordinator", "Trying AI provider: Gemini (Fallback)")
            val geminiResult = geminiProvider.summarizeNotice(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }
    }

    suspend fun generateNoticeDraft(input: NoticeDraftInput): Result<AiResult<com.shuaib.classmate.models.AiNoticeDraft>> {
        val geminiPrimary = isGeminiPrimary()
        if (geminiPrimary) {
            Log.d("AiCoordinator", "Trying AI provider: Gemini (Primary - Draft)")
            val geminiResult = geminiProvider.generateNoticeDraft(input)
            if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                return Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI"))
            }

            val error = geminiResult.exceptionOrNull() ?: Exception("Unknown Gemini error")
            Log.e("AiCoordinator", "Gemini failed: ${error.message}. Falling back to Groq...")

            Log.d("AiCoordinator", "Trying AI provider: Groq (Fallback)")
            val groqResult = groqProvider.generateNoticeDraft(input)
            return if (groqResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Groq")
                Result.success(AiResult(groqResult.getOrThrow(), "GROQ", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        } else {
            Log.d("AiCoordinator", "Trying AI provider: Groq (Primary - Draft)")
            val groqResult = groqProvider.generateNoticeDraft(input)
            if (groqResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Groq")
                return Result.success(AiResult(groqResult.getOrThrow(), "GROQ"))
            }

            val error = groqResult.exceptionOrNull() ?: Exception("Unknown Groq error")
            Log.e("AiCoordinator", "Groq failed: ${error.message}. Falling back to Gemini...")

            Log.d("AiCoordinator", "Trying AI provider: Gemini (Fallback)")
            val geminiResult = geminiProvider.generateNoticeDraft(input)
            return if (geminiResult.isSuccess) {
                Log.d("AiCoordinator", "AI provider used: Gemini")
                Result.success(AiResult(geminiResult.getOrThrow(), "GEMINI", true, error.message))
            } else {
                Log.e("AiCoordinator", "Both AI providers failed")
                Result.failure(Exception("AI is temporarily unavailable. Please try again or continue manually."))
            }
        }
    }
}
