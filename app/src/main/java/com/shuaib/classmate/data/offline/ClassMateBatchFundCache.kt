package com.shuaib.classmate.data.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ClassMateBatchFundCache {
    private const val PREFS_NAME = "cm_batch_fund_cache"

    private const val KEY_ROSTER_PREFIX = "roster_"
    private const val KEY_SUMMARY_PREFIX = "summary_"
    private const val KEY_COLLECTIONS_PREFIX = "collections_"
    private const val KEY_TRANSACTIONS_PREFIX = "txs_"
    private const val KEY_PAYERS_PREFIX = "payers_"

    fun saveRoster(context: Context, batchId: String, roster: List<JSONObject>) {
        if (batchId.isBlank() || roster.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        roster.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ROSTER_PREFIX + batchId, arr.toString()).apply()
    }

    fun getRoster(context: Context, batchId: String): List<JSONObject> {
        if (batchId.isBlank()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ROSTER_PREFIX + batchId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSummary(context: Context, batchId: String, summary: JSONObject) {
        if (batchId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SUMMARY_PREFIX + batchId, summary.toString()).apply()
    }

    fun getSummary(context: Context, batchId: String): JSONObject? {
        if (batchId.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SUMMARY_PREFIX + batchId, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveCollections(context: Context, batchId: String, collections: JSONArray) {
        if (batchId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COLLECTIONS_PREFIX + batchId, collections.toString()).apply()
    }

    fun getCollections(context: Context, batchId: String): JSONArray? {
        if (batchId.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_COLLECTIONS_PREFIX + batchId, null) ?: return null
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveTransactions(context: Context, batchId: String, transactions: JSONArray) {
        if (batchId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TRANSACTIONS_PREFIX + batchId, transactions.toString()).apply()
    }

    fun getTransactions(context: Context, batchId: String): JSONArray? {
        if (batchId.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TRANSACTIONS_PREFIX + batchId, null) ?: return null
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun savePayers(context: Context, batchId: String, collectionId: String, payers: JSONArray) {
        if (batchId.isBlank() || collectionId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("${KEY_PAYERS_PREFIX}${batchId}_${collectionId}", payers.toString()).apply()
    }

    fun getPayers(context: Context, batchId: String, collectionId: String): JSONArray? {
        if (batchId.isBlank() || collectionId.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("${KEY_PAYERS_PREFIX}${batchId}_${collectionId}", null) ?: return null
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }
    }
}
