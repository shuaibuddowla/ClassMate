package com.shuaib.classmate.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.models.PdfFile

object LibraryUrlOpener {
    private const val TAG = "LibraryOpenDebug"

    fun open(context: Context, file: PdfFile) {
        val url = file.downloadUrl.ifBlank { file.driveUrl.ifBlank { file.telegramUrl } }
        if (url.isBlank()) {
            Toast.makeText(context, "Resource link not available", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "openUrl resourceId=${file.id} url=$url")
        Toast.makeText(context, "Opening resource...", Toast.LENGTH_SHORT).show()

        val uri = Uri.parse(url)
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setToolbarColor(ContextCompat.getColor(context, R.color.cm_background))
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "customTabsFailed resourceId=${file.id} message=${e.message}", e)
            try {
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "browserOpenFailed resourceId=${file.id} message=${fallbackError.message}", fallbackError)
                Toast.makeText(context, "Unable to open resource link", Toast.LENGTH_LONG).show()
            }
        }
    }
}
