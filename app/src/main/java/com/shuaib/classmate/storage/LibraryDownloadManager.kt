package com.shuaib.classmate.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.Timestamp
import com.shuaib.classmate.models.PdfFile
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

object LibraryDownloadManager {
    private const val TAG = "LibraryDownload"
    private const val DOWNLOADS_DIR = "library_downloads"

    fun isDownloaded(context: Context, fileId: String): Boolean {
        val file = File(getDownloadsDir(context), "$fileId.pdf")
        return file.exists() && file.length() > 0
    }

    fun getDownloadedFiles(context: Context): List<PdfFile> {
        val dir = getDownloadsDir(context)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val timestampLong = json.optLong("timestamp", -1)
                val timestamp = if (timestampLong != -1L) Timestamp(Date(timestampLong)) else null
                val createdAtLong = json.optLong("createdAt", -1)
                val createdAt = if (createdAtLong != -1L) Timestamp(Date(createdAtLong)) else null
                
                PdfFile(
                    id = json.getString("id"),
                    title = json.optString("title", ""),
                    subject = json.optString("subject", ""),
                    description = json.optString("description", ""),
                    uploadedBy = json.optString("uploadedBy", ""),
                    downloadUrl = json.optString("downloadUrl", ""),
                    driveUrl = json.optString("driveUrl", ""),
                    telegramUrl = json.optString("telegramUrl", ""),
                    fileId = json.optString("fileId", ""),
                    courseCode = json.optString("courseCode", ""),
                    courseType = json.optString("courseType", ""),
                    fileType = json.optString("fileType", "pdf"),
                    mimeType = json.optString("mimeType", "application/pdf"),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                    provider = json.optString("provider", ""),
                    githubAssetId = json.optLong("githubAssetId", 0L),
                    githubAssetName = json.optString("githubAssetName", ""),
                    downloadCount = json.optLong("downloadCount", 0L),
                    timestamp = timestamp,
                    createdAt = createdAt
                )
            }.getOrNull()
        }.sortedByDescending { it.timestamp ?: it.createdAt }
    }

    fun downloadFile(
        context: Context,
        pdfFile: PdfFile,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val url = pdfFile.downloadUrl.ifBlank { pdfFile.driveUrl.ifBlank { pdfFile.telegramUrl } }
        if (url.isBlank()) {
            onFailure(IllegalArgumentException("No download link available"))
            return
        }

        Log.d(TAG, "Starting download for ${pdfFile.title} from url: $url")
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Download failed", e)
                onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure(IOException("Unexpected response code: ${response.code}"))
                    return
                }

                val body = response.body
                if (body == null) {
                    onFailure(IOException("Empty response body"))
                    return
                }

                try {
                    val dir = getDownloadsDir(context)
                    if (!dir.exists()) dir.mkdirs()

                    val file = File(dir, "${pdfFile.id}.pdf")
                    val outputStream = FileOutputStream(file)
                    val inputStream = body.byteStream()
                    val totalBytes = body.contentLength()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((totalRead * 100) / totalBytes).toInt()
                            onProgress(progress)
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    saveMetadata(context, pdfFile)
                    Log.d(TAG, "Download and metadata save completed for ${pdfFile.id}")
                    onSuccess()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing file to storage", e)
                    onFailure(e)
                }
            }
        })
    }

    fun deleteDownload(context: Context, fileId: String) {
        val dir = getDownloadsDir(context)
        val file = File(dir, "$fileId.pdf")
        val metaFile = File(dir, "$fileId.json")
        if (file.exists()) file.delete()
        if (metaFile.exists()) metaFile.delete()
        Log.d(TAG, "Deleted offline cache for file: $fileId")
    }

    fun openLocalFile(context: Context, pdfFile: PdfFile) {
        val dir = getDownloadsDir(context)
        val file = File(dir, "${pdfFile.id}.pdf")
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "Offline copy not found or empty", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening local PDF", e)
            Toast.makeText(context, "No PDF viewer app found", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveMetadata(context: Context, pdf: PdfFile) {
        val dir = getDownloadsDir(context)
        if (!dir.exists()) dir.mkdirs()
        val metaFile = File(dir, "${pdf.id}.json")
        val json = JSONObject().apply {
            put("id", pdf.id)
            put("title", pdf.title)
            put("subject", pdf.subject)
            put("description", pdf.description)
            put("uploadedBy", pdf.uploadedBy)
            put("downloadUrl", pdf.downloadUrl)
            put("driveUrl", pdf.driveUrl)
            put("telegramUrl", pdf.telegramUrl)
            put("fileId", pdf.fileId)
            put("courseCode", pdf.courseCode)
            put("courseType", pdf.courseType)
            put("fileType", pdf.fileType)
            put("mimeType", pdf.mimeType)
            put("sizeBytes", pdf.sizeBytes)
            put("provider", pdf.provider)
            put("githubAssetId", pdf.githubAssetId)
            put("githubAssetName", pdf.githubAssetName)
            put("downloadCount", pdf.downloadCount)
            pdf.timestamp?.let { put("timestamp", it.toDate().time) }
            pdf.createdAt?.let { put("createdAt", it.toDate().time) }
        }
        metaFile.writeText(json.toString())
    }

    private fun getDownloadsDir(context: Context): File {
        return File(context.filesDir, DOWNLOADS_DIR)
    }
}
