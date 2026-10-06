package com.shuaib.classmate.update

import org.junit.Assert.*
import org.junit.Test

class UpdateMetadataTest {
    private val base = "https://github.com/shuaibuddowla/ClassMate/releases/latest/download"
    private val body = """
        {"versionCode":7,"versionName":"1.1.6","minSupportedVersionCode":6,
         "apkUrl":"https://github.com/shuaibuddowla/ClassMate/releases/download/v1.1.6/classmate-1.1.6.apk",
         "apkSize":1024,"sha256":"${"a".repeat(64)}","mandatory":false,
         "releaseDate":"2026-10-02T00:00:00Z","releaseNotes":["Fixes"]}
    """.trimIndent()

    @Test fun versionCodeIsAuthoritative() {
        assertFalse(UpdatePolicy.isNewer(6, 6))
        assertFalse(UpdatePolicy.isNewer(5, 6))
        assertTrue(UpdatePolicy.isNewer(7, 6))
    }

    @Test fun downloadRequiresUserConsentAndRespectsWifi() {
        assertFalse(UpdatePolicy.mayAutoDownload(false, false, false))
        assertFalse(UpdatePolicy.mayAutoDownload(false, false, true))
        assertFalse(UpdatePolicy.mayAutoDownload(false, true, false))
        assertFalse(UpdatePolicy.mayAutoDownload(false, true, true))
        assertFalse(UpdatePolicy.mayAutoDownload(true, true, true))
        assertTrue(UpdatePolicy.mayAutoDownload(true, true, false))
        assertTrue(UpdatePolicy.mayAutoDownload(true, false, true))
    }

    @Test fun metadataParsesAndMandatoryFloorWorks() {
        val metadata = UpdateMetadata.parse(body, base)
        assertEquals(7, metadata.versionCode)
        assertFalse(metadata.isMandatoryFor(6))
        assertTrue(metadata.isMandatoryFor(5))
    }

    @Test fun rejectsUntrustedArtifactUrls() {
        listOf("http://github.com/shuaibuddowla/ClassMate/releases/download/v1.1.6/classmate-1.1.6.apk",
            "https://evil.example.com/shuaibuddowla/ClassMate/releases/download/v1.1.6/classmate-1.1.6.apk",
            "https://github.com/another/ClassMate/releases/download/v1.1.6/classmate-1.1.6.apk",
            "https://github.com/shuaibuddowla/ClassMate/releases/download/v1.1.5/classmate-1.1.6.apk"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                UpdateMetadata.parse(body.replace(
                    "https://github.com/shuaibuddowla/ClassMate/releases/download/v1.1.6/classmate-1.1.6.apk", url), base)
            }
        }
    }

    @Test fun rejectsInvalidHashSizeAndVersion() {
        listOf(body.replace("${"a".repeat(64)}", "abcd"),
            body.replace("\"apkSize\":1024", "\"apkSize\":0"),
            body.replace("\"versionCode\":7", "\"versionCode\":0")
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                UpdateMetadata.parse(invalid, base)
            }
        }
    }
}
