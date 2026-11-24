package com.shuaib.classmate.activities

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.shuaib.classmate.data.offline.ClassMateBatchFundCache
import com.shuaib.classmate.data.offline.OfflineFundQueue
import com.shuaib.classmate.data.offline.PendingFundTransaction
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.fund.*
import com.shuaib.classmate.utils.HapticHelper
import com.shuaib.classmate.utils.NetworkMonitor
import com.shuaib.classmate.workers.OfflineFundSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ClassMateBatchFundActivity : AppCompatActivity() {

    private var batchId: String = ""
    private var batchLabel: String = ""
    private var currentUserId: String = ""

    private val summaryState = mutableStateOf(FundSummary())
    private val campaignsState = mutableStateListOf<FundCampaignItem>()
    private val transactionsState = mutableStateListOf<FundTransactionItem>()
    private val studentsState = mutableStateListOf<FundStudentDue>()
    private val undoActionState = mutableStateOf<UndoAction?>(null)

    private var rawRosterList: List<JSONObject> = emptyList()
    private var rawCollectionsList: List<JSONObject> = emptyList()
    private var undoJob: Job? = null

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OfflineFundSyncWorker.ACTION_FUND_SYNCED) {
                loadData()
                loadBatchRoster()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            ContextCompat.registerReceiver(
                this,
                syncReceiver,
                IntentFilter(OfflineFundSyncWorker.ACTION_FUND_SYNCED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(syncReceiver)
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClassMateAuthApi.attach(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        batchId = intent.getStringExtra("batch_id").orEmpty()
        batchLabel = intent.getStringExtra("batch_label").orEmpty()

        setContent {
            ClassMateBatchFundScreen(
                summary = summaryState.value,
                campaigns = campaignsState,
                transactions = transactionsState,
                students = studentsState,
                currentUserId = currentUserId,
                onBackClick = { finish() },
                onShareReport = { shareStatement() },
                onUpdatePurpose = { newDesc -> updateFundPurpose(newDesc) },
                onCreateCampaign = { name, perStudent -> createCampaign(name, perStudent) },
                onQuickCollectBatch = { studentIds, campaignName, method, amountPerStudent ->
                    performBatchCollect(studentIds, campaignName, method, amountPerStudent)
                },
                onDirectCollectSingle = { student, campaignName, perStudent ->
                    performSingleCollect(student, campaignName, perStudent)
                },
                onToggleStudentUnpaid = { student, campaignName ->
                    toggleStudentUnpaid(student, campaignName)
                },
                onSaveTransaction = { isDeposit, name, sId, pId, amt, method, reason ->
                    saveManualTransaction(isDeposit, name, sId, pId, amt, method, reason)
                },
                onDeleteTransaction = { tx ->
                    deleteTransaction(tx)
                },
                onSendReminderNotification = { campaignName, unpaidProfileIds ->
                    sendPushReminders(campaignName, unpaidProfileIds)
                },
                onRefreshData = {
                    loadData()
                    loadBatchRoster()
                },
                undoAction = undoActionState.value,
                onDismissUndo = { undoActionState.value = null }
            )
        }

        lifecycleScope.launch {
            val hasSavedSession = ClassMateAuthApi.hasSavedSession() || !ClassMateAuthApi.accessToken.isNullOrBlank()
            if (!hasSavedSession) {
                Toast.makeText(this@ClassMateBatchFundActivity, "Please sign in to access Batch Fund", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            if (NetworkMonitor.isOnline(this@ClassMateBatchFundActivity)) {
                runCatching { ClassMateAuthApi.restoreSession() }
            }

            val cachedProfile = ClassMateAcademicCache.home(this@ClassMateBatchFundActivity)?.optJSONObject("profile")
            currentUserId = cachedProfile?.optString("student_id").orEmpty().ifBlank {
                cachedProfile?.optString("id").orEmpty()
            }

            if (batchId.isBlank()) {
                val cachedHome = ClassMateAcademicCache.home(this@ClassMateBatchFundActivity)
                batchId = cachedHome?.optString("batch").orEmpty().ifBlank {
                    cachedHome?.optJSONObject("profile")?.optString("batch_id").orEmpty()
                }.ifBlank {
                    com.shuaib.classmate.utils.AppContextManager.getManagedBatchId().ifBlank {
                        com.shuaib.classmate.utils.AppContextManager.getBatchId()
                    }
                }
                if (batchLabel.isBlank()) {
                    batchLabel = cachedHome?.optString("label").orEmpty()
                }
            }

            if (batchId.isBlank() && NetworkMonitor.isOnline(this@ClassMateBatchFundActivity)) {
                runCatching {
                    val prof = ClassMateAuthApi.initializeProfile()
                    batchId = prof.optString("batch_id").ifBlank { prof.optString("effective_batch_id") }
                }
            }

            if (batchId.isBlank()) {
                Toast.makeText(this@ClassMateBatchFundActivity, "No active batch associated with your profile.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // Pre-seed CR capabilities
            if (cachedProfile != null) {
                val isCrOrAdmin = cachedProfile.optBoolean("is_cr", false) ||
                        cachedProfile.optBoolean("is_admin", false) ||
                        cachedProfile.optString("role") in setOf("admin", "cr")
                if (isCrOrAdmin) {
                    summaryState.value = summaryState.value.copy(isCr = true)
                }
            }

            loadData()
            loadBatchRoster()
        }
    }

    private fun loadBatchRoster() {
        if (batchId.isBlank()) return
        val cached = ClassMateBatchFundCache.getRoster(this, batchId)
        if (cached.isNotEmpty()) {
            rawRosterList = cached
            rebuildStudentsList()
        }
        if (!NetworkMonitor.isOnline(this)) return
        lifecycleScope.launch {
            try {
                val res = ClassMateAuthApi.rpcText(
                    "batch_friends",
                    JSONObject().apply {
                        put("target_batch", batchId)
                        put("query_text", "")
                        put("result_offset", 0)
                    }
                )
                val arr = JSONArray(res)
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    if (p.optBoolean("is_student", p.optString("role") != "teacher")) {
                        list.add(p)
                    }
                }
                if (list.isNotEmpty()) {
                    rawRosterList = list
                    ClassMateBatchFundCache.saveRoster(this@ClassMateBatchFundActivity, batchId, list)
                    rebuildStudentsList()
                }
            } catch (_: Exception) {}
        }
    }

    private fun applySummaryData(summary: JSONObject) {
        var bal = summary.optDouble("current_balance", 0.0)
        var coll = summary.optDouble("total_collected", 0.0)
        var spent = summary.optDouble("total_spent", 0.0)
        var count = summary.optInt("transaction_count", 0)

        // Optimistic adjustments from offline pending transactions
        val pendingTxs = OfflineFundQueue.getForBatch(this, batchId)
        for (ptx in pendingTxs) {
            if (ptx.type == "inflow") {
                bal += ptx.amount
                coll += ptx.amount
            } else {
                bal -= ptx.amount
                spent += ptx.amount
            }
            count++
        }

        val isCr = summary.optBoolean("is_cr", summaryState.value.isCr)
        val fundDesc = summary.optString("fund_description", "Used for emergency, events, jersey, and other batch expenses.")
        val bName = summary.optString("batch_name", batchLabel.ifBlank { "Batch Fund" })
        val uni = summary.optString("university", "MBSTU")

        val ongoingTitle = summary.optString("ongoing_title", "").trim()
        val ongoingCanon = summary.optString("ongoing_canon", "").trim()
        val ongoingCollected = summary.optDouble("ongoing_collected", 0.0)
        val ongoingPaidCount = summary.optInt("ongoing_paid_count", 0)
        val ongoingUnpaidCount = summary.optInt("ongoing_unpaid_count", 0)
        val ongoingProgress = summary.optDouble("ongoing_progress", 0.0)

        // Compute Cash vs MFS breakdown from transactions if available, otherwise 60/40 split
        var cashSum = 0.0
        var mfsSum = 0.0
        var hasMethods = false
        for (tx in transactionsState) {
            val amt = if (tx.type == "inflow") tx.amount else -tx.amount
            if (tx.method.equals("bKash", ignoreCase = true) || tx.method.equals("Nagad", ignoreCase = true)) {
                mfsSum += amt
                hasMethods = true
            } else {
                cashSum += amt
                if (tx.method.isNotBlank()) hasMethods = true
            }
        }
        if (!hasMethods || (cashSum <= 0 && mfsSum <= 0 && bal > 0)) {
            cashSum = Math.round(bal * 0.63).toDouble()
            mfsSum = bal - cashSum
        }

        summaryState.value = FundSummary(
            currentBalance = bal,
            totalCollected = coll,
            totalSpent = spent,
            cashBalance = cashSum.coerceAtLeast(0.0),
            mfsBalance = mfsSum.coerceAtLeast(0.0),
            txCount = count,
            isCr = isCr,
            fundDescription = fundDesc,
            batchName = bName,
            university = uni,
            ongoingTitle = ongoingTitle,
            ongoingCanon = ongoingCanon,
            ongoingCollected = ongoingCollected,
            ongoingPaidCount = ongoingPaidCount,
            ongoingUnpaidCount = ongoingUnpaidCount,
            ongoingProgress = ongoingProgress
        )
    }

    private fun loadData() {
        if (batchId.isBlank()) return
        val cachedSummary = ClassMateBatchFundCache.getSummary(this, batchId)
        if (cachedSummary != null) {
            applySummaryData(cachedSummary)
        }

        lifecycleScope.launch {
            try {
                loadCollections()
                loadTransactions()

                if (NetworkMonitor.isOnline(this@ClassMateBatchFundActivity)) {
                    val summary = ClassMateAuthApi.rpc(
                        "batch_fund_summary",
                        JSONObject().put("target_batch", batchId)
                    )
                    ClassMateBatchFundCache.saveSummary(this@ClassMateBatchFundActivity, batchId, summary)
                    applySummaryData(summary)
                }
            } catch (e: Exception) {
                // Ignore or keep cached
            }
        }
    }

    private suspend fun loadCollections() {
        val cachedCols = ClassMateBatchFundCache.getCollections(this, batchId)
        if (cachedCols != null && cachedCols.length() > 0) {
            populateCampaigns(cachedCols)
        }

        if (NetworkMonitor.isOnline(this)) {
            try {
                val colResponse = ClassMateAuthApi.rpcText(
                    "batch_fund_collections",
                    JSONObject().put("target_batch", batchId)
                )
                val colArray = JSONArray(colResponse)
                ClassMateBatchFundCache.saveCollections(this, batchId, colArray)
                populateCampaigns(colArray)
            } catch (_: Exception) {}
        }
    }

    private fun populateCampaigns(colArray: JSONArray) {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until colArray.length()) {
            val item = colArray.getJSONObject(i)
            if (item.optString("title").trim().isNotBlank()) {
                list.add(item)
            }
        }
        rawCollectionsList = list

        val colors = listOf(0xFF6366F1, 0xFF10B981, 0xFF8B5CF6, 0xFFF59E0B, 0xFFEC4899)
        val items = list.mapIndexed { idx, obj ->
            val title = obj.optString("title").trim()
            val id = title.lowercase().replace(" ", "_")
            val coll = obj.optDouble("total_amount", obj.optDouble("total_collected", 0.0))
            val paid = obj.optInt("payment_count", obj.optInt("paid_count", 0))
            val total = rawRosterList.size.coerceAtLeast(58)
            val perStudent = if (paid > 0 && coll > 0) Math.round(coll / paid).toDouble() else 500.0
            FundCampaignItem(
                id = id,
                name = title,
                paid = paid,
                total = total,
                collected = coll,
                perStudent = if (perStudent > 0) perStudent else 500.0,
                colorHex = colors[idx % colors.size]
            )
        }.toMutableList()

        if (items.isEmpty()) {
            items.add(
                FundCampaignItem(
                    id = "general",
                    name = summaryState.value.ongoingTitle.ifBlank { "Monthly Fund" },
                    paid = summaryState.value.ongoingPaidCount,
                    total = (summaryState.value.ongoingPaidCount + summaryState.value.ongoingUnpaidCount).coerceAtLeast(58),
                    collected = summaryState.value.ongoingCollected.coerceAtLeast(summaryState.value.totalCollected),
                    perStudent = 500.0,
                    colorHex = 0xFF6366F1
                )
            )
        }

        campaignsState.clear()
        campaignsState.addAll(items)
        rebuildStudentsList()
    }

    private suspend fun loadTransactions() {
        val cachedTxs = ClassMateBatchFundCache.getTransactions(this, batchId)
        val pendingTxs = OfflineFundQueue.getForBatch(this, batchId)

        fun parseTxArray(array: JSONArray?) {
            val list = mutableListOf<FundTransactionItem>()
            // First add offline pending transactions
            pendingTxs.forEach { p ->
                list.add(
                    FundTransactionItem(
                        id = p.id,
                        type = p.type,
                        method = "Cash",
                        title = p.studentName?.ifBlank { null } ?: p.title,
                        studentName = p.studentName,
                        studentId = p.studentId,
                        studentProfileId = p.studentProfileId,
                        campaign = p.title,
                        formattedDate = "Today",
                        createdByName = "You",
                        amount = p.amount,
                        isOfflinePending = true,
                        canEdit = true
                    )
                )
            }

            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id")
                    val isPending = obj.optBoolean("is_offline_pending", false)
                    val type = obj.optString("type", "inflow")
                    val amt = obj.optDouble("amount", 0.0)
                    val title = obj.optString("title")
                    val sName = obj.optString("student_name").takeIf { it.isNotBlank() && it != "null" }
                    val sId = obj.optString("student_id").takeIf { it.isNotBlank() && it != "null" }
                    val sPid = obj.optString("student_profile_id").takeIf { it.isNotBlank() && it != "null" }
                    val dateStr = obj.optString("transacted_at")
                    val cName = obj.optString("created_by_name").takeIf { it.isNotBlank() && it != "null" }
                    val canEdit = if (isPending) summaryState.value.isCr else obj.optBoolean("can_edit", summaryState.value.isCr)

                    // Deduce method from title or tags if available, else default
                    val method = if (title.contains("bkash", ignoreCase = true)) "bKash"
                    else if (title.contains("nagad", ignoreCase = true)) "Nagad"
                    else "Cash"

                    val displayTitle = sName ?: title
                    val campTag = if (sName != null && title.isNotBlank() && !title.equals(sName, ignoreCase = true)) title else "Batch Fund"

                    list.add(
                        FundTransactionItem(
                            id = id,
                            type = type,
                            method = method,
                            title = displayTitle,
                            studentName = sName,
                            studentId = sId,
                            studentProfileId = sPid,
                            campaign = campTag,
                            dateStr = dateStr,
                            formattedDate = formatTransactionDate(dateStr),
                            createdByName = cName?.trim()?.split(" ")?.firstOrNull() ?: "CR",
                            createdAt = obj.optString("created_at"),
                            amount = amt,
                            isOfflinePending = isPending,
                            canEdit = canEdit,
                            rawJson = obj
                        )
                    )
                }
            }

            transactionsState.clear()
            transactionsState.addAll(list)
            rebuildStudentsList()
        }

        if (cachedTxs != null || pendingTxs.isNotEmpty()) {
            parseTxArray(cachedTxs)
        }

        if (NetworkMonitor.isOnline(this)) {
            try {
                val responseText = ClassMateAuthApi.rpcText(
                    "batch_fund_transactions",
                    JSONObject().apply {
                        put("target_batch", batchId)
                        put("filter_type", "all")
                        put("query_text", "")
                        put("result_offset", 0)
                        put("page_limit", 100)
                    }
                )
                val txArray = JSONArray(responseText)
                ClassMateBatchFundCache.saveTransactions(this, batchId, txArray)
                parseTxArray(txArray)
            } catch (_: Exception) {}
        }
    }

    private fun rebuildStudentsList() {
        if (rawRosterList.isEmpty()) return
        val list = mutableListOf<FundStudentDue>()

        // Map transactions per student and per campaign
        val paidMapByStudent = mutableMapOf<String, MutableMap<String, Boolean>>()
        val totalPaidByStudent = mutableMapOf<String, Double>()
        val countByStudent = mutableMapOf<String, Int>()

        for (tx in transactionsState) {
            if (tx.type == "inflow") {
                val sKey = tx.studentId?.lowercase()?.trim()
                val nKey = tx.studentName?.lowercase()?.trim()
                val campId = tx.campaign.lowercase().replace(" ", "_").trim()

                if (!sKey.isNullOrBlank()) {
                    paidMapByStudent.getOrPut(sKey) { mutableMapOf() }[campId] = true
                    totalPaidByStudent[sKey] = (totalPaidByStudent[sKey] ?: 0.0) + tx.amount
                    countByStudent[sKey] = (countByStudent[sKey] ?: 0) + 1
                }
                if (!nKey.isNullOrBlank()) {
                    paidMapByStudent.getOrPut(nKey) { mutableMapOf() }[campId] = true
                    totalPaidByStudent[nKey] = (totalPaidByStudent[nKey] ?: 0.0) + tx.amount
                    countByStudent[nKey] = (countByStudent[nKey] ?: 0) + 1
                }
            }
        }

        for (student in rawRosterList) {
            val name = student.optString("full_name").trim()
            val sid = student.optString("student_id").trim().takeIf { it.isNotBlank() && it != "null" } ?: ""
            val pid = student.optString("id").ifBlank { student.optString("profile_id") }.trim().takeIf { it.isNotBlank() && it != "null" }
            val sidKey = sid.lowercase().trim()
            val nameKey = name.lowercase().trim()
            val pMap = (paidMapByStudent[sidKey] ?: emptyMap()) + (paidMapByStudent[nameKey] ?: emptyMap())
            val totalPaid = (totalPaidByStudent[sidKey] ?: 0.0).coerceAtLeast(totalPaidByStudent[nameKey] ?: 0.0)
            val count = (countByStudent[sidKey] ?: 0).coerceAtLeast(countByStudent[nameKey] ?: 0)

            list.add(
                FundStudentDue(
                    studentId = sid,
                    profileId = pid,
                    fullName = name,
                    paidMap = pMap,
                    totalPaid = totalPaid,
                    paymentCount = count
                )
            )
        }

        studentsState.clear()
        studentsState.addAll(list)
    }

    private fun formatTransactionDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank() || dateStr == "null") return "Recent"
        return try {
            val raw = dateStr.trim().take(10)
            val parsed = LocalDate.parse(raw)
            val today = LocalDate.now()
            when (parsed) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> parsed.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
            }
        } catch (_: Exception) {
            dateStr
        }
    }

    // ACTIONS: SINGLE COLLECT, BATCH COLLECT, UNDO
    private fun performSingleCollect(student: FundStudentDue, campaignName: String, perStudent: Double) {
        val campId = campaignName.lowercase().replace(" ", "_")
        val txId = "tx_" + System.currentTimeMillis()

        // 1. Optimistic update
        val updatedMap = student.paidMap.toMutableMap().apply { put(campId, true) }
        val sIdx = studentsState.indexOfFirst { it.studentId == student.studentId }
        if (sIdx >= 0) {
            studentsState[sIdx] = student.copy(paidMap = updatedMap, totalPaid = student.totalPaid + perStudent, paymentCount = student.paymentCount + 1)
        }

        val newTx = FundTransactionItem(
            id = txId,
            type = "inflow",
            method = "Cash",
            title = student.fullName,
            studentName = student.fullName,
            studentId = student.studentId,
            studentProfileId = student.profileId,
            campaign = campaignName,
            formattedDate = "Today",
            createdByName = "You",
            amount = perStudent,
            isOfflinePending = !NetworkMonitor.isOnline(this),
            canEdit = true
        )
        transactionsState.add(0, newTx)

        val newBal = summaryState.value.currentBalance + perStudent
        summaryState.value = summaryState.value.copy(
            currentBalance = newBal,
            totalCollected = summaryState.value.totalCollected + perStudent,
            cashBalance = summaryState.value.cashBalance + perStudent,
            txCount = summaryState.value.txCount + 1
        )

        // 2. Persist
        saveContributionToServerOrOffline(
            isDeposit = true,
            name = student.fullName,
            studentId = student.studentId,
            studentProfileId = student.profileId,
            amount = perStudent,
            method = "Cash",
            title = campaignName
        )

        // 3. Show forgiving undo
        HapticHelper.success(this)
        showUndoToast("Collected ৳ ${perStudent.toInt()} (${student.studentId})") {
            // Undo action
            if (sIdx >= 0) {
                val revertedMap = student.paidMap.toMutableMap().apply { put(campId, false) }
                studentsState[sIdx] = student.copy(paidMap = revertedMap, totalPaid = Math.max(0.0, student.totalPaid - perStudent), paymentCount = Math.max(0, student.paymentCount - 1))
            }
            transactionsState.removeAll { it.id == txId }
            summaryState.value = summaryState.value.copy(
                currentBalance = Math.max(0.0, summaryState.value.currentBalance - perStudent),
                totalCollected = Math.max(0.0, summaryState.value.totalCollected - perStudent),
                cashBalance = Math.max(0.0, summaryState.value.cashBalance - perStudent),
                txCount = Math.max(0, summaryState.value.txCount - 1)
            )
        }
    }

    private fun performBatchCollect(studentIds: List<String>, campaignName: String, method: String, amountPerStudent: Double) {
        val campId = campaignName.lowercase().replace(" ", "_")
        val addedTxIds = mutableListOf<String>()
        val collectedStudents = mutableListOf<FundStudentDue>()

        for (sid in studentIds) {
            val student = studentsState.firstOrNull { it.studentId == sid } ?: continue
            collectedStudents.add(student)
            val txId = "tx_" + System.currentTimeMillis() + "_" + sid
            addedTxIds.add(txId)

            val updatedMap = student.paidMap.toMutableMap().apply { put(campId, true) }
            val sIdx = studentsState.indexOfFirst { it.studentId == sid }
            if (sIdx >= 0) {
                studentsState[sIdx] = student.copy(paidMap = updatedMap, totalPaid = student.totalPaid + amountPerStudent, paymentCount = student.paymentCount + 1)
            }

            val newTx = FundTransactionItem(
                id = txId,
                type = "inflow",
                method = method,
                title = student.fullName,
                studentName = student.fullName,
                studentId = student.studentId,
                studentProfileId = student.profileId,
                campaign = campaignName,
                formattedDate = "Today",
                createdByName = "You",
                amount = amountPerStudent,
                isOfflinePending = !NetworkMonitor.isOnline(this),
                canEdit = true
            )
            transactionsState.add(0, newTx)

            saveContributionToServerOrOffline(
                isDeposit = true,
                name = student.fullName,
                studentId = student.studentId,
                studentProfileId = student.profileId,
                amount = amountPerStudent,
                method = method,
                title = campaignName
            )
        }

        val totalAmt = studentIds.size * amountPerStudent
        val newBal = summaryState.value.currentBalance + totalAmt
        summaryState.value = summaryState.value.copy(
            currentBalance = newBal,
            totalCollected = summaryState.value.totalCollected + totalAmt,
            cashBalance = if (method == "Cash") summaryState.value.cashBalance + totalAmt else summaryState.value.cashBalance,
            mfsBalance = if (method != "Cash") summaryState.value.mfsBalance + totalAmt else summaryState.value.mfsBalance,
            txCount = summaryState.value.txCount + studentIds.size
        )

        HapticHelper.success(this)
        showUndoToast("Collected ${collectedStudents.size} via $method (+৳ ${totalAmt.toInt()})") {
            for (st in collectedStudents) {
                val sIdx = studentsState.indexOfFirst { it.studentId == st.studentId }
                if (sIdx >= 0) {
                    val revertedMap = st.paidMap.toMutableMap().apply { put(campId, false) }
                    studentsState[sIdx] = st.copy(paidMap = revertedMap, totalPaid = Math.max(0.0, st.totalPaid - amountPerStudent), paymentCount = Math.max(0, st.paymentCount - 1))
                }
            }
            transactionsState.removeAll { it.id in addedTxIds }
            summaryState.value = summaryState.value.copy(
                currentBalance = Math.max(0.0, summaryState.value.currentBalance - totalAmt),
                totalCollected = Math.max(0.0, summaryState.value.totalCollected - totalAmt),
                cashBalance = if (method == "Cash") Math.max(0.0, summaryState.value.cashBalance - totalAmt) else summaryState.value.cashBalance,
                mfsBalance = if (method != "Cash") Math.max(0.0, summaryState.value.mfsBalance - totalAmt) else summaryState.value.mfsBalance,
                txCount = Math.max(0, summaryState.value.txCount - collectedStudents.size)
            )
        }
    }

    private fun toggleStudentUnpaid(student: FundStudentDue, campaignName: String) {
        val campId = campaignName.lowercase().replace(" ", "_")
        val sIdx = studentsState.indexOfFirst { it.studentId == student.studentId }
        if (sIdx >= 0) {
            val updatedMap = student.paidMap.toMutableMap().apply { put(campId, false) }
            studentsState[sIdx] = student.copy(paidMap = updatedMap)
            Toast.makeText(this, "Marked ${student.fullName} as Unpaid", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveManualTransaction(
        isDeposit: Boolean,
        name: String,
        studentId: String?,
        studentProfileId: String?,
        amount: Double,
        method: String,
        reason: String
    ) {
        saveContributionToServerOrOffline(
            isDeposit = isDeposit,
            name = name,
            studentId = studentId,
            studentProfileId = studentProfileId,
            amount = amount,
            method = method,
            title = reason
        )
        val type = if (isDeposit) "inflow" else "outflow"
        val newTx = FundTransactionItem(
            id = "tx_" + System.currentTimeMillis(),
            type = type,
            method = method,
            title = name,
            studentName = if (isDeposit) name else null,
            studentId = studentId,
            studentProfileId = studentProfileId,
            campaign = reason,
            formattedDate = "Today",
            createdByName = "You",
            amount = amount,
            isOfflinePending = !NetworkMonitor.isOnline(this),
            canEdit = true
        )
        transactionsState.add(0, newTx)

        val delta = if (isDeposit) amount else -amount
        summaryState.value = summaryState.value.copy(
            currentBalance = (summaryState.value.currentBalance + delta).coerceAtLeast(0.0),
            totalCollected = if (isDeposit) summaryState.value.totalCollected + amount else summaryState.value.totalCollected,
            totalSpent = if (!isDeposit) summaryState.value.totalSpent + amount else summaryState.value.totalSpent,
            cashBalance = if (method == "Cash") (summaryState.value.cashBalance + delta).coerceAtLeast(0.0) else summaryState.value.cashBalance,
            mfsBalance = if (method != "Cash") (summaryState.value.mfsBalance + delta).coerceAtLeast(0.0) else summaryState.value.mfsBalance,
            txCount = summaryState.value.txCount + 1
        )

        HapticHelper.success(this)
        Toast.makeText(this, if (isDeposit) "Deposit recorded for $name" else "Expense recorded", Toast.LENGTH_SHORT).show()
    }

    private fun saveContributionToServerOrOffline(
        isDeposit: Boolean,
        name: String,
        studentId: String?,
        studentProfileId: String?,
        amount: Double,
        method: String,
        title: String
    ) {
        val type = if (isDeposit) "inflow" else "outflow"
        val todayStr = LocalDate.now().toString()

        if (!NetworkMonitor.isOnline(this)) {
            val pendingTx = PendingFundTransaction(
                batchId = batchId,
                type = type,
                amount = amount,
                title = title,
                studentName = if (isDeposit) name else null,
                studentId = studentId,
                studentProfileId = if (isDeposit) studentProfileId else null
            )
            OfflineFundQueue.enqueue(this, pendingTx)
            return
        }

        lifecycleScope.launch {
            try {
                ClassMateAuthApi.rpc(
                    "record_batch_fund_transaction",
                    JSONObject().apply {
                        put("target_batch", batchId)
                        put("trans_type", type)
                        put("trans_amount", amount)
                        put("trans_title", title)
                        put("trans_student_name", if (isDeposit) name else null)
                        put("trans_student_id", studentId)
                        put("trans_date", todayStr)
                        if (isDeposit && !studentProfileId.isNullOrBlank()) {
                            put("trans_student_profile_id", studentProfileId)
                        }
                    }
                )
            } catch (e: Exception) {
                val pendingTx = PendingFundTransaction(
                    batchId = batchId,
                    type = type,
                    amount = amount,
                    title = title,
                    studentName = if (isDeposit) name else null,
                    studentId = studentId,
                    studentProfileId = if (isDeposit) studentProfileId else null
                )
                OfflineFundQueue.enqueue(this@ClassMateBatchFundActivity, pendingTx)
            }
        }
    }

    private fun deleteTransaction(tx: FundTransactionItem) {
        if (tx.isOfflinePending) {
            val offlineId = tx.id.removePrefix("offline_")
            OfflineFundQueue.remove(this, offlineId)
            transactionsState.remove(tx)
            Toast.makeText(this, "Offline transaction removed", Toast.LENGTH_SHORT).show()
            loadData()
            return
        }

        lifecycleScope.launch {
            try {
                ClassMateAuthApi.rpc(
                    "delete_batch_fund_transaction",
                    JSONObject().apply {
                        put("trans_id", tx.id)
                        put("target_transaction", tx.id)
                    }
                )
                transactionsState.remove(tx)
                Toast.makeText(this@ClassMateBatchFundActivity, "Transaction deleted", Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchFundActivity, "Error deleting: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateFundPurpose(newDesc: String) {
        lifecycleScope.launch {
            try {
                ClassMateAuthApi.rpc(
                    "update_batch_fund_description",
                    JSONObject().apply {
                        put("target_batch", batchId)
                        put("new_description", newDesc)
                    }
                )
                summaryState.value = summaryState.value.copy(fundDescription = newDesc)
                Toast.makeText(this@ClassMateBatchFundActivity, "Fund purpose updated!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchFundActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createCampaign(name: String, perStudent: Double) {
        val id = name.lowercase().replace(" ", "_")
        val newCamp = FundCampaignItem(
            id = id,
            name = name,
            paid = 0,
            total = studentsState.size.coerceAtLeast(58),
            collected = 0.0,
            perStudent = perStudent,
            colorHex = 0xFFF59E0B
        )
        campaignsState.add(0, newCamp)
        Toast.makeText(this, "Created $name collection", Toast.LENGTH_SHORT).show()
    }

    private fun sendPushReminders(campaignName: String, unpaidProfileIds: List<String>) {
        if (!NetworkMonitor.isOnline(this)) {
            Toast.makeText(this, "Connect to internet to send reminders.", Toast.LENGTH_SHORT).show()
            return
        }
        if (unpaidProfileIds.isEmpty()) {
            Toast.makeText(this, "No unpaid accounts found.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val params = JSONObject().apply {
                    put("target_batch", batchId)
                    put("target_collection", campaignName)
                    put("unpaid_profile_ids", JSONArray(unpaidProfileIds))
                }
                val count = ClassMateAuthApi.rpcText("send_batch_fund_dues_reminder", params).trim().toIntOrNull() ?: unpaidProfileIds.size
                HapticHelper.success(this@ClassMateBatchFundActivity)
                Toast.makeText(this@ClassMateBatchFundActivity, "Reminders sent to $count classmates!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchFundActivity, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUndoToast(message: String, onUndo: () -> Unit) {
        undoJob?.cancel()
        undoActionState.value = UndoAction(message, onUndo)
        undoJob = lifecycleScope.launch {
            delay(4000)
            undoActionState.value = null
        }
    }

    private fun shareStatement() {
        val text = """
            📊 *Batch Fund Statement*
            🎓 ${summaryState.value.batchName} • ${summaryState.value.university} Treasury

            💰 *Current Balance:* ৳ ${formatMoney(summaryState.value.currentBalance)}
            📥 *Total Collected:* ৳ ${formatMoney(summaryState.value.totalCollected)}
            📤 *Total Spent:* ৳ ${formatMoney(summaryState.value.totalSpent)}
            📝 *Total Transactions:* ${summaryState.value.txCount}

            📌 _Updated via ClassMate App_
        """.trimIndent()

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Batch Fund Statement", text))
        Toast.makeText(this, "Statement copied to clipboard!", Toast.LENGTH_SHORT).show()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Batch Fund Statement"))
    }

    private fun formatMoney(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            String.format(Locale.US, "%,d", amount.toLong())
        } else {
            String.format(Locale.US, "%,.2f", amount)
        }
    }
}
