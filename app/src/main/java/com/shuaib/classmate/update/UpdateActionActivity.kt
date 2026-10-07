package com.shuaib.classmate.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.databinding.DialogAppUpdateBinding
import com.shuaib.classmate.utils.AppPreferences
import kotlinx.coroutines.launch
import java.util.Locale

/** User-action bridge for update prompt, download progress, system confirmation, and permissions. */
class UpdateActionActivity : AppCompatActivity() {

    private val permissionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            checkAndPrompt()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            ACTION_CONFIRM -> {
                AppPreferences(this).setNeedsInstallerConfirmation(false)
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra("confirmation", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("confirmation")
                if (confirmation != null) {
                    try {
                        startActivity(confirmation)
                    } catch (error: Exception) {
                        Toast.makeText(this, "Could not open Android installer", Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
            ACTION_PERMISSION -> showPermissionExplanation()
            ACTION_MANDATORY -> checkAndPrompt(forceMandatory = true)
            else -> checkAndPrompt(forceMandatory = false)
        }
    }

    private fun checkAndPrompt(forceMandatory: Boolean = false) {
        val checkingDialog = AlertDialog.Builder(this)
            .setTitle("Checking for updates")
            .setMessage("Contacting ClassMate release server…")
            .setCancelable(false)
            .create()
        checkingDialog.show()

        lifecycleScope.launch {
            val result = runCatching { UpdateRepository(this@UpdateActionActivity).fetch() }
            checkingDialog.dismiss()
            if (isFinishing || isDestroyed) return@launch

            val metadata = result.getOrNull()
            if (metadata == null) {
                showResult("Unable to check for updates", result.exceptionOrNull()?.message ?: "Check your connection and try again.")
                return@launch
            }

            val installedCode = BuildConfig.VERSION_CODE.toLong()
            val isMandatory = forceMandatory || metadata.isMandatoryFor(installedCode)

            if (metadata.versionCode <= installedCode && !isMandatory) {
                showResult("You're up to date", "ClassMate ${BuildConfig.VERSION_NAME} is the latest version.")
                return@launch
            }

            if (isMandatory) {
                AppPreferences(this@UpdateActionActivity).setPendingMandatoryVersionCode(metadata.versionCode)
            }

            showUpdateDialog(metadata, isMandatory)
        }
    }

    private fun showUpdateDialog(metadata: UpdateMetadata, isMandatory: Boolean) {
        val binding = DialogAppUpdateBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(!isMandatory)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (isMandatory) {
            dialog.setCanceledOnTouchOutside(false)
            onBackPressedDispatcher.addCallback(this) {
                finishAffinity()
            }
        } else {
            dialog.setOnCancelListener { finish() }
        }

        binding.tvLocalVersionPill.text = "v${BuildConfig.VERSION_NAME}"
        binding.tvUpdateVersion.text = "v${metadata.versionName}"

        if (isMandatory) {
            binding.tvUpdateTitle.text = "Update Required"
            binding.btnUpdateLater.visibility = View.GONE
        } else {
            binding.tvUpdateTitle.text = "New Update Available"
            binding.btnUpdateLater.visibility = View.VISIBLE
            binding.btnUpdateLater.setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }

        val notes = if (metadata.releaseNotes.isNotEmpty()) {
            metadata.releaseNotes.joinToString("\n• ", prefix = "• ")
        } else {
            "• Stability and performance improvements."
        }
        binding.tvUpdateChangelog.text = notes

        val repository = UpdateRepository(this)
        val alreadyDownloaded = repository.hasDownloaded(metadata)
        val sizeMb = String.format(Locale.US, "%.1f MB", metadata.apkSize / (1024.0 * 1024.0))

        if (alreadyDownloaded) {
            binding.btnUpdateNow.text = "Install Now"
        } else {
            binding.btnUpdateNow.text = "Update ($sizeMb)"
        }

        binding.btnUpdateNow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                dialog.dismiss()
                showPermissionExplanation()
                return@setOnClickListener
            }

            binding.btnUpdateNow.isEnabled = false
            binding.btnUpdateLater.isEnabled = false
            binding.layoutDownloadProgress.visibility = View.VISIBLE
            binding.pbDownloadProgress.progress = if (alreadyDownloaded) 100 else 0
            binding.tvDownloadProgressText.text = if (alreadyDownloaded) "Verifying update…" else "Starting download…"

            lifecycleScope.launch {
                val result = runCatching {
                    UpdateCoordinator.run(this@UpdateActionActivity, manual = true) { percent ->
                        runOnUiThread {
                            binding.pbDownloadProgress.progress = percent
                            val downloadedMb = (metadata.apkSize * percent / 100.0) / (1024.0 * 1024.0)
                            val totalMb = metadata.apkSize / (1024.0 * 1024.0)
                            binding.tvDownloadProgressText.text = String.format(
                                Locale.US,
                                "Downloading: %d%% (%.1f MB / %.1f MB)",
                                percent, downloadedMb, totalMb
                            )
                        }
                    }
                }

                if (isFinishing || isDestroyed) return@launch

                val outcome = result.getOrNull()
                if (outcome is UpdateOutcome.Installing) {
                    dialog.dismiss()
                    finish()
                } else if (outcome is UpdateOutcome.PermissionRequired) {
                    dialog.dismiss()
                    showPermissionExplanation()
                } else if (outcome is UpdateOutcome.WaitingForWifi) {
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateLater.isEnabled = true
                    binding.layoutDownloadProgress.visibility = View.GONE
                    showResult("Wi-Fi Required", "Connect to Wi-Fi to download this update, or turn off 'Wi-Fi only' in Profile.")
                } else if (result.isFailure) {
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateLater.isEnabled = true
                    binding.layoutDownloadProgress.visibility = View.GONE
                    showResult("Update Failed", result.exceptionOrNull()?.message ?: "Could not complete update. Please try again.")
                } else {
                    dialog.dismiss()
                    finish()
                }
            }
        }

        dialog.show()
    }

    private fun showPermissionExplanation() {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            checkAndPrompt()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow ClassMate to install its own updates")
            .setMessage("This permission is used only when installing an official ClassMate update.")
            .setPositiveButton("Open settings") { _, _ ->
                permissionResult.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Later") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showResult(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val ACTION_PROMPT = "com.shuaib.classmate.update.PROMPT"
        const val ACTION_CONFIRM = "com.shuaib.classmate.update.CONFIRM"
        const val ACTION_PERMISSION = "com.shuaib.classmate.update.PERMISSION"
        const val ACTION_RETRY = "com.shuaib.classmate.update.RETRY"
        const val ACTION_CHECK = "com.shuaib.classmate.update.CHECK"
        const val ACTION_MANDATORY = "com.shuaib.classmate.update.MANDATORY"
    }
}
