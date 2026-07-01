package com.shuaib.classmate.storage

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.firebase.Timestamp
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.activities.OfflinePdfViewerActivity
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

        val isTelegram = pdfFile.provider == "telegram" ||
                (pdfFile.fileId.isNotBlank() && (url.contains("t.me", ignoreCase = true) || pdfFile.telegramUrl.contains("t.me", ignoreCase = true)))

        if (isTelegram && pdfFile.fileId.isNotBlank() && com.shuaib.classmate.utils.AppConstants.TELEGRAM_BOT_TOKEN.isNotBlank()) {
            val botToken = com.shuaib.classmate.utils.AppConstants.TELEGRAM_BOT_TOKEN
            val getFileUrl = "https://api.telegram.org/bot$botToken/getFile?file_id=${pdfFile.fileId}"
            val getFileRequest = Request.Builder()
                .url(getFileUrl)
                .build()

            client.newCall(getFileRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Failed to resolve Telegram file path, attempting direct URL: $url", e)
                    performDownload(client, context, pdfFile, url, onProgress, onSuccess, onFailure)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        var resolvedUrl: String? = null
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string()
                            if (!bodyStr.isNullOrBlank()) {
                                try {
                                    val json = JSONObject(bodyStr)
                                    if (json.optBoolean("ok", false)) {
                                        val result = json.optJSONObject("result")
                                        val filePath = result?.optString("file_path", "") ?: ""
                                        if (filePath.isNotBlank()) {
                                            resolvedUrl = "https://api.telegram.org/file/bot$botToken/$filePath"
                                            Log.d(TAG, "Resolved Telegram download URL: $resolvedUrl")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing Telegram getFile response", e)
                                }
                            }
                        }

                        if (resolvedUrl != null) {
                            performDownload(client, context, pdfFile, resolvedUrl!!, onProgress, onSuccess, onFailure)
                        } else {
                            Log.w(TAG, "Telegram getFile response unsuccessful or invalid. Attempting direct URL: $url")
                            performDownload(client, context, pdfFile, url, onProgress, onSuccess, onFailure)
                        }
                    }
                }
            })
        } else {
            performDownload(client, context, pdfFile, url, onProgress, onSuccess, onFailure)
        }
    }

    private fun performDownload(
        client: OkHttpClient,
        context: Context,
        pdfFile: PdfFile,
        url: String,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val request = buildDownloadRequest(pdfFile, url)
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Download failed", e)
                onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onFailure(IOException("Download failed with HTTP ${it.code}"))
                        return
                    }

                    val body = it.body
                    if (body == null) {
                        onFailure(IOException("Empty response body"))
                        return
                    }

                    try {
                        val dir = getDownloadsDir(context)
                        if (!dir.exists() && !dir.mkdirs()) {
                            onFailure(IOException("Unable to prepare offline cache"))
                            return
                        }

                        val file = File(dir, "${pdfFile.id}.pdf")
                        val tempFile = File(dir, "${pdfFile.id}.download")
                        if (tempFile.exists()) tempFile.delete()

                        body.byteStream().use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                val totalBytes = body.contentLength()
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L

                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    if (totalBytes > 0) {
                                        val progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                        onProgress(progress)
                                    }
                                }
                                outputStream.flush()
                            }
                        }

                        if (tempFile.length() == 0L) {
                            tempFile.delete()
                            onFailure(IOException("Downloaded file was empty"))
                            return
                        }

                        if (file.exists()) file.delete()
                        if (!tempFile.renameTo(file)) {
                            tempFile.copyTo(file, overwrite = true)
                            tempFile.delete()
                        }

                        saveMetadata(context, pdfFile, file.length())
                        Log.d(TAG, "Download and metadata save completed for ${pdfFile.id}")
                        onProgress(100)
                        onSuccess()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing file to storage", e)
                        File(getDownloadsDir(context), "${pdfFile.id}.download").delete()
                        onFailure(e)
                    }
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
            val intent = Intent(context, OfflinePdfViewerActivity::class.java).apply {
                putExtra(OfflinePdfViewerActivity.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(OfflinePdfViewerActivity.EXTRA_TITLE, pdfFile.title.ifBlank { "Offline PDF" })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening local PDF", e)
            Toast.makeText(context, "Unable to open offline PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildDownloadRequest(pdfFile: PdfFile, url: String): Request {
        val useGitHubAssetApi = isGitHubReleaseAsset(pdfFile) &&
            BuildConfig.GITHUB_OWNER.isNotBlank() &&
            BuildConfig.GITHUB_REPO.isNotBlank()
        val builder = Request.Builder()
            .url(if (useGitHubAssetApi) githubAssetApiUrl(pdfFile) else directDownloadUrl(url, pdfFile.fileId))
            .header("User-Agent", "ClassMate-Android")

        if (useGitHubAssetApi) {
            builder.header("Accept", "application/octet-stream")
        }

        if (useGitHubAssetApi && BuildConfig.GITHUB_LIBRARY_TOKEN.isNotBlank()) {
            builder
                .header("Authorization", "Bearer ${BuildConfig.GITHUB_LIBRARY_TOKEN}")
                .header("X-GitHub-Api-Version", "2022-11-28")
        }

        return builder.build()
    }

    private fun directDownloadUrl(url: String, storedFileId: String): String {
        val driveFileId = storedFileId.ifBlank { extractDriveFileId(url) }
        return if (driveFileId.isNotBlank() && url.contains("drive.google.com", ignoreCase = true)) {
            "https://drive.google.com/uc?export=download&id=$driveFileId"
        } else {
            url
        }
    }

    private fun extractDriveFileId(url: String): String {
        val filePathMatch = Regex("/file/d/([^/]+)").find(url)?.groupValues?.getOrNull(1)
        if (!filePathMatch.isNullOrBlank()) return filePathMatch

        val queryMatch = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        return queryMatch.orEmpty()
    }

    private fun isGitHubReleaseAsset(pdfFile: PdfFile): Boolean {
        return pdfFile.provider == "github_releases" && pdfFile.githubAssetId > 0L
    }

    private fun githubAssetApiUrl(pdfFile: PdfFile): String {
        return "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/assets/${pdfFile.githubAssetId}"
    }

    private fun saveMetadata(context: Context, pdf: PdfFile, cachedSizeBytes: Long) {
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
            put("sizeBytes", cachedSizeBytes.takeIf { it > 0L } ?: pdf.sizeBytes)
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
