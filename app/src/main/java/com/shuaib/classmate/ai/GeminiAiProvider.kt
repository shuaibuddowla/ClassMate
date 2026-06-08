package com.shuaib.classmate.ai

import android.util.Log
import com.google.gson.Gson
import com.shuaib.classmate.models.AiNoticeDraft
import com.shuaib.classmate.utils.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GeminiAiProvider(private val client: OkHttpClient, private val gson: Gson) : AiProvider {

    override suspend fun summarizeNotice(input: NoticeSummaryInput): Result<String> = withContext(Dispatchers.IO) {
        val prompt = """
            You are ClassMate AI, an expert academic assistant for MBSTU CSE-22 (Computer Science & Engineering, 2022 batch) students.
            Your task is to transform academic notices (which can be unstructured, verbose, or in Bangla/Benglish) into highly polished, structured, and scannable summaries in English.

            Guidelines:
            1. **Language & Translation**: Always output ONLY in English. Translate any Bangla or colloquial "Benglish" text (e.g., "class hobena", "somoy ektu change", "ct postpone") into formal, precise academic English.
            2. **Structure & Formatting**:
               - Start directly with the key details. Do NOT include any "TL;DR" lines, titles, introductory text, or greetings.
               - **Key Details**: Use clear, high-density, bulleted points (using - ) as appropriate. Bold crucial facts (dates, times, venues, faculty names).
               - **Action Required**: If students need to act, prefix the relevant bullet with "⚠️ **ACTION REQUIRED:**".
               - **Deadline**: If a deadline is mentioned, append a single line: "**📅 Deadline:** [Date/Time] (verbatim)" at the very end.
            3. **High-Density Extraction**:
               - **What**: Exact details of the event (assignment, exam, syllabus, cancellation).
               - **When & Where**: Exact time, date, and venue (e.g. "**11:30 AM**", "**Room 403**", "**CSE Lab 2**"). Preserve these verbatim.
               - **Who**: Faculty/coordinator name and instructions.
            4. **Tone & Constraints**:
               - Professional, concise, and academic. No greetings, introductions, or sign-offs.
               - Do NOT assume or invent any information. If details are missing, omit them.
            5. **Scannable Emojis**:
               - Use emojis as bullets to visually group information:
                 - 📌 for general announcements
                 - 🧪 for exams, class tests, or quizzes
                 - 📝 for assignments or submissions
                 - 🚫 for class cancellations or suspensions
                 - 📍 for location/room numbers
                 - 👤 for teacher/faculty information

            Notice Title: ${input.title}
            Notice Type: ${input.type ?: "General"}
            Subject/Course: ${input.subject ?: "N/A"}
            Target Date: ${input.date ?: "N/A"}
            Notice Body:
            ${input.body}
        """.trimIndent()

        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.15,
                "maxOutputTokens" to 800
            )
        )

        executeRequest(requestBody) { responseText ->
            val parsedText = parseTextResponse(responseText)
            if (parsedText.isBlank()) {
                Result.failure(AiProviderError.InvalidResponse("Empty response from Gemini"))
            } else {
                Result.success(parsedText)
            }
        }
    }

    override suspend fun generateNoticeDraft(input: NoticeDraftInput): Result<AiNoticeDraft> = withContext(Dispatchers.IO) {
        val prompt = """
            You are ClassMate AI for MBSTU (Mawlana Bhashani Science and Technology University) CSE department admins.
            Convert messy admin notes/drafts into polished academic notices for CSE-22 batch students.

            University context:
            - Department: Computer Science & Engineering (CSE)
            - Batch: CSE-22 (2022 intake)
            - Location: Bangladesh
            - Known subjects: ${input.knownSubjects.joinToString()}

            Current local date: ${input.currentDate}
            Timezone: ${input.timezone}
            Supported types: ${input.supportedTypes.joinToString()}
            Teacher context: ${input.teacherContext ?: "N/A"}

            Rules:
            - Translate any Bangla/mixed input to formal English.
            - Polish grammar, spelling, and academic tone.
            - Use bullet points where listing multiple items.
            - Never invent dates, subject names, teacher names, or room numbers.
            - Use missingFields array for fields you cannot determine.
            - Resolve relative dates (e.g., "tomorrow", "next Monday") to yyyy-MM-dd using the currentDate above.
            - Output JSON only. No markdown code blocks.

            JSON schema:
            {
              "type": "GENERAL|CLASS_CANCELLATION|ASSIGNMENT_DEADLINE|CLASS_TEST|EXAM|RESOURCE|VACATION|HOLIDAY|CLASS_SUSPENDED",
              "title": "string (concise, professional)",
              "body": "string (polished, formatted notice body)",
              "subject": "string or null (must match known subjects if applicable)",
              "date": "yyyy-MM-dd or null",
              "deadline": "yyyy-MM-dd or null",
              "startDate": "yyyy-MM-dd or null",
              "endDate": "yyyy-MM-dd or null",
              "priority": "NORMAL|URGENT",
              "isUrgent": boolean,
              "shouldPushNotification": boolean,
              "notificationTitle": "string (max 60 chars)",
              "notificationBody": "string (max 120 chars)",
              "missingFields": ["string"],
              "confidence": number (0.0 to 1.0)
            }

            Messy admin draft:
            ${input.rawInput}
        """.trimIndent()

        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.2,
                "maxOutputTokens" to 4096,
                "responseMimeType" to "application/json"
            )
        )

        executeRequest(requestBody) { responseText ->
            try {
                val jsonText = parseTextResponse(responseText)
                val cleanJson = jsonText.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                if (cleanJson.isBlank()) {
                    Result.failure(AiProviderError.InvalidResponse("Empty response from Gemini"))
                } else {
                    val draft = gson.fromJson(cleanJson, AiNoticeDraft::class.java)
                    Result.success(draft)
                }
            } catch (e: Exception) {
                Result.failure(AiProviderError.InvalidResponse("Failed to parse draft JSON: ${e.message}"))
            }
        }
    }

    private fun <T> executeRequest(bodyMap: Any, parser: (String) -> Result<T>): Result<T> {
        val apiKey = AppConstants.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("TODO")) {
            return Result.failure(AiProviderError.InvalidApiKey("Gemini API key is missing"))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${AppConstants.GEMINI_MODEL}:generateContent"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestJson = gson.toJson(bodyMap)
        val requestBody = requestJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    parser(responseBody)
                } else {
                    val error = mapError(response.code, responseBody)
                    Result.failure(error)
                }
            }
        } catch (e: IOException) {
            Result.failure(AiProviderError.Network(e.message ?: "Network error", null, null))
        } catch (e: Exception) {
            Result.failure(AiProviderError.Unknown(e.message ?: "Unknown error", null, null))
        }
    }

    private fun parseTextResponse(jsonResponse: String): String {
        return try {
            val map = gson.fromJson(jsonResponse, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val firstCandidate = candidates?.get(0) as? Map<*, *>
            val content = firstCandidate?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            val firstPart = parts?.get(0) as? Map<*, *>
            (firstPart?.get("text") as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun mapError(code: Int, body: String?): AiProviderError {
        val message = body ?: "Error code $code"
        return when (code) {
            429 -> AiProviderError.RateLimited(message, code, body)
            403 -> if (message.contains("quota", ignoreCase = true) ||
                       message.contains("limit", ignoreCase = true) ||
                       message.contains("exhausted", ignoreCase = true)) {
                AiProviderError.QuotaExceeded(message, code, body)
            } else {
                AiProviderError.InvalidApiKey(message, code, body)
            }
            in 500..599 -> AiProviderError.Server(message, code, body)
            else -> AiProviderError.Unknown(message, code, body)
        }
    }
}
