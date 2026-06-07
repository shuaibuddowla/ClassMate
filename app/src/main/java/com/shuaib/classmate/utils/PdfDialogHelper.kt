package com.shuaib.classmate.utils

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.shuaib.classmate.databinding.DialogPdfOptionsBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryDownloadManager
import com.shuaib.classmate.storage.LibraryUrlOpener
import java.util.Locale

object PdfDialogHelper {
    fun showPdfOptions(
        activity: Activity,
        context: Context,
        pdf: PdfFile,
        onOfflineStatusChanged: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val layoutInflater = LayoutInflater.from(context)
        val sheet = DialogPdfOptionsBinding.inflate(layoutInflater)
        val isDownloaded = LibraryDownloadManager.isDownloaded(context, pdf.id)
        
        sheet.tvTitle.text = pdf.title.ifBlank { "Library material" }
        sheet.tvMeta.text = listOf(pdf.subject.ifBlank { "ClassMate Library" }, formatBytes(pdf.sizeBytes))
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        
        sheet.tvDownloadLabel.text = if (isDownloaded) "Open Offline" else "Download Offline"
        sheet.btnDeleteCache.isVisible = isDownloaded

        val visuals = FileVisuals.getVisuals(pdf)
        sheet.iconContainer.background = ContextCompat.getDrawable(context, visuals.backgroundRes)
        sheet.ivFileIcon.setImageResource(visuals.iconRes)
        sheet.ivFileIcon.setColorFilter(visuals.tint)

        sheet.btnOpenOnline.setOnClickListener {
            dialog.dismiss()
            LibraryUrlOpener.open(context, pdf)
        }
        
        sheet.btnDownload.setOnClickListener {
            if (LibraryDownloadManager.isDownloaded(context, pdf.id)) {
                dialog.dismiss()
                LibraryDownloadManager.openLocalFile(context, pdf)
                return@setOnClickListener
            }
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
                        Toast.makeText(context, "Saved for offline study", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onOfflineStatusChanged?.invoke()
                    }
                },
                onFailure = { error ->
                    activity.runOnUiThread {
                        sheet.btnDownload.isEnabled = true
                        sheet.tvDownloadLabel.text = "Download Offline"
                        Toast.makeText(context, "Download failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        
        sheet.btnDeleteCache.setOnClickListener {
            LibraryDownloadManager.deleteDownload(context, pdf.id)
            Toast.makeText(context, "Offline copy removed", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onOfflineStatusChanged?.invoke()
        }
        
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return if (unit == 0) "${bytes}B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
