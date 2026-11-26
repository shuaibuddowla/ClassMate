package com.shuaib.classmate.data.remote.supabase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.shuaib.classmate.BuildConfig
import java.net.URLEncoder
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Google ID-token exchange and authenticated access to the classmate schema. */
object ClassMateAuthApi {
    private val http = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val uploadHttp = OkHttpClient.Builder().writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.CLASSMATE_URL.trimEnd('/')
    private val key = BuildConfig.CLASSMATE_PUBLISHABLE_KEY
    private var appContext: Context? = null
    private var sessionStore: ClassMateSessionStore? = null

    @Volatile var accessToken: String? = null
        private set
    @Volatile private var refreshToken: String? = null
    @Volatile private var expiresAtMs: Long = 0
    private val refreshMutex = Mutex()

    val configured: Boolean get() = BuildConfig.CLASSMATE_AUTH_ENABLED &&
        base.startsWith("https://") && key.isNotBlank()

    fun attach(context: Context) {
        appContext = context.applicationContext
        sessionStore = ClassMateSessionStore(context, BuildConfig.CLASSMATE_ENV)
    }

    fun hasSavedSession(): Boolean = sessionStore?.load() != null

    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        // Startup restoration and API refresh must not rotate the same token concurrently.
        refreshMutex.withLock {
            if (accessToken != null && System.currentTimeMillis() < expiresAtMs - 60_000) return@withLock true
            val stored = refreshToken ?: sessionStore?.load() ?: return@withLock false
            runCatching { refreshSession(stored) }.isSuccess
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): JSONObject {
        val body = JSONObject().put("provider", "google").put("id_token", idToken)
        val result = request("POST", "/auth/v1/token?grant_type=id_token", body)
        val response = JSONObject(result)
        saveSession(response)
        return response
    }

    private fun saveSession(response: JSONObject) {
        val token = response.optString("access_token")
        if (token.isBlank()) throw IOException("Supabase returned no access token")
        val rotatedRefresh = response.optString("refresh_token").ifBlank { null }
        // Persist before advertising success; silently losing a rotated token logs users out later.
        sessionStore?.save(rotatedRefresh)
        accessToken = token
        refreshToken = rotatedRefresh
        expiresAtMs = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000
    }

