package com.shuaib.classmate.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

class ApkInstaller(private val context: Context) {
    fun install(apk: File, versionCode: Long) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("classmate-update.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output, 32 * 1024) }
                    session.fsync(output)
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .putExtra("version_code", versionCode)
                    .putExtra("session_id", sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender
                session.commit(sender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }
}
