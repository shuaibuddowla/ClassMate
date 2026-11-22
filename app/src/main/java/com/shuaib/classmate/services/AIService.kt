package com.shuaib.classmate.services

import android.util.Log
import com.google.gson.Gson
import com.shuaib.classmate.ai.GeminiAiProvider
import com.shuaib.classmate.ai.NoticeDraftInput
import com.shuaib.classmate.ai.NoticeSummaryInput
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.models.AiNoticeDraft
import com.shuaib.classmate.utils.AppContextManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ClassMate AI Service - Unified AI Engine powered by Gemini & Supabase Edge Functions.
 * Handles multimodal speech-to-text, intelligent scheduling summaries, and notice drafting.
 */
object AIService {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson by lazy { Gson() }

    val geminiProvider by lazy { GeminiAiProvider(client, gson) }

    /**
     * Multimodal Audio Transcription powered by Gemini AI.
     * High precision for Bengali (বাংলা), English, and mixed academic speech.
     */
    suspend fun transcribeAudio(audioBytes: ByteArray, mimeType: String = "audio/mp4"): Result<String> {
        return geminiProvider.transcribeAudio(audioBytes, mimeType)
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
            geminiProvider.summarizeNotice(input)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converts raw/messy teacher announcements into structured, polished notices.
     * First attempts the server-side Supabase AI function; falls back to GeminiAiProvider.
     */
    suspend fun analyzeAndPolishNotice(
        messyText: String,
        currentDateStr: String,
        currentDayName: String,
        subjects: List<String>
    ): AiNoticeDraft? {
        val batch = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }

        // Attempt 1: Server-side Supabase Edge Function (classmate-ai auto_notice/compose)
        if (batch.isNotBlank() && ClassMateAuthApi.accessToken != null) {
            try {
                val reqId = UUID.randomUUID().toString()
                val response = ClassMateAuthApi.ai(
                    JSONObject()
                        .put("mode", "auto_notice")
                        .put("batch_id", batch)
                        .put("request_id", reqId)
                        .put("text", messyText)
                )
                val resultObj = response.optJSONObject("result")
                if (resultObj != null) {
                    val title = resultObj.optString("title").trim()
                    val body = resultObj.optString("body").trim()
                    if (title.isNotBlank() && body.isNotBlank()) {
                        return AiNoticeDraft(
                            title = title,
                            body = body,
                            type = "GENERAL",
                            confidence = 0.95
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("AIService", "Supabase Edge AI notice draft failed, using Gemini provider: ${e.message}")
            }
        }

        // Attempt 2: Gemini Direct Provider
        val input = NoticeDraftInput(
            rawInput = messyText,
            currentDate = "$currentDateStr ($currentDayName)",
            supportedTypes = listOf("GENERAL", "CLASS_CANCELLATION", "ASSIGNMENT_DEADLINE", "CLASS_TEST", "EXAM", "RESOURCE", "VACATION", "HOLIDAY", "CLASS_SUSPENDED"),
            knownSubjects = subjects,
            teacherContext = "Use only current batch courses supplied in knownSubjects."
        )

        return try {
            val result = geminiProvider.generateNoticeDraft(input)
            result.getOrNull()
        } catch (e: Exception) {
            Log.e("AIService", "Notice polish failed: ${e.message}", e)
            null
        }
    }
}
