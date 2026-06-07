// com/shuaib/classmate/utils/CloudinaryUploader.kt
package com.shuaib.classmate.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object CloudinaryUploader {

    private const val CLOUD_NAME = "dpz2xodwv"

    /**
     * uploadImage() is kept for profile picture uploads.
     * uploadPdf() has been removed as we now use Telegram Bot API for documents.
     */
    fun uploadImage(
        context: Context,
        fileUri: Uri,
        folder: String,
        onSuccess: (url: String, publicId: String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val IMAGE_PRESET = "classmate_image_uploads"
        Thread {
            try {
                val fileName = getFileName(context, fileUri)
                    ?: "image_${System.currentTimeMillis()}.jpg"

                val inputStream = context.contentResolver
                    .openInputStream(fileUri)
                    ?: throw Exception("Cannot open image")
                val fileBytes = inputStream.readBytes()
                inputStream.close()

                Log.d("CLOUDINARY", "Uploading image: $fileName")

                // Use /image/upload/ for images
                val uploadUrl = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload"

                val boundary = "ClassMate${System.currentTimeMillis()}"
                val connection = java.net.URL(uploadUrl)
                    .openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                val outputStream = connection.outputStream
                val writer = java.io.PrintWriter(
                    java.io.OutputStreamWriter(outputStream, "UTF-8"),
                    true
                )

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"upload_preset\"\r\n")
                writer.append("\r\n")
                writer.append(IMAGE_PRESET)
                writer.append("\r\n")

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"folder\"\r\n")
                writer.append("\r\n")
                writer.append(folder)
                writer.append("\r\n")
                writer.flush()

                writer.append("--$boundary\r\n")
                writer.append(
                    "Content-Disposition: form-data; " +
                    "name=\"file\"; filename=\"$fileName\"\r\n"
                )
                writer.append("Content-Type: image/jpeg\r\n")
                writer.append("\r\n")
                writer.flush()
                outputStream.write(fileBytes)
                outputStream.flush()

                writer.append("\r\n")
                writer.append("--$boundary--\r\n")
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                val responseText = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText()
                        ?: "Unknown error"
                }

                if (responseCode == 200) {
                    val json = org.json.JSONObject(responseText)
                    val secureUrl = json.getString("secure_url")
                    val publicId = json.getString("public_id")
                    Handler(Looper.getMainLooper()).post {
                        onSuccess(secureUrl, publicId)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onFailure("Image upload failed: $responseText")
                    }
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    onFailure("Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(
            uri, null, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(
                android.provider.OpenableColumns.DISPLAY_NAME
            )
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
