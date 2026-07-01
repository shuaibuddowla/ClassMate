package com.shuaib.classmate.utils

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.DialogPdfOptionsBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryDownloadManager
import com.shuaib.classmate.storage.LibraryUrlOpener

object PdfDialogHelper {
    fun showPdfOptions(
        activity: Activity,
        context: Context,
        pdf: PdfFile,
        onOfflineStatusChanged: (() -> Unit)? = null
    ) {
        val isDownloaded = LibraryDownloadManager.isDownloaded(context, pdf.id)
        if (isDownloaded) {
            // Directly open local offline PDF inside the app without showing the bottom sheet
            LibraryDownloadManager.openLocalFile(context, pdf)
            return
        }

        // Show the bottom sheet options dialog
        val dialog = BottomSheetDialog(context)
        val layoutInflater = LayoutInflater.from(context)
        val sheet = DialogPdfOptionsBinding.inflate(layoutInflater)
        
        sheet.tvTitle.text = pdf.title.ifBlank { "Library material" }
        sheet.tvMeta.text = pdf.subject.ifBlank { "ClassMate Library" }

        val url = pdf.downloadUrl.ifBlank { pdf.driveUrl.ifBlank { pdf.telegramUrl } }
        
        // Dynamically update the text of the online viewing option depending on the provider/URL
        val openOnlineText = when {
            pdf.provider == "telegram" || url.contains("t.me", ignoreCase = true) || pdf.telegramUrl.contains("t.me", ignoreCase = true) -> "Open in Telegram"
            pdf.provider == "google_drive" || pdf.provider == "drive" || url.contains("drive.google.com", ignoreCase = true) -> "Open in Drive"
            pdf.provider == "github_releases" || pdf.provider == "github" || url.contains("github.com", ignoreCase = true) -> "Open in GitHub"
            else -> "Open Online"
        }
        sheet.tvOpenOnlineLabel.text = openOnlineText

        // Only allow Google Drive files to be made available offline (excluding folder links)
        val isDrive = pdf.provider == "google_drive" || pdf.provider == "drive" || url.contains("drive.google.com", ignoreCase = true)
        val isFolder = url.contains("/folders/") || url.contains("drive/folders")
        val showDownload = isDrive && !isFolder

        sheet.btnDownload.isVisible = showDownload
        sheet.tvDownloadLabel.text = "Make Available Offline"
        sheet.btnDeleteCache.isVisible = false // Bypassed for offline copies anyway

        val visuals = FileVisuals.getVisuals(pdf)
        sheet.iconContainer.background = ContextCompat.getDrawable(context, visuals.backgroundRes)
        sheet.ivFileIcon.setImageResource(visuals.iconRes)
        sheet.ivFileIcon.setColorFilter(visuals.tint)

        sheet.btnOpenOnline.setOnClickListener {
            dialog.dismiss()
            LibraryUrlOpener.open(context, pdf)
        }
        
        sheet.btnDownload.setOnClickListener {
            sheet.tvDownloadLabel.text = "Downloading..."
            sheet.btnDownload.isEnabled = false
            LibraryDownloadManager.downloadFile(
                context = context,
                pdfFile = pdf,
                onProgress = { progress ->
                    activity.runOnUiThread {
                        sheet.tvDownloadLabel.text = "Downloading $progress%"
                    }
                },
                onSuccess = {
                    activity.runOnUiThread {
                        Toast.makeText(context, "Saved offline", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onOfflineStatusChanged?.invoke()
                        // Directly open the downloaded PDF inside the app
                        LibraryDownloadManager.openLocalFile(context, pdf)
                    }
                },
                onFailure = { error ->
                    activity.runOnUiThread {
                        sheet.btnDownload.isEnabled = true
                        sheet.tvDownloadLabel.text = "Make Available Offline"
                        Toast.makeText(context, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        dialog.setContentView(sheet.root)
        dialog.show()
    }
}
