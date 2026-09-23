package com.shuaib.classmate.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.shuaib.classmate.network.BackendApiClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitHubUploadResult(
    val assetId: Long,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val mimeType: String
)

class GitHubStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GitHubReleaseStorageClient(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    fun uploadAsset(
        fileUri: Uri,
        assetName: String,
        mimeType: String,
        onProgress: (Int) -> Unit = {}
    ): GitHubUploadResult {
        val encodedName = URLEncoder.encode(assetName, Charsets.UTF_8.name()).replace("+", "%20")
        val url = BackendApiClient.url("/v1/github/upload?name=$encodedName")
        val requestBody = ContentUriRequestBody(context, fileUri, mimeType, onProgress)
        val request = BackendApiClient.authenticated(
            Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", mimeType)
        )

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapGitHubError(response.code, body)
            }
            val json = JSONObject(body)
            Log.d(TAG, "Uploaded GitHub release asset: ${json.optString("name")}")
            return GitHubUploadResult(
                assetId = json.getLong("id"),
                assetName = json.getString("name"),
                downloadUrl = json.getString("browser_download_url"),
                sizeBytes = json.optLong("size", requestBody.contentLength()),
                mimeType = json.optString("content_type", mimeType).ifBlank { mimeType }
            )
        }
    }

    private fun mapGitHubError(code: Int, body: String): GitHubStorageException {
        val githubMessage = runCatching {
            JSONObject(body).optString("message")
        }.getOrNull().orEmpty()

        val message = when (code) {
            401 -> "GitHub token invalid or expired"
            403 -> when {
                githubMessage.contains("Resource not accessible", ignoreCase = true) ->
                    "GitHub token cannot upload to this repository. Check the Worker secret and repository permissions."
                githubMessage.contains("rate limit", ignoreCase = true) ->
                    "GitHub API rate limit reached. Try again later."
                else ->
                    "GitHub upload forbidden. Check token repository access and Contents: Read and write permission."
            }
            404 -> "GitHub release not found. Check owner/repo/tag."
            422 -> "A file with this name may already exist."
            else -> "GitHub upload failed with HTTP $code"
        }
        Log.e(TAG, "$message. Response size=${body.length}")
        return GitHubStorageException(message)
    }

    private class ContentUriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mimeType: String,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {
        private val resolver = context.contentResolver
        private val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        override fun contentType() = mimeType.toMediaTypeOrNull()

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var uploaded = 0L
            resolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    if (size > 0) {
                        onProgress(((uploaded * 100) / size).toInt().coerceIn(0, 100))
                    }
                }
            } ?: throw IOException("Cannot read selected file")
        }
    }

    companion object {
        private const val TAG = "GitHubStorageDebug"
    }
}
