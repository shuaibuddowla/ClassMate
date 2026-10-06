package com.shuaib.classmate.update

import com.google.gson.JsonParser
import java.net.URI
import java.time.Instant

data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val mandatory: Boolean,
    val releaseDate: String,
    val releaseNotes: List<String>,
) {
    fun isMandatoryFor(installedCode: Long) = mandatory || installedCode < minSupportedVersionCode

    companion object {
        fun parse(json: String, baseUrl: String): UpdateMetadata {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.get("versionCode").asLong
            val name = root.get("versionName").asString.trim()
            val min = root.get("minSupportedVersionCode").asLong
            val url = root.get("apkUrl").asString
            val size = root.get("apkSize").asLong
            val hash = root.get("sha256").asString.lowercase()
            val required = root.get("mandatory").asBoolean
            val date = root.get("releaseDate").asString
            val notes = root.getAsJsonArray("releaseNotes").map { it.asString.trim() }
            require(code > 0 && min >= 0 && min <= code) { "Invalid version code" }
            require(name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid version name" }
            require(size in 1..1_000_000_000) { "Invalid APK size" }
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
            require(notes.size <= 20 && notes.all { it.length <= 500 }) { "Invalid release notes" }
            Instant.parse(date)
            validateArtifactUrl(url, baseUrl, name)
            return UpdateMetadata(code, name, min, url, size, hash, required, date, notes)
        }

        fun validateArtifactUrl(url: String, baseUrl: String, versionName: String) {
            val base = URI(baseUrl.trimEnd('/'))
            val artifact = URI(url)
            require(base.scheme == "https" && artifact.scheme == "https") { "Updates require HTTPS" }
            val latestSuffix = "/releases/latest/download"
            require(base.host.equals("github.com", ignoreCase = true) &&
                base.rawPath.endsWith(latestSuffix) && base.userInfo == null &&
                base.query == null && base.fragment == null && base.port == -1) {
                "Invalid GitHub release source"
            }
            val repositoryPath = base.rawPath.removeSuffix(latestSuffix)
            require(repositoryPath.matches(Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
                "Invalid GitHub repository path"
            }
            val expected = "https://github.com$repositoryPath/releases/download/v$versionName/classmate-$versionName.apk"
            require(url == expected && artifact.userInfo == null && artifact.query == null &&
                artifact.fragment == null && artifact.port == -1) {
                "APK URL is outside the trusted GitHub release"
            }
        }
    }
}

object UpdatePolicy {
    fun mayInstall(userConfirmed: Boolean) = userConfirmed
    fun isNewer(serverCode: Long, installedCode: Long) = serverCode > installedCode
    fun mayAutoDownload(enabled: Boolean, wifiOnly: Boolean, isMetered: Boolean) =
        enabled && (!wifiOnly || !isMetered)
}
