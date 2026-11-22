package com.shuaib.classmate.notices

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class NoticeCachedPayload(
    val notices: List<JSONObject>,
    val authors: Map<String, JSONObject>,
    val states: Map<String, JSONObject>,
    val reads: Map<String, JSONObject>,
    val previews: Map<String, List<JSONObject>>,
    val polls: Map<String, JSONObject>,
    val attachments: Map<String, List<JSONObject>>,
    val translations: Map<String, JSONObject>,
    val hasMore: Boolean
)

object NoticeCacheStore {

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getCacheFile(cacheDir: File, userId: String, batchId: String): File {
        val safeUser = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeBatch = batchId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cacheDir, "notices_cache_${safeUser}_${safeBatch}.json")
    }

    suspend fun saveCache(cacheDir: File, userId: String, batchId: String, payload: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank() || batchId.isBlank() || payload.isBlank()) return@withContext
        try {
            val targetFile = getCacheFile(cacheDir, userId, batchId)
            val tempFile = File(cacheDir, "${targetFile.name}.tmp")

            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
            }
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }
        } catch (e: Exception) {
            // Safe fallback: never crash on disk I/O errors
        }
    }

    suspend fun readCache(cacheDir: File, userId: String, batchId: String): String? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || batchId.isBlank()) return@withContext null
        try {
            val targetFile = getCacheFile(cacheDir, userId, batchId)
            if (!targetFile.exists() || !targetFile.canRead()) return@withContext null
            targetFile.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clearUserCache(cacheDir: File, userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val safeUser = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val prefix = "notices_cache_${safeUser}_"
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && file.name.endsWith(".json")) {
                file.delete()
            }
        }
    }

    fun saveCache(
        context: Context,
        userId: String,
        batchId: String,
        notices: List<JSONObject>,
        authors: Map<String, JSONObject>,
        states: Map<String, JSONObject>,
        reads: Map<String, JSONObject>,
        previews: Map<String, List<JSONObject>>,
        polls: Map<String, JSONObject>,
        attachments: Map<String, List<JSONObject>>,
        translations: Map<String, JSONObject>,
        hasMore: Boolean = false
    ) {
        cacheScope.launch {
            try {
                fun mapJson(map: Map<String, JSONObject>) = JSONObject().apply {
                    map.forEach { (id, value) -> put(id, value) }
                }
                val previewsObj = JSONObject().apply {
                    previews.forEach { (id, list) -> put(id, JSONArray(list)) }
                }
                val attachmentsObj = JSONObject().apply {
                    attachments.forEach { (id, list) -> put(id, JSONArray(list)) }
                }

                val cacheObj = JSONObject()
                    .put("feed", JSONArray(notices))
                    .put("has_more", hasMore)
                    .put("states", mapJson(states))
                    .put("authors", mapJson(authors))
                    .put("reads", mapJson(reads))
                    .put("polls", mapJson(polls))
                    .put("attachments", attachmentsObj)
                    .put("previews", previewsObj)
                    .put("translations", mapJson(translations))
                    .put("saved_at", System.currentTimeMillis())

                saveCache(context.filesDir, userId, batchId, cacheObj.toString())
            } catch (e: Exception) {
                // Ignore background caching errors safely
            }
        }
    }

    fun readCache(context: Context, userId: String, batchId: String): NoticeCachedPayload? {
        val targetFile = getCacheFile(context.filesDir, userId, batchId)
        if (!targetFile.exists() || !targetFile.canRead()) return null
        return try {
            val content = targetFile.readText(StandardCharsets.UTF_8)
            val json = JSONObject(content)

            fun parseJsonList(arr: JSONArray?): List<JSONObject> {
                if (arr == null) return emptyList()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(it) }
                }
                return list
            }

            fun parseJsonMap(obj: JSONObject?): Map<String, JSONObject> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, JSONObject>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    obj.optJSONObject(k)?.let { map[k] = it }
                }
                return map
            }

            fun parseJsonListMap(obj: JSONObject?): Map<String, List<JSONObject>> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, List<JSONObject>>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = parseJsonList(obj.optJSONArray(k))
                }
                return map
            }

            val feed = parseJsonList(json.optJSONArray("feed"))
            if (feed.isEmpty()) return null

            NoticeCachedPayload(
                notices = feed,
                authors = parseJsonMap(json.optJSONObject("authors")),
                states = parseJsonMap(json.optJSONObject("states")),
                reads = parseJsonMap(json.optJSONObject("reads")),
                previews = parseJsonListMap(json.optJSONObject("previews")),
                polls = parseJsonMap(json.optJSONObject("polls")),
                attachments = parseJsonListMap(json.optJSONObject("attachments")),
                translations = parseJsonMap(json.optJSONObject("translations")),
                hasMore = json.optBoolean("has_more", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}
