package com.shuaib.classmate.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.DialogAppUpdateBinding
import com.shuaib.classmate.notices.NoticeTextFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val latestVersionName: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSize: Long
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/shuaibuddowla/ClassMate/releases/latest"
    
    // Toggle for testing the update flow (Set to false for production)
    var isMockEnabled = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var downloadJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks the latest release from GitHub.
     */
    suspend fun checkLatestRelease(): AppUpdateInfo = withContext(Dispatchers.IO) {
        if (isMockEnabled) {
            return@withContext AppUpdateInfo(
                latestVersionName = "9.9.9",
                changelog = """
                    # ClassMate Update v9.9.9
                    
                    This is a **mock update** designed to verify that the in-app updater is functioning as expected.
                    
                    ### Key Improvements:
                    * **Ask AI Tab**: Restructured the chat interface for a smoother experience.
                    * **Bus Schedules**: Integrated live bus timings and routes.
                    * **Offline Storage**: Improved PDF caching and database performance.
                    
                    *Enjoy the updated ClassMate experience!*
                """.trimIndent(),
                // Using a small text file from the repo to simulate downloading an APK quickly for testing
                apkDownloadUrl = "https://raw.githubusercontent.com/shuaibuddowla/ClassMate/main/README.md",
                apkSize = 8293L
            )
        }

        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ClassMate-Android-Updater")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub API request failed: HTTP ${response.code}")
            }
            
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")
            val changelog = json.optString("body", "No release notes provided.")
            
            // Find the first asset ending with .apk
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
            
            if (apkUrl.isBlank()) {
                throw IOException("No APK asset found in the latest GitHub release.")
            }
            
            AppUpdateInfo(
                latestVersionName = tagName,
                changelog = changelog,
                apkDownloadUrl = apkUrl,
                apkSize = apkSize
            )
        }
    }

    /**
     * Compares the remote version with the local version.
     */
    fun isUpdateAvailable(latestTag: String): Boolean {
        val localVersion = BuildConfig.VERSION_NAME
        val localClean = localVersion.trim().lowercase().removePrefix("v")
        val remoteClean = latestTag.trim().lowercase().removePrefix("v")

        if (localClean == remoteClean) return false

        val localParts = localClean.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remoteClean.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(localParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val l = localParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (l > r) return false
        }
        
        return false
    }

    /**
     * Downloads the APK in a background thread, notifying the callback of progress.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "ClassMate-Android-Updater")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download update: HTTP ${response.code}")
            }
            
            val body = response.body ?: throw IOException("Download response body was empty.")
            val totalBytes = body.contentLength()
            
            body.byteStream().use { input ->
                destinationFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        
                        val read = input.read(buffer)
                        if (read == -1) break
                        
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        
                        val percent = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }
                        
                        onProgress(percent, bytesDownloaded, totalBytes)
                    }
                }
            }
        }
    }

    /**
     * Shows the beautiful custom update dialog.
     */
    fun showUpdateDialog(
        context: Context,
        updateInfo: AppUpdateInfo,
        coroutineScope: CoroutineScope
    ) {
        val inflater = LayoutInflater.from(context)
        val dialogBinding = DialogAppUpdateBinding.inflate(inflater)
        
        val dialog = MaterialAlertDialogBuilder(context, R.style.Theme_ClassMate_Dialog)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val localVerClean = BuildConfig.VERSION_NAME.trim().lowercase().removePrefix("v")
        val remoteVerClean = updateInfo.latestVersionName.trim().lowercase().removePrefix("v")
        dialogBinding.tvLocalVersionPill.text = "v$localVerClean"
        dialogBinding.tvUpdateVersion.text = "v$remoteVerClean"

        // Format changelog using existing Markwon formatter
        val formattedChangelog = NoticeTextFormatter.format(context, updateInfo.changelog)
        dialogBinding.tvUpdateChangelog.text = formattedChangelog

        var isDownloading = false

        dialogBinding.btnUpdateNow.setOnClickListener {
            if (!isDownloading) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Toast.makeText(
                            context,
                            "Please enable \"Install unknown apps\" for ClassMate, then try again.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                }

                isDownloading = true
                dialogBinding.btnUpdateNow.isEnabled = false
                dialogBinding.btnUpdateLater.text = "Cancel"
                dialogBinding.layoutDownloadProgress.visibility = View.VISIBLE

                val apkFile = File(context.cacheDir, "update.apk")
                
                downloadJob = coroutineScope.launch {
                    try {
                        downloadApk(updateInfo.apkDownloadUrl, apkFile) { percent, bytes, total ->
                            mainHandler.post {
                                dialogBinding.pbDownloadProgress.progress = percent
                                val sizeText = if (total > 0) {
                                    val downloadedMb = String.format("%.1f", bytes.toDouble() / (1024 * 1024))
                                    val totalMb = String.format("%.1f", total.toDouble() / (1024 * 1024))
                                    "$percent% ($downloadedMb MB / $totalMb MB)"
                                } else {
                                    val downloadedMb = String.format("%.1f", bytes.toDouble() / (1024 * 1024))
                                    "$downloadedMb MB"
                                }
                                dialogBinding.tvDownloadProgressText.text = "Downloading: $sizeText"
                            }
                        }
                        
                        mainHandler.post {
                            dialog.dismiss()
                            installApk(context, apkFile)
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            if (downloadJob?.isCancelled == true) {
                                Toast.makeText(context, "Update download canceled.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                            isDownloading = false
                            dialogBinding.btnUpdateNow.isEnabled = true
                            dialogBinding.btnUpdateLater.text = "Later"
                            dialogBinding.layoutDownloadProgress.visibility = View.GONE
                        }
                    }
                }
            }
        }

        dialogBinding.btnUpdateLater.setOnClickListener {
            if (isDownloading) {
                downloadJob?.cancel()
                isDownloading = false
                dialogBinding.btnUpdateNow.isEnabled = true
                dialogBinding.btnUpdateLater.text = "Later"
                dialogBinding.layoutDownloadProgress.visibility = View.GONE
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * Triggers the Android Package Installer to install the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, "Update file is missing or corrupted.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(
                    context, 
                    "Please enable the \"Install unknown apps\" permission for ClassMate, then try again.", 
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        try {
            val authority = "${context.packageName}.provider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
