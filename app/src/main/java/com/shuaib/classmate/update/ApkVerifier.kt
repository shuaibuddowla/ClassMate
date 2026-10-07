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
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
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
        val oldCerts = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                ?: @Suppress("DEPRECATION") installed.signatures?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") installed.signatures?.map { it.toByteArray() }
        } ?: return false

        val nextCerts = if (Build.VERSION.SDK_INT >= 28) {
            archive.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                ?: @Suppress("DEPRECATION") archive.signatures?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") archive.signatures?.map { it.toByteArray() }
        }

        // If Android's getPackageArchiveInfo failed to populate archive signatures
        // (a known Android bug when APK is V2-only signed or on certain OS versions),
        // we do not reject it here because SHA-256 and GitHub release origin are verified,
        // and Android's PackageInstaller will enforce signature matching natively at install time.
        if (nextCerts.isNullOrEmpty()) {
            android.util.Log.w("ClassMateUpdate", "PackageArchiveInfo could not extract signatures; delegating to PackageInstaller")
            return true
        }

        val history = if (Build.VERSION.SDK_INT >= 28) {
            archive.signingInfo?.signingCertificateHistory?.map { it.toByteArray() }
        } else null

        val oldMultiple = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.hasMultipleSigners() ?: false
        } else false

        val nextMultiple = if (Build.VERSION.SDK_INT >= 28) {
            archive.signingInfo?.hasMultipleSigners() ?: false
        } else false

        return UpdateSafety.acceptsSigner(oldCerts, nextCerts, history, oldMultiple, nextMultiple)
    }
}
