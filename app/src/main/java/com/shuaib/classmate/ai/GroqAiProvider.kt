package com.shuaib.classmate.ai

import android.util.Log
import com.google.gson.Gson
import com.shuaib.classmate.models.AiNoticeDraft
import com.shuaib.classmate.utils.AppConstants
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.ClassMateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GroqAiProvider(private val client: OkHttpClient, private val gson: Gson) : AiProvider {

    override suspend fun summarizeNotice(input: NoticeSummaryInput): Result<String> = withContext(Dispatchers.IO) {
        val isMultiNotice = input.subject == "Notice Feed Summary" || input.title == "Today's Updates"
        
        val systemInstruction = if (isMultiNotice) {
            """
                You are ClassMate AI, an expert academic assistant for MBSTU CSE-22 (Computer Science & Engineering, 2022 batch) students.
                Your task is to transform TODAY'S ACADEMIC UPDATES (which is a concatenated list of multiple notices, potentially in Bangla, English, or Benglish) into a single, cohesive, highly polished, and easily scannable daily briefing in English.

                Guidelines:
                1. **Language & Translation**: Always output ONLY in English. Translate any Bangla or colloquial "Benglish" notices (e.g., "class hobena" -> Class Cancelled, "ct postpone" -> Class Test Postponed) into formal, precise academic English.
                2. **Structure & Formatting**:
                   - Start directly with the summary. Do NOT include any "TL;DR" lines, titles, introductory text, greetings, or meta-commentary.
                   - For each distinct notice in the updates:
                     - Use a separate bullet point starting with a relevant, descriptive emoji.
                     - Bold crucial facts like dates, times, venues, and faculty names.
                     - If a notice requires action or has a deadline, clearly highlight it (e.g., "⚠️ **ACTION REQUIRED:** [task] by **[Deadline]**").
                3. **Bullet Point Categories**:
                   - 🚫 for class cancellations or suspensions
                   - 🧪 for exams, class tests (CTs), or quizzes
                   - 📝 for assignments or homework submissions
                   - 📌 for general announcements, registration, or fees
                   - 📍 for locations, rooms, or lab venues
                   - 👤 for faculty or teacher announcements
                4. **High Density & Conciseness**:
                   - Keep the briefing extremely concise, dense, and focused on actionable info. Do NOT write long paragraphs; write short, punchy bullet points.
                   - Do not invent or assume details. If a notice is vague, summarize only what is explicitly written.
            """.trimIndent()
        } else {
            """
                You are ClassMate AI, an expert academic assistant for MBSTU CSE-22 (Computer Science & Engineering, 2022 batch) students.
                Your task is to transform academic notices (which can be unstructured, verbose, or in Bangla/Benglish) into highly polished, structured, and scannable summaries in English.

                Guidelines:
                1. **Language & Translation**: Always output ONLY in English. Translate any Bangla or colloquial "Benglish" text (e.g., "class hobena" -> Class Cancelled, "somoy ektu change" -> Time rescheduled, "ct postpone" -> Class Test Postponed) into formal, precise academic English.
                2. **Common Abbreviation Mapping**:
                   - Translate "CT" or "ct" or "quiz" to "Class Test".
                   - Translate "mid" or "sem" to "Semester Examination".
                   - Translate "cr" or "CR" to "Class Representative".
                   - Translate "hw" or "task" to "Assignment".
                   - Translate common Bengali day names (e.g., "Sombar" to "Monday", "Mongolbar" to "Tuesday") to English.
                3. **Structure & Formatting**:
                   - Start directly with the key details. Do NOT include any "TL;DR" lines, titles, introductory text, or greetings.
                   - **Key Details**: Use clear, high-density, bulleted points (using - ) as appropriate. Bold crucial facts (dates, times, venues, faculty names).
                   - **Action Required**: If students need to act, prefix the relevant bullet with "⚠️ **ACTION REQUIRED:**".
                   - **Deadline**: If a deadline is mentioned, append a single line: "**📅 Deadline:** [Date/Time] (verbatim)" at the very end.
                4. **High-Density Extraction**:
                   - **What**: Exact details of the event (assignment, exam, syllabus, cancellation).
                   - **When & Where**: Exact time, date, and venue (e.g. "**11:30 AM**", "**Room 403**", "**CSE Lab 2**"). Preserve these verbatim.
                   - **Who**: Faculty/coordinator name and instructions.
                5. **Tone & Constraints**:
                   - Professional, concise, and academic. No greetings, introductions, or sign-offs.
                   - Do NOT assume or invent any information. If details are missing, omit them.
                6. **Scannable Emojis**:
                   - Use emojis as bullets to visually group information:
                     - 📌 for general announcements
                     - 🧪 for exams, class tests, or quizzes
                     - 📝 for assignments or submissions
                     - 🚫 for class cancellations or suspensions
                     - 📍 for location/room numbers
                     - 👤 for teacher/faculty information
            """.trimIndent()
        }

        val userPrompt = if (isMultiNotice) {
            """
                Here is the list of today's updates:
                ${input.body}
            """.trimIndent()
        } else {
            """
                Notice Title: ${input.title}
                Notice Type: ${input.type ?: "General"}
                Subject/Course: ${input.subject ?: "N/A"}
                Target Date: ${input.date ?: "N/A"}
                Notice Body:
                ${input.body}
            """.trimIndent()
        }

        val requestBody = mapOf(
            "model" to getSelectedModel(),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemInstruction),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.15,
            "max_tokens" to 800
        )

        executeRequest(requestBody) { responseText ->
            try {
                val parsedText = parseTextResponse(responseText)
                val cleanedText = cleanMarkdownResponse(parsedText)
                if (cleanedText.isBlank()) {
                    Result.failure(AiProviderError.InvalidResponse("Empty response from Groq"))
                } else {
                    Result.success(cleanedText)
                }
            } catch (e: Exception) {
                Result.failure(AiProviderError.InvalidResponse(e.message ?: "Failed to parse response"))
            }
        }
    }

    override suspend fun generateNoticeDraft(input: NoticeDraftInput): Result<AiNoticeDraft> = withContext(Dispatchers.IO) {
        val systemInstruction = """
            You are ClassMate AI for MBSTU (Mawlana Bhashani Science and Technology University) CSE department admins.
            Convert messy admin notes/drafts into polished academic notices for CSE-22 batch students.

            University context:
            - Department: Computer Science & Engineering (CSE)
            - Batch: CSE-22 (2022 intake)
            - Location: Bangladesh
            - Known subjects: ${input.knownSubjects.joinToString()}

            Rules & Abbreviation Mappings:
            - Translate all Bangla/colloquial "Benglish" (e.g., "class hobena", "quiz postpone", "somoy change") into formal academic English.
            - Map local abbreviations:
              - "CT" or "ct" or "quiz" -> "Class Test"
              - "mid" or "sem" -> "Semester Examination"
              - "cr" or "CR" -> "Class Representative"
              - "hw" or "task" -> "Assignment"
              - "sombar" -> "Monday", "mangalbar" -> "Tuesday", "budhbar" -> "Wednesday", "brihoshpotibar" -> "Thursday", "shukrobar" -> "Friday", "shonibar" -> "Saturday", "robibar" -> "Sunday".
            - Resolve relative dates (e.g., "tomorrow", "next Monday", "sombar") to yyyy-MM-dd using the currentDate.
            - Format the "body" text beautifully using Markdown. Use bold (e.g., **10:30 AM**, **Room 403**, **Dr. X**) for key parameters, and clean bullet points.
            - Keep the notice "body" extremely concise, high-density, and focused purely on the main context and key details (cancellations, timings, deadlines, tasks). Avoid verbose fluff or excessive elaboration so students can grasp the core message immediately. Do not write long paragraphs; use concise bullet points instead.
            - Never invent dates, subjects, teacher names, or room numbers. Leave fields as null or use the missingFields array.
            - Output JSON only. No markdown wrapping.

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
        """.trimIndent()

        val userPrompt = """
            Current local date: ${input.currentDate}
            Timezone: ${input.timezone}
            Supported types: ${input.supportedTypes.joinToString()}
            Teacher context: ${input.teacherContext ?: "N/A"}

            Messy admin draft:
            ${input.rawInput}
        """.trimIndent()

        val requestBody = mapOf(
            "model" to getSelectedModel(),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemInstruction),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.2,
            "max_tokens" to 4096,
            "response_format" to mapOf("type" to "json_object")
        )

        executeRequest(requestBody) { responseText ->
            try {
                val content = parseTextResponse(responseText)
                val cleanedText = cleanMarkdownResponse(content)
                if (cleanedText.isBlank()) {
                    Result.failure(AiProviderError.InvalidResponse("Empty response from Groq"))
                } else {
                    val draft = gson.fromJson(cleanedText, AiNoticeDraft::class.java)
                    Result.success(draft)
                }
            } catch (e: Exception) {
                Result.failure(AiProviderError.InvalidResponse("Failed to parse draft JSON: ${e.message}"))
            }
        }
    }

    override suspend fun chatWithAi(input: AiChatInput): Result<String> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to input.systemPrompt))
        input.messages.forEach { msg ->
            val apiRole = if (msg.role == "user") "user" else "assistant"
            messages.add(mapOf("role" to apiRole, "content" to msg.content))
        }

        val requestBody = mapOf(
            "model" to getSelectedModel(),
            "messages" to messages,
            "temperature" to 0.4,
            "max_tokens" to 2048
        )

        executeRequest(requestBody) { responseText ->
            try {
                val parsedText = parseTextResponse(responseText)
                val cleanedText = cleanMarkdownResponse(parsedText)
                if (cleanedText.isBlank()) {
                    Result.failure(AiProviderError.InvalidResponse("Empty response from Groq"))
                } else {
                    Result.success(cleanedText)
                }
            } catch (e: Exception) {
                Result.failure(AiProviderError.InvalidResponse(e.message ?: "Failed to parse response"))
            }
        }
    }


    private fun cleanMarkdownResponse(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            val lines = cleaned.split("\n")
            val startIdx = if (lines.isNotEmpty() && lines.first().startsWith("```")) 1 else 0
            val endIdx = if (lines.size > startIdx && lines.last().startsWith("```")) lines.size - 1 else lines.size
            cleaned = lines.subList(startIdx, endIdx).joinToString("\n").trim()
        }
        return cleaned
    }

    private fun parseTextResponse(jsonResponse: String): String {
        val map = gson.fromJson(jsonResponse, Map::class.java) ?: throw Exception("Invalid JSON response")
        
        // Check for error field in JSON if any
        val errorMap = map["error"] as? Map<*, *>
        if (errorMap != null) {
            val errorMsg = errorMap["message"] as? String
            throw Exception(errorMsg ?: "Groq API returned an error")
        }

        val choices = map["choices"] as? List<*>
        if (choices.isNullOrEmpty()) {
            throw Exception("No choices returned from Groq")
        }
        val firstChoice = choices[0] as? Map<*, *>
        val message = firstChoice?.get("message") as? Map<*, *>
        val content = message?.get("content") as? String
        
        val finishReason = firstChoice?.get("finish_reason") as? String
        if (finishReason != null && finishReason != "stop" && finishReason != "length") {
            throw Exception("Groq generation finished with unexpected reason: $finishReason")
        }

        return content ?: throw Exception("Empty text content from Groq")
    }

    private fun <T> executeRequest(bodyMap: Any, parser: (String) -> Result<T>): Result<T> {
        val apiKey = AppConstants.GROQ_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("TODO")) {
            return Result.failure(AiProviderError.InvalidApiKey("Groq API key is missing"))
        }

        val url = "https://api.groq.com/openai/v1/chat/completions"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestJson = gson.toJson(bodyMap)
        val requestBody = requestJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
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



    private fun mapError(code: Int, body: String?): AiProviderError {
        val message = body ?: "Error code $code"
        return when (code) {
            429 -> AiProviderError.RateLimited(message, code, body)
            403 -> AiProviderError.QuotaExceeded(message, code, body)
            in 500..599 -> AiProviderError.Server(message, code, body)
            else -> AiProviderError.Unknown(message, code, body)
        }
    }

    private fun getSelectedModel(): String {
        return try {
            AppPreferences(ClassMateApp.instance).getGroqModel()
        } catch (_: Throwable) {
            AppConstants.GROQ_MODEL
        }
    }
}
