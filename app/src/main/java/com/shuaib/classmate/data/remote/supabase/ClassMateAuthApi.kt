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
    private val http = OkHttpClient()
    private val uploadHttp = OkHttpClient.Builder().writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.CLASSMATE_URL.trimEnd('/')
    private val key = BuildConfig.CLASSMATE_PUBLISHABLE_KEY
    private var sessionStore: ClassMateSessionStore? = null

    @Volatile var accessToken: String? = null
        private set
    @Volatile private var refreshToken: String? = null
    @Volatile private var expiresAtMs: Long = 0
    private val refreshMutex = Mutex()

    val configured: Boolean get() = BuildConfig.CLASSMATE_AUTH_ENABLED &&
        base.startsWith("https://") && key.isNotBlank()

    fun attach(context: Context) {
        sessionStore = ClassMateSessionStore(context, BuildConfig.CLASSMATE_ENV)
    }

    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val stored = sessionStore?.load() ?: return@withContext false
        runCatching { refreshSession(stored) }.isSuccess
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
        accessToken = token
        refreshToken = response.optString("refresh_token").ifBlank { null }
        expiresAtMs = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000
        runCatching { sessionStore?.save(refreshToken) }
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

    private suspend fun refreshIfNeeded() {
        if (accessToken == null || System.currentTimeMillis() < expiresAtMs - 60_000) return
        refreshMutex.withLock {
            if (System.currentTimeMillis() < expiresAtMs - 60_000) return@withLock
            val token = refreshToken ?: throw IOException("Session expired; sign in again")
            refreshSession(token)
        }
    }

    suspend fun initializeProfile(): JSONObject = rpc("create_or_initialize_profile", JSONObject())

    suspend fun rpc(name: String, args: JSONObject): JSONObject =
        JSONObject(request("POST", "/rest/v1/rpc/$name", args, classmate = true))

    suspend fun rpcText(name: String, args: JSONObject): String =
        request("POST", "/rest/v1/rpc/$name", args, classmate = true)

    suspend fun registerDeviceToken(token: String) {
        request("POST", "/rest/v1/rpc/register_device_token",
            JSONObject().put("target_token", token), classmate = true)
    }

    suspend fun unregisterDeviceToken(token: String) {
        val encoded = URLEncoder.encode(token, "UTF-8")
        request("DELETE", "/rest/v1/device_tokens?fcm_token=eq.$encoded", classmate = true)
    }

    suspend fun rows(table: String, query: String): JSONArray =
        JSONArray(request("GET", "/rest/v1/$table?$query", classmate = true))

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

    suspend fun uploadResource(
        resolver: ContentResolver, uri: Uri, batchId: String,
        semesterCourseId: String?, title: String, mimeType: String, size: Long,
        category: String,
    ): String = withContext(Dispatchers.IO) {
        require(size in 1..100_000_000) { "File must be between 1 byte and 100 MB" }
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
                val input = resolver.openInputStream(uri) ?: throw IOException("Selected file unavailable")
                input.use { sink.writeAll(it.source()) }
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

    fun signOut() {
        accessToken = null
        refreshToken = null
        expiresAtMs = 0
        sessionStore?.clear()
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        classmate: Boolean = false,
        prefer: String? = null,
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
        http.newCall(request).execute().use { response ->
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
