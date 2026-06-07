// com/shuaib/classmate/utils/TelegramUploader.kt
package com.shuaib.classmate.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object TelegramUploader {

    private const val TAG = "TELEGRAM_UPLOAD"
    private val BOT_TOKEN: String
        get() = AppConstants.TELEGRAM_BOT_TOKEN
    private val CHANNEL_ID: String
        get() = AppConstants.TELEGRAM_CHANNEL_ID

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun uploadPdf(
        context: Context,
        fileUri: Uri,
        title: String,
        subject: String,
        uploadedBy: String,
        onProgress: (String) -> Unit,
        onSuccess: (telegramUrl: String, fileId: String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val mimeType = context.contentResolver.getType(fileUri) ?: "application/octet-stream"
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "doc"
                
                val fileName = getFileName(context, fileUri)
                    ?: "document_${System.currentTimeMillis()}.$extension"

                Log.d(TAG, "Starting upload: $fileName, MIME: $mimeType")

                mainHandler.post { onProgress("Reading file...") }

                val inputStream = context.contentResolver.openInputStream(fileUri)
                    ?: throw Exception("Cannot read file")
                val fileBytes = inputStream.readBytes()
                inputStream.close()

                Log.d(TAG, "File size: ${fileBytes.size} bytes")

                mainHandler.post { onProgress("Uploading to Telegram...") }

                // Professional caption formatting
                val sanitizedSubjectTag = subject.replace(" ", "").replace("&", "").replace("-", "")
                val caption = "<b>New Academic Resource Posted</b> ✨\n\n" +
                        "📝 <b>Title:</b> $title\n" +
                        "📚 <b>Subject:</b> $subject\n" +
                        "━━━━━━━━━━━━━━━━━━━━━\n" +
                        "📥 <i>Available in the ClassMate App Library</i>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "#ClassMate #StudyMaterial"

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", CHANNEL_ID)
                    .addFormDataPart("caption", caption)
                    .addFormDataPart("parse_mode", "HTML")
                    .addFormDataPart(
                        "document",
                        fileName,
                        fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$BOT_TOKEN/sendDocument")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "Response code: ${response.code}")
                    Log.d(TAG, "Response: $responseBody")

                    if (!response.isSuccessful) {
                        mainHandler.post {
                            onFailure("Telegram error (${response.code}): $responseBody")
                        }
                        return@Thread
                    }

                    val json = JSONObject(responseBody)
                    if (!json.getBoolean("ok")) {
                        mainHandler.post {
                            onFailure("Telegram API error: ${json.getString("description")}")
                        }
                        return@Thread
                    }

                    val result = json.getJSONObject("result")
                    val messageId = result.getInt("message_id")
                    val chat = result.getJSONObject("chat")
                    val chatUsername = chat.optString("username", "")
                    
                    val documentObj = result.optJSONObject("document") 
                        ?: result.optJSONObject("video")
                        ?: result.optJSONObject("audio")
                    
                    val fileId = documentObj?.optString("file_id", "") ?: ""

                    val telegramUrl = if (chatUsername.isNotEmpty()) {
                        "https://t.me/$chatUsername/$messageId"
                    } else {
                        val channelIdForUrl = CHANNEL_ID.removePrefix("-100")
                        "https://t.me/c/$channelIdForUrl/$messageId"
                    }

                    mainHandler.post {
                        onSuccess(telegramUrl, fileId)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}", e)
                mainHandler.post {
                    onFailure("Upload failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) {
                    name = cursor.getString(col)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting filename", e)
        }
        return name
    }
}
