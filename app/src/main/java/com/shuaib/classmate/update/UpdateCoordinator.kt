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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed class UpdateOutcome {
    data object UpToDate : UpdateOutcome()
    data class WaitingForWifi(val version: String) : UpdateOutcome()
    data class PermissionRequired(val version: String) : UpdateOutcome()
    data class Installing(val version: String) : UpdateOutcome()
    data object AlreadyInstalling : UpdateOutcome()
}

object UpdateCoordinator {
    private const val PERIODIC_NAME = "ClassMatePeriodicUpdate"
    private const val FOREGROUND_NAME = "ClassMateForegroundUpdate"
    private val mutex = Mutex()

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
            if (!UpdatePolicy.mayAutoDownload(true, prefs.isWifiOnlyUpdates(), repository.isMetered()))
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
