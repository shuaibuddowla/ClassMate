package com.shuaib.classmate.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** All checks run before an install session is created. Android checks the signature again. */
class ApkVerifier(private val context: Context) {
    fun verify(file: File, metadata: UpdateMetadata) {
        if (file.length() != metadata.apkSize) throw PermanentUpdateException("APK size mismatch")
        if (!UpdateSafety.matchesSha256(file, metadata.sha256))
            throw PermanentUpdateException("APK SHA-256 mismatch")

        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw PermanentUpdateException("APK cannot be parsed")
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archiveCode = versionCode(archive)
        if (!UpdateSafety.acceptsIdentity(archive.packageName, context.packageName,
                archiveCode, metadata.versionCode, versionCode(installed)))
            throw PermanentUpdateException("APK package or version mismatch")
        if (!trustedSigningIdentity(installed, archive))
            throw PermanentUpdateException("APK signing certificate mismatch")
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28)
        info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun trustedSigningIdentity(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val old = installed.signingInfo ?: return false
            val next = archive.signingInfo ?: return false
            val oldCurrent = old.apkContentsSigners ?: return false
            val nextCurrent = next.apkContentsSigners ?: return false
            return UpdateSafety.acceptsSigner(oldCurrent.map { it.toByteArray() },
                nextCurrent.map { it.toByteArray() },
                next.signingCertificateHistory?.map { it.toByteArray() },
                old.hasMultipleSigners(), next.hasMultipleSigners())
        }
        @Suppress("DEPRECATION") val old = installed.signatures ?: return false
        @Suppress("DEPRECATION") val next = archive.signatures ?: return false
        return UpdateSafety.acceptsSigner(old.map { it.toByteArray() },
            next.map { it.toByteArray() }, null, false, false)
    }
}
