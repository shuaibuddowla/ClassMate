package com.shuaib.classmate.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateSafetyTest {
    @Test fun packageAndVersionMustMatch() {
        assertTrue(UpdateSafety.acceptsIdentity("com.shuaib.classmate", "com.shuaib.classmate", 7, 7, 6))
        assertFalse(UpdateSafety.acceptsIdentity("another.app", "com.shuaib.classmate", 7, 7, 6))
        assertFalse(UpdateSafety.acceptsIdentity("com.shuaib.classmate", "com.shuaib.classmate", 8, 7, 6))
        assertFalse(UpdateSafety.acceptsIdentity("com.shuaib.classmate", "com.shuaib.classmate", 6, 6, 6))
    }

    @Test fun signerMustMatchOrHaveValidatedRotationHistory() {
        val trusted = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        assertTrue(UpdateSafety.acceptsSigner(listOf(trusted), listOf(trusted), null, false, false))
        assertFalse(UpdateSafety.acceptsSigner(listOf(trusted), listOf(other), null, false, false))
        assertTrue(UpdateSafety.acceptsSigner(listOf(trusted), listOf(other),
            listOf(trusted, other), false, false))
        assertFalse(UpdateSafety.acceptsSigner(listOf(trusted), listOf(other),
            listOf(trusted, other), true, false))
    }

    @Test fun hashMismatchBlocksCandidate() {
        val file = File.createTempFile("classmate-update-test", ".apk")
        try {
            file.writeText("trusted bytes")
            val good = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertTrue(UpdateSafety.matchesSha256(file, good))
            assertFalse(UpdateSafety.matchesSha256(file, "0".repeat(64)))
        } finally { file.delete() }
    }
}
