package com.shuaib.classmate.services

import android.util.Log
import com.google.gson.Gson
import com.shuaib.classmate.ai.AiCoordinator
import com.shuaib.classmate.ai.GeminiAiProvider
import com.shuaib.classmate.ai.GroqAiProvider
import com.shuaib.classmate.ai.NoticeDraftInput
import com.shuaib.classmate.ai.NoticeSummaryInput
import com.shuaib.classmate.models.AiNoticeDraft
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.AppConstants
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

    private fun isKeyMissing(): Boolean {
        val key = AppConstants.GEMINI_API_KEY
        val groqKey = AppConstants.GROQ_API_KEY
        return (key.isBlank() || key.startsWith("TODO") || key == "null") &&
               (groqKey.isBlank() || groqKey.startsWith("TODO") || groqKey == "null")
    }

    /**
     * Generates a warm, friendly morning summary of classes and assignments.
     */
    suspend fun generateMorningBrief(periods: List<Period>, assignments: List<String>): String? {
        if (isKeyMissing()) return null

        val prompt = StringBuilder().apply {
            append("You are ClassMate AI, a friendly and helpful student assistant. ")
            append("Generate a warm, encouraging morning brief for a student's day. ")
            if (periods.isEmpty() && assignments.isEmpty()) {
                append("Today is completely free with no classes or assignments. Tell them to relax and have a great day!")
            } else {
                append("Here is their schedule for today:\n")
                periods.forEach { append("- ${it.subject} at ${it.startTime} with ${it.teacher}\n") }
                if (assignments.isNotEmpty()) {
                    append("\nDeadlines today:\n")
                    assignments.forEach { append("- $it\n") }
                }
                append("\nProvide a concise (max 80 words) summary. Highlight the first class time and any tight schedules. Use a supportive tone.")
            }
        }.toString()

        return try {
            // Reusing summarizeNotice logic for simple text prompt for now
            val input = NoticeSummaryInput("Morning Brief", prompt)
            val result = coordinator.summarizeNotice(input)
            result.getOrNull()?.data
        } catch (e: Exception) {
            Log.e("AIService", "Brief generation failed: ${e.message}")
            null
        }
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
    ): String? {
        if (isKeyMissing()) {
            Log.e("AIService", "AI API Keys are missing or invalid.")
            return null
        }

        val input = NoticeSummaryInput(
            title = title,
            body = content,
            type = type?.ifBlank { null },
            subject = subject?.ifBlank { null },
            date = date?.ifBlank { null }
        )

        return try {
            val result = coordinator.summarizeNotice(input)
            val aiResult = result.getOrNull()
            if (aiResult == null) {
                Log.w("AIService", "AI generated an empty response or failed.")
                null
            } else {
                aiResult.data
            }
        } catch (e: Exception) {
            Log.e("AIService", "Notice summary failed: ${e.message}", e)
            null
        }
    }

    suspend fun analyzeAndPolishNotice(
        messyText: String,
        currentDateStr: String,
        currentDayName: String,
        subjects: List<String>
    ): AiNoticeDraft? {
        if (isKeyMissing()) return null

        // Build teacher context from SubjectList or timetable info if available
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

    private fun buildTeacherContext(): String {
        // Known teacher-subject mappings, locations, and schedules for MBSTU CSE-22
        // These are the faculty members and typical schedules teaching in this batch
        return """
            Known course-teacher associations and schedules (CSE-22 batch):
            - Electronic Devices and Circuits (CSE1201): Faculty from ECE department (e.g., Hadifur Sir / হাদিফুর স্যার)
            - Structured Programming (CSE1203): Faculty from CSE department (typical class: 9:30 AM)
            - Structured Programming Lab (CSE1204): Faculty from CSE department
            - Digital Electronics (CSE1205): Faculty from ECE department (e.g., Hadifur Sir / হাদিফুর স্যার)
            - Digital Electronics Lab (CSE1206): Faculty from ECE department
            - Physics (CSE1207): Faculty from Physics department (typical class: 9:00 AM, Location: Academic Building 2, 1st floor)
            - Statistics (CSE1209): Faculty from Mathematics department
            - Integral Calculus (CSE1211): Faculty from Mathematics department (typical class: 11:30 AM)
            Note: Actual teacher names are assigned by course coordinator. Hadifur Sir (হাদিফুর স্যার) is associated with ECE/Digital Electronics subjects. Do NOT invent other teacher names.
        """.trimIndent()
    }
}
