package com.shuaib.classmate.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Small user-action bridge for system confirmation, install-source permission and retries. */
class UpdateActionActivity : AppCompatActivity() {
    private val permissionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) runUpdate()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            ACTION_CONFIRM -> {
                com.shuaib.classmate.utils.AppPreferences(this).setNeedsInstallerConfirmation(false)
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra("confirmation", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("confirmation")
                if (confirmation != null) {
                    try { startActivity(confirmation) }
                    catch (error: Exception) { Toast.makeText(this, "Could not open Android installer", Toast.LENGTH_LONG).show() }
                }
                finish()
            }
            ACTION_PROMPT -> AlertDialog.Builder(this)
                .setTitle("New ClassMate update")
                .setMessage("Update to the latest version? The app will download and verify the release, then open Android’s installer.")
                .setPositiveButton("Update") { _, _ -> runUpdate() }
                .setNegativeButton("Later") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            ACTION_PERMISSION -> showPermissionExplanation()
            ACTION_MANDATORY -> AlertDialog.Builder(this)
                .setTitle("ClassMate update required")
                .setMessage("This version is no longer supported. Update ClassMate to continue.")
                .setCancelable(false)
                .setPositiveButton("Update") { _, _ -> runUpdate() }
                .show()
            else -> runUpdate()
        }
    }

    private fun showPermissionExplanation() {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            runUpdate(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow ClassMate to install its own updates")
            .setMessage("This permission is used only when installing an official ClassMate update.")
            .setPositiveButton("Open settings") { _, _ ->
                permissionResult.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Later") { _, _ -> finish() }
            .show()
    }

    private fun runUpdate() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Checking for updates")
            .setMessage("Checking the ClassMate release…")
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val result = runCatching { UpdateCoordinator.run(this@UpdateActionActivity, manual = true) { percent ->
                runOnUiThread { dialog.setMessage("Downloading ClassMate update · $percent%") }
            } }
            dialog.dismiss()
            if (isFinishing || isDestroyed) return@launch
            when (val outcome = result.getOrNull()) {
                is UpdateOutcome.Available -> showResult("Update available", "ClassMate ${outcome.version} is available.")
                UpdateOutcome.UpToDate -> showResult("You're up to date", "This is the latest ClassMate version.")
                is UpdateOutcome.WaitingForWifi -> showResult("ClassMate ${outcome.version} is available",
                    "Connect to Wi-Fi to download it, or turn off Wi-Fi only in App Updates.")
                is UpdateOutcome.PermissionRequired -> showPermissionExplanation()
                is UpdateOutcome.Installing -> showResult("ClassMate ${outcome.version} is available",
                    "The verified update was sent to Android's installer.")
                UpdateOutcome.AlreadyInstalling -> showResult("Update in progress", "Android is processing the update.")
                null -> showResult("Unable to check for updates", result.exceptionOrNull()?.message ?: "Try again later.")
            }
        }
    }

    private fun showResult(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val ACTION_PROMPT = "com.shuaib.classmate.update.PROMPT"
        const val ACTION_CONFIRM = "com.shuaib.classmate.update.CONFIRM"
        const val ACTION_PERMISSION = "com.shuaib.classmate.update.PERMISSION"
        const val ACTION_RETRY = "com.shuaib.classmate.update.RETRY"
        const val ACTION_MANDATORY = "com.shuaib.classmate.update.MANDATORY"
    }
}
