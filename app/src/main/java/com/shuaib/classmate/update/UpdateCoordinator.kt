package com.shuaib.classmate.update

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.utils.AppPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed class UpdateOutcome {
    data object UpToDate : UpdateOutcome()
    data class Available(val version: String) : UpdateOutcome()
    data class WaitingForWifi(val version: String) : UpdateOutcome()
    data class PermissionRequired(val version: String) : UpdateOutcome()
    data class Installing(val version: String) : UpdateOutcome()
    data object AlreadyInstalling : UpdateOutcome()
}

object UpdateCoordinator {
    private const val PERIODIC_NAME = "ClassMatePeriodicUpdate"
    private const val FOREGROUND_NAME = "ClassMateForegroundUpdate"
    private val mutex = Mutex()
    private var foregroundBusy=false
    private var lastForegroundCheck=0L
    private var promptedVersion=0L
    fun promptOnOpen(activity: androidx.activity.ComponentActivity) {
        if(foregroundBusy || System.currentTimeMillis()-lastForegroundCheck<60_000 || !UpdateRepository(activity).configured) return
        foregroundBusy=true; lastForegroundCheck=System.currentTimeMillis()
        activity.lifecycleScope.launch {
            try {
                val metadata=UpdateRepository(activity).fetch()
                if(metadata.versionCode<=BuildConfig.VERSION_CODE || metadata.versionCode==promptedVersion || activity.isFinishing || activity.isDestroyed) return@launch
                if(!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
                promptedVersion=metadata.versionCode
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("ClassMate ${metadata.versionName} is available")
                    .setMessage(if(UpdateRepository(activity).hasDownloaded(metadata)) "The update has been downloaded. Tap Update to verify it and open Android's installer." else "The update can download automatically using your update settings. Tap Update to prepare it now and open Android's installer.")
                    .setPositiveButton("Update") { _, _ -> activity.startActivity(android.content.Intent(activity,UpdateActionActivity::class.java).setAction(UpdateActionActivity.ACTION_RETRY)) }
                    .setNegativeButton("Later",null).show()
            } catch(error:Exception) { android.util.Log.w("ClassMateUpdate","Foreground update check unavailable",error) }
            finally { foregroundBusy=false }
        }
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        val manager = WorkManager.getInstance(app)
        if (!prefs.isAutoUpdateEnabled() || !UpdateRepository(app).configured) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(FOREGROUND_NAME)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(
            if (prefs.isWifiOnlyUpdates()) NetworkType.UNMETERED else NetworkType.CONNECTED
        ).build()
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    fun enqueueReleaseCheck(context: Context) = enqueueCheck(context, fromRelease = true)

    fun enqueueForegroundCheck(context: Context) = enqueueCheck(context, fromRelease = false)

    private fun enqueueCheck(context: Context, fromRelease: Boolean) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        if (!prefs.isAutoUpdateEnabled() || !UpdateRepository(app).configured) return
        if (!fromRelease && System.currentTimeMillis() - prefs.lastUpdateCheckTimestamp() < TimeUnit.HOURS.toMillis(1)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(
            if (prefs.isWifiOnlyUpdates()) NetworkType.UNMETERED else NetworkType.CONNECTED
        ).build()
        val work = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(FOREGROUND_NAME, ExistingWorkPolicy.KEEP, work)
    }

    suspend fun run(context: Context, manual: Boolean = false, progress: (Int) -> Unit = {}): UpdateOutcome =
        mutex.withLock {
            val app = context.applicationContext
            val prefs = AppPreferences(app)
            if (!manual && !prefs.isAutoUpdateEnabled()) return@withLock UpdateOutcome.UpToDate
            val repository = UpdateRepository(app)
            val metadata = repository.fetch()
            prefs.setLastUpdateCheckTimestamp(System.currentTimeMillis())
            val installedCode = BuildConfig.VERSION_CODE.toLong()
            if (!UpdatePolicy.isNewer(metadata.versionCode, installedCode)) {
                prefs.setPendingMandatoryVersionCode(0)
                return@withLock UpdateOutcome.UpToDate
            }
            if (!repository.hasDownloaded(metadata) && !UpdatePolicy.mayAutoDownload(manual || prefs.isAutoUpdateEnabled(), prefs.isWifiOnlyUpdates(), repository.isMetered()))
                return@withLock UpdateOutcome.WaitingForWifi(metadata.versionName)
            val started = prefs.updateInstallStartedAt()
            if (!manual && prefs.lastDownloadedVersionCode() == metadata.versionCode &&
                System.currentTimeMillis() - started < TimeUnit.MINUTES.toMillis(30))
                return@withLock UpdateOutcome.AlreadyInstalling

            val apk = repository.download(metadata, progress)
            try { withContext(Dispatchers.IO) { ApkVerifier(app).verify(apk, metadata) } }
            catch (error: Exception) {
                apk.delete()
                throw error
            }
            // Background work may prepare the APK, but only a user action may open the installer.
            if (!UpdatePolicy.mayInstall(manual)) {
                UpdateNotifications.show(app,"ClassMate ${metadata.versionName} is ready",
                    "Downloaded and verified. Tap to update when you're ready.",UpdateActionActivity.ACTION_PROMPT)
                return@withLock UpdateOutcome.Available(metadata.versionName)
            }
            if (metadata.isMandatoryFor(installedCode))
                prefs.setPendingMandatoryVersionCode(metadata.versionCode)
            if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                UpdateNotifications.show(app, "Allow ClassMate updates",
                    "Tap to allow installs from ClassMate", UpdateActionActivity.ACTION_PERMISSION)
                return@withLock UpdateOutcome.PermissionRequired(metadata.versionName)
            }
            try {
                prefs.setLastDownloadedVersionCode(metadata.versionCode)
                prefs.setUpdateInstallStartedAt(System.currentTimeMillis())
                withContext(Dispatchers.IO) { ApkInstaller(app).install(apk, metadata.versionCode) }
                UpdateOutcome.Installing(metadata.versionName)
            } catch (error: Exception) {
                prefs.setLastDownloadedVersionCode(0)
                prefs.setUpdateInstallStartedAt(0)
                throw error
            }
        }

}