    private fun refreshSession(token: String) {
        val request = Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token")
            .header("apikey", key)
            .post(JSONObject().put("refresh_token", token).toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 401) signOut()
                throw IOException("Session refresh failed: HTTP ${response.code}")
            }
            saveSession(JSONObject(response.body?.string().orEmpty()))
        }
    }

    fun getValidAccessToken(): String? {
        if (sessionStore == null) {
            val ctx = appContext ?: com.shuaib.classmate.ClassMateApp.instance
            if (ctx != null) {
                attach(ctx)
            }
        }
        if (accessToken != null && System.currentTimeMillis() < expiresAtMs - 60_000) {
            return accessToken
        }
        val stored = refreshToken ?: sessionStore?.load()
        if (stored != null) {
            try {
                refreshSession(stored)
                return accessToken
            } catch (e: Exception) {
                android.util.Log.e("ClassMateAuth", "Failed to refresh token: ${e.message}")
            }
        }
        return accessToken
    }

    private suspend fun refreshIfNeeded() {
        if (accessToken != null && System.currentTimeMillis() < expiresAtMs - 60_000) return
        refreshMutex.withLock {
            if (accessToken != null && System.currentTimeMillis() < expiresAtMs - 60_000) return@withLock
            val token = refreshToken ?: sessionStore?.load() ?: throw IOException("Session expired; sign in again")
            refreshSession(token)
        }
    }

    suspend fun initializeProfile(): JSONObject = rpc("create_or_initialize_profile", JSONObject())

    suspend fun publicRpc(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = "$base/rest/v1/rpc/$name"
        val request = Request.Builder()
            .url(url)
            .header("apikey", key)
            .header("Accept-Profile", "classmate")
            .header("Content-Profile", "classmate")
            .post(args.toString().toRequestBody(jsonType))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val result = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) return@withContext JSONObject()
                if (result.startsWith("{")) JSONObject(result) else JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    suspend fun rpc(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val text = request("POST", "/rest/v1/rpc/$name", args, classmate = true).trim()
        if (text.isEmpty()) return@withContext JSONObject()
        if (text.startsWith("{")) return@withContext JSONObject(text)
        if (text.startsWith("[")) return@withContext JSONObject().put("data", JSONArray(text))
        JSONObject().put("result", text.removeSurrounding("\""))
    }

    suspend fun rpcVoid(name: String, args: JSONObject) {
        request("POST", "/rest/v1/rpc/$name", args, classmate = true)
    }

    suspend fun rpcText(name: String, args: JSONObject): String =
        request("POST", "/rest/v1/rpc/$name", args, classmate = true)

    /** AI credentials and all database write authorization stay server-side. */
    suspend fun ai(args: JSONObject): JSONObject =
        JSONObject(request("POST", "/functions/v1/classmate-ai", args, longRunning = true))

    suspend fun registerDeviceToken(token: String) {
        request("POST", "/rest/v1/rpc/register_device_token",
            JSONObject().put("target_token", token).put("target_version", BuildConfig.VERSION_CODE), classmate = true)
    }

    suspend fun unregisterDeviceToken(token: String) {
        val encoded = URLEncoder.encode(token, "UTF-8")
        request("DELETE", "/rest/v1/device_tokens?fcm_token=eq.$encoded", classmate = true)
    }

    suspend fun rows(table: String, query: String): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(request("GET", "/rest/v1/$table?$query", classmate = true))
    }

    suspend fun upsert(table: String, row: JSONObject) {
        request("POST", "/rest/v1/$table", row, classmate = true,
            prefer = "resolution=merge-duplicates")
    }

    suspend fun insert(table: String, row: JSONObject) {
        request("POST", "/rest/v1/$table", row, classmate = true)
    }

    suspend fun delete(table: String, query: String) {
        request("DELETE", "/rest/v1/$table?$query", classmate = true)
    }

    suspend fun signedResource(resourceId: String): String =
        JSONObject(request("POST", "/functions/v1/signed-classmate-resource",
            JSONObject().put("resource_id", resourceId))).getString("url")

    suspend fun edgePost(functionName: String, body: JSONObject = JSONObject()): String = withContext(Dispatchers.IO) {
        request("POST", "/functions/v1/$functionName", body)
    }

    suspend fun deleteResource(resourceId: String) {
        val body = JSONObject().put("action", "delete").put("resource_id", resourceId)
        val path = "/functions/v1/upload-classmate-resource"
        val response = try {
            request("POST", path, body)
        } catch (first: IOException) {
            if (first.message?.startsWith("400:") == true ||
                first.message?.startsWith("401:") == true ||
                first.message?.startsWith("403:") == true) throw first
            delay(250)
            request("POST", path, body)
        }
        if (JSONObject(response).optString("status") != "deleted")
            throw IOException("Permanent deletion was not confirmed")
    }

    suspend fun deleteGlobalCourse(courseId: String): JSONObject {
        val args = JSONObject().put("target_course",courseId)
        return try { rpc("delete_global_course",args) }
        catch (first: IOException) {
            if (first.message?.startsWith("400:") == true || first.message?.startsWith("401:") == true || first.message?.startsWith("403:") == true) throw first
            // The RPC is idempotent: a lost response after deletion is safe to retry.
            delay(250)
            rpc("delete_global_course",args)
        }
    }

    suspend fun uploadResource(
        resolver: ContentResolver?,
        uri: Uri?,
        localFile: java.io.File? = null,
        batchId: String,
        semesterCourseId: String?,
        title: String,
        mimeType: String,
        size: Long,
        category: String,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        require(size in 1..500_000_000L) { "File must be between 1 byte and 500 MB" }
        val start = JSONObject(request("POST", "/functions/v1/upload-classmate-resource",
            JSONObject().put("action", "start").put("batch_id", batchId)
                .put("semester_course_id", semesterCourseId ?: JSONObject.NULL)
                .put("title", title).put("file_type", mimeType.substringAfter('/'))
                .put("mime_type", mimeType).put("size_bytes", size).put("category", category)))
        val uploadUrl = start.getString("upload_url")
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = when {
                    localFile != null && localFile.exists() -> java.io.FileInputStream(localFile)
                    uri != null && resolver != null -> resolver.openInputStream(uri)
                    else -> null
                } ?: throw IOException("Selected file unavailable")
                input.use { inStream ->
                    val buffer = ByteArray(32 * 1024)
                    var uploaded = 0L
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        uploaded += read
                        onProgress?.invoke(uploaded, size)
                    }
                    sink.flush()
                }
            }
        }
        uploadHttp.newCall(Request.Builder().url(uploadUrl).put(body).build()).execute().use {
            if (!it.isSuccessful) throw IOException("R2 upload failed: HTTP ${it.code}")
        }
        val finish = JSONObject(request("POST", "/functions/v1/upload-classmate-resource",
            JSONObject().put("action", "finish")
                .put("resource_id", start.getString("resource_id"))))
        finish.getString("resource_id")
    }

    suspend fun uploadResource(
        resolver: ContentResolver, uri: Uri, batchId: String,
        semesterCourseId: String?, title: String, mimeType: String, size: Long,
        category: String,
    ): String = uploadResource(
        resolver = resolver,
        uri = uri,
        localFile = null,
        batchId = batchId,
        semesterCourseId = semesterCourseId,
        title = title,
        mimeType = mimeType,
        size = size,
        category = category,
        onProgress = null
    )

    private fun notificationPrefs() = appContext?.getSharedPreferences("classmate_notification_identity_${BuildConfig.CLASSMATE_ENV}", Context.MODE_PRIVATE)
    fun saveNotificationIdentity(profile: JSONObject) {
        notificationPrefs()?.edit()?.putString("profile", profile.toString())?.apply()
    }
    fun notificationIdentity(): JSONObject? = runCatching {
        notificationPrefs()?.getString("profile", null)?.let(::JSONObject)
    }.getOrNull()

    fun currentAuthUid(): String? {
        val token = accessToken
        if (!token.isNullOrBlank()) {
            val parts = token.split('.')
            if (parts.size >= 2) {
                val sub = runCatching {
                    val decoded = android.util.Base64.decode(
                        parts[1],
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    )
                    JSONObject(String(decoded, Charsets.UTF_8)).optString("sub").takeIf { it.isNotBlank() }
                }.getOrNull()
                if (!sub.isNullOrBlank()) return sub
            }
        }
        return notificationIdentity()?.optString("id")?.takeIf { it.isNotBlank() }
    }

    suspend fun uploadStagedNoticeAttachment(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String,
        sizeHint: Long = 0L,
        onProgress: (Int) -> Unit = {}
    ): JSONObject = withContext(Dispatchers.IO) {
        check(configured) { "Supabase URL or public key is missing" }
        refreshIfNeeded()
        val token = accessToken ?: throw IOException("Session expired; sign in again")
        val uid = currentAuthUid() ?: throw IOException("User identity unavailable; sign in again")

        val safeName = fileName.ifBlank { "attachment" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(160)

        var tempFile: java.io.File? = null
        var uploadMimeType = mimeType
        var uploadFileName = if (mimeType == "application/pdf" && !safeName.endsWith(".pdf", ignoreCase = true)) {
            "$safeName.pdf"
        } else {
            safeName
        }
        try {
            if (mimeType.startsWith("image/") && mimeType != "image/gif") {
                val compressed = compressNoticeImageToTempFile(context, uri, mimeType)
                if (compressed != null && compressed.file.length() > 0L && (sizeHint <= 0L || compressed.file.length() < sizeHint)) {
                    tempFile = compressed.file
                    uploadMimeType = compressed.mimeType
                    if (!uploadFileName.endsWith(".jpg", ignoreCase = true) && !uploadFileName.endsWith(".jpeg", ignoreCase = true)) {
                        uploadFileName = uploadFileName.substringBeforeLast('.') + ".jpg"
                    }
                } else {
                    compressed?.file?.delete()
                }
            }

            val resolvedSize = when {
                tempFile != null -> tempFile.length()
                sizeHint > 0L -> sizeHint
                else -> {
                    val tmp = java.io.File.createTempFile("notice_att_", ".tmp", context.cacheDir)
                    tempFile = tmp
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to read selected attachment.")
                    tmp.length()
                }
            }
            require(resolvedSize in 1L..104_857_600L) { "File must be 100 MB or smaller." }

            val body = object : okhttp3.RequestBody() {
                override fun contentType() = uploadMimeType.toMediaType()
                override fun contentLength() = resolvedSize
                override fun writeTo(sink: BufferedSink) {
                    val stream = tempFile?.inputStream() ?: context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to read selected attachment.")
                    stream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            sent += count
                            onProgress(((sent * 100L) / resolvedSize).toInt().coerceIn(0, 100))
                        }
                        sink.flush()
                    }
                }
            }

            val finalStorageKey = "pending/$uid/${java.util.UUID.randomUUID()}/$uploadFileName"
            val encodedKey = finalStorageKey.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val request = Request.Builder()
                .url("$base/storage/v1/object/notice-images/$encodedKey")
                .header("apikey", key)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", uploadMimeType)
                .header("x-upsert", "true")
                .post(body)
                .build()

            uploadHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errText = response.body?.string().orEmpty()
                    val detail = runCatching { JSONObject(errText).optString("message") }.getOrNull()
                    throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Attachment upload failed (HTTP ${response.code}).")
                }
            }

            JSONObject()
                .put("storage_key", finalStorageKey)
                .put("file_name", uploadFileName)
                .put("mime_type", uploadMimeType)
                .put("size_bytes", resolvedSize)
        } finally {
            tempFile?.delete()
        }
    }

    private data class CompressedImageResult(val file: java.io.File, val mimeType: String)

    private fun compressNoticeImageToTempFile(context: Context, uri: Uri, mimeType: String): CompressedImageResult? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val maxDim = 1920
        var sampleSize = 1
        while (srcW / sampleSize > maxDim * 2 || srcH / sampleSize > maxDim * 2) {
            sampleSize *= 2
        }

        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        // Preserve EXIF orientation
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it)
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide > maxDim) {
            val scale = maxDim.toFloat() / maxSide.toFloat()
            matrix.postScale(scale, scale)
        }
        if (!matrix.isIdentity) {
            val transformed = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            bitmap = transformed
        }

        val outFile = java.io.File.createTempFile("notice_cmp_", ".jpg", context.cacheDir)
        outFile.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        }
        bitmap.recycle()
        CompressedImageResult(outFile, "image/jpeg")
    }.getOrNull()

    fun signOut() {
        accessToken = null
        refreshToken = null
        expiresAtMs = 0
        sessionStore?.clear()
        notificationPrefs()?.edit()?.clear()?.apply()
        appContext?.let { com.shuaib.classmate.activities.ClassMateAcademicCache.clear(it) }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        classmate: Boolean = false,
        prefer: String? = null,
        longRunning: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        check(configured) { "Supabase URL or public key is missing" }
        if (!path.startsWith("/auth/v1/")) refreshIfNeeded()
        val builder = Request.Builder().url(base + path).header("apikey", key)
        if (!path.startsWith("/auth/v1/"))
            accessToken?.let { builder.header("Authorization", "Bearer $it") }
        if (classmate) {
            builder.header("Accept-Profile", "classmate")
            if (method in setOf("POST", "PUT", "PATCH", "DELETE"))
                builder.header("Content-Profile", "classmate")
        }
        prefer?.let { builder.header("Prefer", it) }
        val request = builder.method(method, body?.toString()?.toRequestBody(jsonType)).build()
        (if (longRunning) uploadHttp else http).newCall(request).execute().use { response ->
            val result = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(result) }.getOrNull()
                val message = sequenceOf("error_description", "msg", "message", "error")
                    .mapNotNull { error?.optString(it)?.ifBlank { null } }
                    .firstOrNull() ?: "HTTP ${response.code}"
                throw IOException("${response.code}: $message")
            }
            result
        }
    }
}
