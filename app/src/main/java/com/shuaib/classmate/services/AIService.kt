package com.shuaib.classmate.services

import android.util.Log
import com.google.gson.Gson
import com.shuaib.classmate.ai.AiCoordinator
import com.shuaib.classmate.ai.GeminiAiProvider
import com.shuaib.classmate.ai.GroqAiProvider
import com.shuaib.classmate.ai.NoticeDraftInput
import com.shuaib.classmate.ai.NoticeSummaryInput
import com.shuaib.classmate.ai.AiProviderError
import com.shuaib.classmate.models.AiNoticeDraft
import com.shuaib.classmate.models.Period
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ClassMate AI Service - Powered by Gemini & Groq
 * Handles intelligent scheduling summaries and notice analysis with fallback logic.
 */
object AIService {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val gson by lazy { Gson() }

    private val coordinator by lazy {
        AiCoordinator(
            GeminiAiProvider(client, gson),
            GroqAiProvider(client, gson)
        )
    }

    /**
     * Summarizes long notices into short, actionable bullet points or sentences.
     * Passes notice type and subject for richer context-aware summaries.
     */
    suspend fun summarizeNotice(
        title: String,
        content: String,
        type: String? = null,
        subject: String? = null,
        date: String? = null
    ): Result<String> {
        val input = NoticeSummaryInput(
            title = title,
            body = content,
            type = type?.ifBlank { null },
            subject = subject?.ifBlank { null },
            date = date?.ifBlank { null }
        )

        return try {
            val result = coordinator.summarizeNotice(input)
            if (result.isSuccess) {
                Result.success(result.getOrThrow().data)
            } else {
                val exception = result.exceptionOrNull() ?: Exception("AI failed to generate a summary.")
                Result.failure(exception)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun analyzeAndPolishNotice(
        messyText: String,
        currentDateStr: String,
        currentDayName: String,
        subjects: List<String>
    ): AiNoticeDraft? {
        // Course names come from the current batch/semester Firestore collection.
        val teacherContext = buildTeacherContext()

        val input = NoticeDraftInput(
            rawInput = messyText,
            currentDate = "$currentDateStr ($currentDayName)",
            supportedTypes = listOf("GENERAL", "CLASS_CANCELLATION", "ASSIGNMENT_DEADLINE", "CLASS_TEST", "EXAM", "RESOURCE", "VACATION", "HOLIDAY", "CLASS_SUSPENDED"),
            knownSubjects = subjects,
            teacherContext = teacherContext
        )

        return try {
            val result = coordinator.generateNoticeDraft(input)
            result.getOrNull()?.data
        } catch (e: Exception) {
            Log.e("AIService", "Notice polish failed: ${e.message}", e)
            null
        }
    }



    private fun buildTeacherContext(): String =
        "Use only the current batch and semester courses supplied in knownSubjects. " +
            "Do not invent course names, codes, teachers, rooms, or schedules."
}
