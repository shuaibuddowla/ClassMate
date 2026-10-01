package com.shuaib.classmate.update

import java.io.File
import java.security.MessageDigest

/** Pure guards shared by the Android verifier and JVM tests. */
object UpdateSafety {
    fun acceptsIdentity(candidatePackage: String, ownPackage: String,
                        candidateCode: Long, metadataCode: Long, installedCode: Long): Boolean =
        candidatePackage == ownPackage && candidateCode == metadataCode && candidateCode > installedCode

    fun acceptsSigner(oldCurrent: List<ByteArray>, newCurrent: List<ByteArray>,
                      newHistory: List<ByteArray>?, oldMultiple: Boolean, newMultiple: Boolean): Boolean {
        if (oldCurrent.isEmpty() || newCurrent.isEmpty()) return false
        if (oldCurrent.size == newCurrent.size && oldCurrent.all { a ->
                newCurrent.any { b -> MessageDigest.isEqual(a, b) }
            }) return true
        if (oldMultiple || newMultiple || oldCurrent.size != 1 || newCurrent.size != 1 || newHistory == null)
            return false
        // The caller must obtain newHistory from PackageManager's validated APK SigningInfo.
        return newHistory.any { MessageDigest.isEqual(it, oldCurrent[0]) } &&
            newHistory.any { MessageDigest.isEqual(it, newCurrent[0]) }
    }

    fun matchesSha256(file: File, expectedHex: String): Boolean {
        if (!expectedHex.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val expected = ByteArray(32) { i -> expectedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return MessageDigest.isEqual(expected, digest.digest())
    }
}
