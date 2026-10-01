package com.shuaib.classmate.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.shuaib.classmate.ClassMateApp
import com.shuaib.classmate.utils.AppPreferences
import java.io.File

/** Explicit, non-exported callback survives the Activity and worker lifetimes. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getLongExtra("version_code", 0L)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AppPreferences(context).apply {
                    setLastDownloadedVersionCode(0)
                    setUpdateInstallStartedAt(0)
                    setPendingMandatoryVersionCode(0)
                    setNeedsInstallerConfirmation(false)
                }
                File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
                UpdateNotifications.clear(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    fail(context, status, "Android did not provide an install confirmation")
                    return
                }
                val action = Intent(context, UpdateActionActivity::class.java).apply {
                    this.action = UpdateActionActivity.ACTION_CONFIRM
                    putExtra("confirmation", confirmation)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (ClassMateApp.isVisible) {
                    runCatching { context.startActivity(action) }.onFailure {
                        AppPreferences(context).setNeedsInstallerConfirmation(!UpdateNotifications.canNotify(context))
                        UpdateNotifications.show(context, "Confirm ClassMate update",
                            "Tap to open Android's installer", UpdateActionActivity.ACTION_CONFIRM, confirmation)
                    }
                } else {
                    AppPreferences(context).setNeedsInstallerConfirmation(!UpdateNotifications.canNotify(context))
                    UpdateNotifications.show(context, "Confirm ClassMate update",
                        "Tap to open Android's installer", UpdateActionActivity.ACTION_CONFIRM, confirmation)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(context, status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty())
            else -> fail(context, status, "Unknown installer result")
        }
        Log.i("ClassMateUpdate", "Install status=$status version=$code")
    }

    private fun fail(context: Context, status: Int, detail: String) {
        AppPreferences(context).setNeedsInstallerConfirmation(false)
        Log.e("ClassMateUpdate", "Install failed: status=$status $detail")
        UpdateNotifications.show(context, "ClassMate update couldn't be installed",
            "Tap to retry", UpdateActionActivity.ACTION_RETRY)
    }
}
