package com.shuaib.classmate.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.shuaib.classmate.BuildConfig
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
    private val context: Context,
    private val token: String = BuildConfig.GITHUB_LIBRARY_TOKEN,
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val releaseTag: String = BuildConfig.GITHUB_RELEASE_TAG
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
        validateConfig()
        val release = getReleaseByTag()
        return uploadReleaseAsset(release, fileUri, assetName, mimeType, onProgress)
    }

    private fun validateConfig() {
        if (token.isBlank() || owner.isBlank() || repo.isBlank() || releaseTag.isBlank()) {
            throw GitHubStorageException(
                "GitHub storage is not configured. Check GITHUB_LIBRARY_TOKEN, GITHUB_OWNER, GITHUB_REPO, and GITHUB_RELEASE_TAG in local.properties."
            )
        }
    }

    private fun getReleaseByTag(): ReleaseInfo {
        val url = "https://api.github.com/repos/$owner/$repo/releases/tags/$releaseTag"
        val request = baseRequest(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mapGitHubError(response.code, body)
            }
            val json = JSONObject(body)
            return ReleaseInfo(
                id = json.getLong("id"),
                uploadUrl = json.getString("upload_url").substringBefore("{")
            )
        }
    }

    private fun uploadReleaseAsset(
        release: ReleaseInfo,
        fileUri: Uri,
        assetName: String,
        mimeType: String,
        onProgress: (Int) -> Unit
    ): GitHubUploadResult {
        val encodedName = URLEncoder.encode(assetName, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "${release.uploadUrl}?name=$encodedName"
        val requestBody = ContentUriRequestBody(context, fileUri, mimeType, onProgress)
        val request = baseRequest(url)
            .post(requestBody)
            .header("Content-Type", mimeType)
            .build()

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

    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ClassMate-Android")
    }

    private fun mapGitHubError(code: Int, body: String): GitHubStorageException {
        val githubMessage = runCatching {
            JSONObject(body).optString("message")
        }.getOrNull().orEmpty()

        val message = when (code) {
            401 -> "GitHub token invalid or expired"
            403 -> when {
                githubMessage.contains("Resource not accessible", ignoreCase = true) ->
                    "GitHub token cannot upload to this repository. Regenerate or approve a fine-grained token for $owner/$repo with Contents: Read and write."
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

    private data class ReleaseInfo(val id: Long, val uploadUrl: String)

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
