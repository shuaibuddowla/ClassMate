package com.shuaib.classmate.data.offline

import android.content.Context
import androidx.work.*
import com.shuaib.classmate.workers.OfflineFundSyncWorker
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object OfflineFundQueue {
    private const val PREFS_NAME = "cm_offline_fund_queue"
    private const val KEY_QUEUE = "pending_fund_transactions_queue"

    @Synchronized
    fun enqueue(context: Context, tx: PendingFundTransaction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getAll(context).toMutableList()
        list.removeAll { it.id == tx.id }
        list.add(tx)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()

        scheduleSync(context)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getAll(context).filter { it.id != id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    @Synchronized
    fun getAll(context: Context): List<PendingFundTransaction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PendingFundTransaction>()
            for (i in 0 until arr.length()) {
                list.add(PendingFundTransaction.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getForBatch(context: Context, batchId: String): List<PendingFundTransaction> {
        return getAll(context).filter { it.batchId == batchId }
    }

    @Synchronized
    fun count(context: Context, batchId: String? = null): Int {
        val all = getAll(context)
        return if (batchId.isNullOrBlank()) all.size else all.count { it.batchId == batchId }
    }

    fun scheduleSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = OneTimeWorkRequestBuilder<OfflineFundSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag("offline_fund_sync")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            OfflineFundSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncWork
        )
    }
}
