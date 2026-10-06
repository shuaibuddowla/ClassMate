package com.shuaib.classmate.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shuaib.classmate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class PermanentUpdateException(message: String) : IOException(message)

class UpdateRepository(private val context: Context) {
    private val base = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .addNetworkInterceptor { chain ->
            if (!chain.request().url.isHttps) throw PermanentUpdateException("Update redirect was not HTTPS")
            chain.proceed(chain.request())
        }
        .build()

    val configured get() = BuildConfig.CLASSMATE_ENV == "production" &&
        base.startsWith("https://github.com/") && base.endsWith("/releases/latest/download")

    suspend fun fetch(): UpdateMetadata = withContext(Dispatchers.IO) {
        if (!configured) throw PermanentUpdateException("Update server is not configured")
        val request = Request.Builder().url("$base/update.json?check=${System.currentTimeMillis()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json")
            .header("User-Agent", "ClassMate-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update metadata HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update metadata")
            val bytes = ByteArrayOutputStream().also { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 65_536)
                            throw PermanentUpdateException("Update metadata is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }.toByteArray()
            try { UpdateMetadata.parse(bytes.toString(Charsets.UTF_8), base) }
            catch (error: Exception) { throw PermanentUpdateException("Invalid update metadata: ${error.message}") }
        }
    }

    fun isMetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun hasDownloaded(metadata: UpdateMetadata): Boolean = File(context.filesDir,"updates/classmate-${metadata.versionCode}.apk").let { it.exists() && it.length()==metadata.apkSize }

    suspend fun download(metadata: UpdateMetadata, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        directory.listFiles()?.filter { it.name != "classmate-${metadata.versionCode}.apk" }
            ?.forEach { it.delete() }
        val ready = File(directory, "classmate-${metadata.versionCode}.apk")
        val temp = File(directory, "classmate-update.tmp")
        if (ready.exists() && ready.length() == metadata.apkSize) return@withContext ready
        ready.delete()
        temp.delete()
        val request = Request.Builder().url(metadata.apkUrl)
            .header("User-Agent", "ClassMate-Android-Updater").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("APK download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty APK response")
                if (body.contentLength() > 0 && body.contentLength() != metadata.apkSize)
                    throw PermanentUpdateException("APK size differs from metadata")
                var count = 0L
                var lastProgress = -1
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > metadata.apkSize) throw PermanentUpdateException("APK exceeds declared size")
                            output.write(buffer, 0, read)
                            val percent = (count * 100 / metadata.apkSize).toInt()
                            if (percent != lastProgress) {
                                lastProgress = percent
                                progress(percent)
                            }
                        }
                    }
                }
                if (count != metadata.apkSize) throw IOException("APK download was incomplete")
            }
            if (!temp.renameTo(ready)) throw IOException("Could not finalize APK download")
            ready
        } finally { temp.delete() }
    }
}
