package com.shuaib.classmate.activities

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.data.offline.ClassMateBatchFundCache
import com.shuaib.classmate.data.offline.OfflineFundQueue
import com.shuaib.classmate.data.offline.PendingFundTransaction
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.EmptyStateHelper
import com.shuaib.classmate.utils.HapticHelper
import com.shuaib.classmate.utils.NetworkMonitor
import com.shuaib.classmate.utils.NotificationSender
import com.shuaib.classmate.workers.OfflineFundSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

class ClassMateBatchFundActivity : AppCompatActivity() {

    private var batchId: String = ""
    private var batchLabel: String = ""
    private var isCr: Boolean = false
    private var currentBalance: Double = 0.0
    private var totalCollected: Double = 0.0
    private var totalSpent: Double = 0.0
    private var txCount: Int = 0
    private var fundDescription: String = ""
    private var batchName: String = "Batch Fund"
    private var university: String = "MBSTU"
    private var batchRoster: List<JSONObject> = emptyList()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvCurrentBalance: TextView
    private lateinit var tvBalanceStats: TextView
    private lateinit var tvFundDescription: TextView
    private lateinit var ivEditDescHint: View
    private lateinit var recentActivityContainer: LinearLayout
    private lateinit var emptyStateOverview: View
    private lateinit var shimmerRecentActivity: ShimmerFrameLayout

    private var ongoingTitle: String = ""
    private var ongoingCanon: String = ""
    private var ongoingCollected: Double = 0.0
    private var ongoingPaidCount: Int = 0
    private var ongoingUnpaidCount: Int = 0
    private var ongoingProgress: Double = 0.0

    private lateinit var tvHeroCollected: TextView
    private lateinit var tvHeroSpent: TextView
    private lateinit var layoutCrQuickActions: View
    private lateinit var btnQuickDeposit: View
    private lateinit var btnQuickExpense: View

    private lateinit var cardHeroOngoingCampaign: View
    private lateinit var tvHeroCampaignTag: TextView
    private lateinit var tvHeroCampaignRatio: TextView
    private lateinit var pbHeroCampaign: ProgressBar
    private lateinit var tvHeroCampaignCollected: TextView
    private lateinit var tvHeroCampaignDues: TextView

    private lateinit var layoutMainActions: View
    private lateinit var btnAddFundPrimary: MaterialButton
    private lateinit var btnRecordExpensePrimary: MaterialButton

    private lateinit var cardPayersBanner: View
    private lateinit var tvPayersBannerSubtitle: TextView
    private lateinit var tvPayersActivePill: TextView
    private lateinit var cardLedgerHub: View
    private lateinit var tvLedgerCountSubtitle: TextView
    private lateinit var cardSummaryHub: View

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OfflineFundSyncWorker.ACTION_FUND_SYNCED) {
                loadData()
                loadBatchRoster()
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

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

        batchId = intent.getStringExtra("batch_id").orEmpty()
        batchLabel = intent.getStringExtra("batch_label").orEmpty()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_batch_fund)

        val root = findViewById<View>(R.id.batchFundRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            ins
        }

        toolbar = findViewById(R.id.fundToolbar)
        swipeRefresh = findViewById(R.id.swipeRefreshFund)
        swipeRefresh.setColorSchemeResources(R.color.cm_primary)
        swipeRefresh.setOnRefreshListener {
            loadData()
            loadBatchRoster()
        }
        tvCurrentBalance = findViewById(R.id.tvCurrentBalance)
        tvBalanceStats = findViewById(R.id.tvBalanceStats)
        tvFundDescription = findViewById(R.id.tvFundDescription)
        ivEditDescHint = findViewById(R.id.ivEditDescHint)
        recentActivityContainer = findViewById(R.id.recentActivityContainer)
        emptyStateOverview = findViewById(R.id.emptyStateOverview)
        shimmerRecentActivity = findViewById(R.id.shimmerRecentActivity)

        tvHeroCollected = findViewById(R.id.tvHeroCollected)
        tvHeroSpent = findViewById(R.id.tvHeroSpent)
        layoutCrQuickActions = findViewById(R.id.layoutCrQuickActions)
        btnQuickDeposit = findViewById(R.id.btnQuickDeposit)
        btnQuickExpense = findViewById(R.id.btnQuickExpense)

        cardHeroOngoingCampaign = findViewById(R.id.cardHeroOngoingCampaign)
        tvHeroCampaignTag = findViewById(R.id.tvHeroCampaignTag)
        tvHeroCampaignRatio = findViewById(R.id.tvHeroCampaignRatio)
        pbHeroCampaign = findViewById(R.id.pbHeroCampaign)
        tvHeroCampaignCollected = findViewById(R.id.tvHeroCampaignCollected)
        tvHeroCampaignDues = findViewById(R.id.tvHeroCampaignDues)

        layoutMainActions = findViewById(R.id.layoutMainActions)
        btnAddFundPrimary = findViewById(R.id.btnAddFundPrimary)
        btnRecordExpensePrimary = findViewById(R.id.btnRecordExpensePrimary)

        cardPayersBanner = findViewById(R.id.cardPayersBanner)
        tvPayersBannerSubtitle = findViewById(R.id.tvPayersBannerSubtitle)
        tvPayersActivePill = findViewById(R.id.tvPayersActivePill)
        cardLedgerHub = findViewById(R.id.cardLedgerHub)
        tvLedgerCountSubtitle = findViewById(R.id.tvLedgerCountSubtitle)
        cardSummaryHub = findViewById(R.id.cardSummaryHub)

        cardHeroOngoingCampaign.setOnClickListener {
            HapticHelper.lightPop(it)
            showPayersSheet(initialCollection = if (ongoingTitle.isNotBlank()) ongoingTitle else "all")
        }

        cardPayersBanner.setOnClickListener {
            HapticHelper.lightPop(it)
            showPayersSheet(initialCollection = if (ongoingTitle.isNotBlank()) ongoingTitle else "all")
        }

        btnAddFundPrimary.setOnClickListener {
            HapticHelper.lightPop(it)
            val defaultReason = if (ongoingTitle.isNotBlank()) ongoingTitle else null
            showAddFundSheet(prefillIsExpense = false, prefillTitle = defaultReason)
        }

        btnRecordExpensePrimary.setOnClickListener {
            HapticHelper.lightPop(it)
            showAddFundSheet(prefillIsExpense = true)
        }

        btnQuickDeposit.setOnClickListener {
            HapticHelper.lightPop(it)
            showAddFundSheet(prefillIsExpense = false)
        }

        btnQuickExpense.setOnClickListener {
            HapticHelper.lightPop(it)
            showAddFundSheet(prefillIsExpense = true)
        }

        cardLedgerHub.setOnClickListener {
            HapticHelper.lightPop(it)
            showLedgerSheet()
        }

        cardSummaryHub.setOnClickListener {
            HapticHelper.lightPop(it)
            showSummarySheet()
        }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_batch_fund_options)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    loadData()
                    loadBatchRoster()
                    true
                }
                R.id.action_share_statement -> {
                    shareStatement()
                    true
                }
                else -> false
            }
        }

        findViewById<View>(R.id.tileAddFund).setOnClickListener {
            HapticHelper.lightPop(it)
            if (!isCr) {
                HapticHelper.warning(this)
                Toast.makeText(this, "Only Class Representatives can record transactions.", Toast.LENGTH_SHORT).show()
            } else {
                showAddFundSheet()
            }
        }

        findViewById<View>(R.id.tileViewLedger).setOnClickListener {
            HapticHelper.lightPop(it)
            showLedgerSheet()
        }

        findViewById<View>(R.id.btnSeeAllActivity).setOnClickListener {
            HapticHelper.lightPop(it)
            showLedgerSheet()
        }

        findViewById<View>(R.id.tilePayers).setOnClickListener {
            HapticHelper.lightPop(it)
            showPayersSheet()
        }

        findViewById<View>(R.id.tileSummary).setOnClickListener {
            HapticHelper.lightPop(it)
            showSummarySheet()
        }

        findViewById<View>(R.id.cardBottomDesc).setOnClickListener {
            if (isCr) {
                showEditDescriptionDialog()
            }
        }

        lifecycleScope.launch {
            if (!ClassMateAuthApi.restoreSession()) {
                if (ClassMateAuthApi.accessToken.isNullOrBlank()) {
                    Toast.makeText(this@ClassMateBatchFundActivity, "Session expired, please sign in again", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
            }
            if (batchId.isBlank()) {
                val prof = ClassMateAuthApi.initializeProfile()
                batchId = prof.optString("batch_id").ifBlank { prof.optString("effective_batch_id") }
            }
            if (batchId.isBlank()) {
                Toast.makeText(this@ClassMateBatchFundActivity, "No active batch associated with your profile.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            loadData()
            loadBatchRoster()
        }
    }

    private fun loadBatchRoster() {
        if (batchId.isBlank()) return
        val cached = ClassMateBatchFundCache.getRoster(this, batchId)
        if (cached.isNotEmpty()) {
            batchRoster = cached
        }
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
                    batchRoster = list
                    ClassMateBatchFundCache.saveRoster(this@ClassMateBatchFundActivity, batchId, list)
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

        currentBalance = bal
        totalCollected = coll
        totalSpent = spent
        txCount = count
        isCr = summary.optBoolean("is_cr", false)
        fundDescription = summary.optString("fund_description", "Used for mess, events, jersey, and other batch expenses.")
        batchName = summary.optString("batch_name", batchLabel.ifBlank { "Batch Fund" })
        university = summary.optString("university", "MBSTU")

        val totalStudents = summary.optInt("total_students", 0)
        val paidStudents = summary.optInt("paid_students", 0)
        val unpaidStudents = summary.optInt("unpaid_students", 0)

        ongoingTitle = summary.optString("ongoing_title", "").trim()
        ongoingCanon = summary.optString("ongoing_canon", "").trim()
        ongoingCollected = summary.optDouble("ongoing_collected", 0.0)
        ongoingPaidCount = summary.optInt("ongoing_paid_count", 0)
        ongoingUnpaidCount = summary.optInt("ongoing_unpaid_count", 0)
        ongoingProgress = summary.optDouble("ongoing_progress", 0.0)

        // Update UI
        toolbar.title = "Batch Fund"
        toolbar.subtitle = "$batchName • $university"
        tvCurrentBalance.text = "৳ ${formatMoney(currentBalance)}"
        tvHeroCollected.text = "৳ ${formatMoney(totalCollected)}"
        tvHeroSpent.text = "৳ ${formatMoney(totalSpent)}"
        layoutCrQuickActions.visibility = if (isCr) View.VISIBLE else View.GONE
        layoutMainActions.visibility = if (isCr) View.VISIBLE else View.GONE

        // Ongoing campaign hero card & dues tracker banner
        if (ongoingTitle.isNotBlank()) {
            cardHeroOngoingCampaign.visibility = View.VISIBLE
            tvHeroCampaignTag.text = "ACTIVE CAMPAIGN: ${ongoingTitle.uppercase()}"
            val totalOngoing = ongoingPaidCount + ongoingUnpaidCount
            tvHeroCampaignRatio.text = "$ongoingPaidCount / $totalOngoing Paid"
            pbHeroCampaign.progress = ongoingProgress.toInt().coerceIn(0, 100)
            tvHeroCampaignCollected.text = "৳ ${formatMoney(ongoingCollected)} Collected"
            tvHeroCampaignDues.text = "$ongoingUnpaidCount Unpaid ›"

            tvPayersActivePill.visibility = View.VISIBLE
            tvPayersActivePill.text = ongoingTitle
            tvPayersBannerSubtitle.text = "$ongoingTitle: $ongoingPaidCount Paid • $ongoingUnpaidCount Unpaid"
        } else {
            cardHeroOngoingCampaign.visibility = View.GONE
            tvPayersActivePill.visibility = View.GONE
            if (totalStudents > 0) {
                tvPayersBannerSubtitle.text = "$paidStudents Paid • $unpaidStudents Unpaid • Tap to view tracker"
            } else {
                tvPayersBannerSubtitle.text = "Track who paid, who owes & copy unpaid lists"
            }
        }
        tvLedgerCountSubtitle.text = "$txCount ${if (txCount == 1) "transaction" else "transactions"}"

        tvBalanceStats.text = "Total Collected: ৳ ${formatMoney(totalCollected)} • Total Spent: ৳ ${formatMoney(totalSpent)}"
        tvFundDescription.text = fundDescription
        ivEditDescHint.visibility = if (isCr) View.VISIBLE else View.GONE
    }

    private fun loadData() {
        if (batchId.isBlank()) return
        val cachedSummary = ClassMateBatchFundCache.getSummary(this, batchId)
        if (cachedSummary != null) {
            applySummaryData(cachedSummary)
        }
        lifecycleScope.launch {
            try {
                // Fetch summary
                val summary = ClassMateAuthApi.rpc(
                    "batch_fund_summary",
                    JSONObject().put("target_batch", batchId)
                )
                ClassMateBatchFundCache.saveSummary(this@ClassMateBatchFundActivity, batchId, summary)
                applySummaryData(summary)

                if ((intent.getBooleanExtra("open_record_dialog", false) || intent.getBooleanExtra("open_add_fund", false)) && isCr) {
                    intent.removeExtra("open_record_dialog")
                    intent.removeExtra("open_add_fund")
                    showAddFundSheet()
                } else if (intent.getBooleanExtra("open_payers", false)) {
                    intent.removeExtra("open_payers")
                    showPayersSheet(initialCollection = if (ongoingTitle.isNotBlank()) ongoingTitle else "all")
                }

                // Load recent 5 transactions
                loadRecentTransactions()
            } catch (e: Exception) {
                if (cachedSummary == null) {
                    Toast.makeText(this@ClassMateBatchFundActivity, "Error loading fund: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                loadRecentTransactions()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun loadRecentTransactions() {
        val cachedTxs = ClassMateBatchFundCache.getTransactions(this, batchId)
        val pendingTxs = OfflineFundQueue.getForBatch(this, batchId)

        fun displayTransactions(serverArray: JSONArray?) {
            val allList = mutableListOf<JSONObject>()
            pendingTxs.forEach { allList.add(it.toLedgerJsonObject()) }
            if (serverArray != null) {
                for (i in 0 until serverArray.length()) {
                    allList.add(serverArray.getJSONObject(i))
                }
            }
            val displayList = allList.take(5)

            shimmerRecentActivity.stopShimmer()
            shimmerRecentActivity.visibility = View.GONE
            recentActivityContainer.visibility = View.VISIBLE
            recentActivityContainer.removeAllViews()

            if (displayList.isEmpty()) {
                EmptyStateHelper.show(
                    emptyStateOverview,
                    iconRes = R.drawable.ic_sparkle,
                    title = "No transactions yet",
                    subtitle = if (isCr) "Tap '+ Add Fund' above to record the first transaction." else "No transactions recorded in this batch fund yet."
                )
            } else {
                EmptyStateHelper.hide(emptyStateOverview)
                for (tx in displayList) {
                    recentActivityContainer.addView(createTransactionRow(tx))
                }
            }
        }

        // Show cached + pending immediately if available
        if (cachedTxs != null || pendingTxs.isNotEmpty()) {
            displayTransactions(cachedTxs)
        } else {
            shimmerRecentActivity.visibility = View.VISIBLE
            shimmerRecentActivity.startShimmer()
            EmptyStateHelper.hide(emptyStateOverview)
            recentActivityContainer.visibility = View.GONE
        }

        try {
            val responseText = ClassMateAuthApi.rpcText(
                "batch_fund_transactions",
                JSONObject().apply {
                    put("target_batch", batchId)
                    put("filter_type", "all")
                    put("query_text", "")
                    put("result_offset", 0)
                    put("page_limit", 20)
                }
            )
            val txArray = JSONArray(responseText)
            ClassMateBatchFundCache.saveTransactions(this, batchId, txArray)
            displayTransactions(txArray)
        } catch (e: Exception) {
            if (cachedTxs == null && pendingTxs.isEmpty()) {
                shimmerRecentActivity.stopShimmer()
                shimmerRecentActivity.visibility = View.GONE
                recentActivityContainer.visibility = View.VISIBLE
                recentActivityContainer.removeAllViews()
                EmptyStateHelper.showError(
                    emptyStateOverview,
                    title = "Could not load activity",
                    errorMessage = "Check your connection and tap below to retry.",
                    onRetry = { loadData() }
                )
            }
        }
    }

    private fun formatTransactionDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank() || dateStr == "null") return ""
        return try {
            val raw = dateStr.trim().take(10)
            val parsed = LocalDate.parse(raw)
            val today = LocalDate.now()
            when (parsed) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> parsed.format(DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
            }
        } catch (_: Exception) {
            dateStr
        }
    }

    private fun createTransactionRow(tx: JSONObject): View {
        val isPending = tx.optBoolean("is_offline_pending", false)
        val isInflow = tx.optString("type") == "inflow"
        val amount = tx.optDouble("amount", 0.0)
        val title = tx.optString("title")
        val studentName = tx.optString("student_name")
        val dateStr = tx.optString("transacted_at")
        val canEdit = if (isPending) isCr else tx.optBoolean("can_edit", isCr)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = getDrawable(R.drawable.bg_fund_card)
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            isClickable = canEdit
            isFocusable = canEdit
            if (canEdit) {
                if (isPending) {
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@ClassMateBatchFundActivity)
                            .setTitle("Pending Offline Transaction")
                            .setMessage("This transaction was recorded while offline. It will be uploaded automatically once you are connected to the internet.\n\nAmount: ৳ ${formatMoney(amount)}\nTitle: $title")
                            .setPositiveButton("Keep Waiting", null)
                            .setNegativeButton("Discard") { _, _ ->
                                val offlineId = tx.optString("offline_id").ifBlank { tx.optString("id").removePrefix("offline_") }
                                OfflineFundQueue.remove(this@ClassMateBatchFundActivity, offlineId)
                                Toast.makeText(this@ClassMateBatchFundActivity, "Offline transaction discarded", Toast.LENGTH_SHORT).show()
                                loadData()
                            }
                            .show()
                    }
                } else {
                    setOnClickListener { showTransactionActionsDialog(tx) }
                }
            }
        }

        // Avatar
        val avatar = FrameLayout(this).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isInflow) 0xFFDCFCE7.toInt() else 0xFFFFEDD5.toInt())
            }
        }
        val avatarText = TextView(this).apply {
            val initial = (if (studentName.isNotBlank() && studentName != "null") studentName else title).take(1).uppercase()
            text = initial
            setTextColor(if (isInflow) 0xFF16A34A.toInt() else 0xFFD97706.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        avatar.addView(avatarText)
        card.addView(avatar)

        // Middle Info
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val tvMainTitle = TextView(this).apply {
            text = if (studentName.isNotBlank() && studentName != "null") studentName else title
            setTextColor(getColor(R.color.cm_text_primary))
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val formattedDate = if (isPending) "Just now" else formatTransactionDate(dateStr)
        val tvSub = TextView(this).apply {
            val subText = if (studentName.isNotBlank() && studentName != "null") {
                val subTitle = if (title.isNotBlank() && !title.equals(studentName, ignoreCase = true)) title else (if (isInflow) "Deposit" else "Expense")
                if (formattedDate.isNotBlank()) "$subTitle • $formattedDate" else subTitle
            } else {
                if (formattedDate.isNotBlank()) "From batch fund • $formattedDate" else "From batch fund"
            }
            text = subText
            setTextColor(getColor(R.color.cm_text_secondary))
            textSize = 12f
        }
        infoCol.addView(tvMainTitle)
        infoCol.addView(tvSub)
        card.addView(infoCol)

        // Amount & Pending Badge Column
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val tvAmount = TextView(this).apply {
            text = if (isInflow) "+ ৳ ${formatMoney(amount)}" else "- ৳ ${formatMoney(amount)}"
            setTextColor(if (isInflow) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        rightCol.addView(tvAmount)

        if (isPending) {
            val tvPending = TextView(this).apply {
                text = "🕒 Pending Sync"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFD97706.toInt())
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFFEF3C7.toInt())
                    setStroke(dp(1), 0xFFFDE68A.toInt())
                }
                val lp = LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = dp(2)
                    gravity = Gravity.END
                }
                layoutParams = lp
            }
            rightCol.addView(tvPending)
        }
        card.addView(rightCol)

        return card
    }

    // ==========================================
    // ADD FUND SHEET (DIRECT MANUAL ENTRY & INSTANT ROSTER SEARCH)
    // ==========================================
    private fun showAddFundSheet(
        prefillProfileId: String? = null,
        prefillStudentName: String? = null,
        prefillTitle: String? = null,
        prefillIsExpense: Boolean = false
    ) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_fund_sheet, null, false)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isFitToContents = true
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val rgType = view.findViewById<RadioGroup>(R.id.rgManualType)
        val tilStudent = view.findViewById<TextInputLayout>(R.id.tilStudentName)
        val actvStudent = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvStudentName)
        val tilAmount = view.findViewById<TextInputLayout>(R.id.tilAmount)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etManualAmount)
        val tilTitle = view.findViewById<TextInputLayout>(R.id.tilTitle)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etManualTitle)
        val hsvReasons = view.findViewById<HorizontalScrollView>(R.id.hsvReasonChips)
        val chipGroupReasons = view.findViewById<ChipGroup>(R.id.chipGroupReasons)
        val btnSaveAndAddAnother = view.findViewById<MaterialButton>(R.id.btnSaveAndAddAnother)
        val btnSaveManual = view.findViewById<MaterialButton>(R.id.btnSaveManual)

        var selectedStudentProfileId: String? = prefillProfileId
        var selectedStudentName: String? = prefillStudentName

        if (prefillIsExpense) {
            rgType.check(R.id.rbManualExpense)
            tilStudent.visibility = View.GONE
            tilTitle.hint = "Expense Title / Reason *"
        } else {
            rgType.check(R.id.rbManualDeposit)
            tilStudent.visibility = View.VISIBLE
            tilTitle.hint = "Reason (e.g. Monthly Fund, Tour) *"
        }

        // Quick reason chips (prioritizing cached/recent batch collections)
        val defaultReasons = listOf("Monthly Fund", "Orientation", "Jersey", "Tour", "Picnic", "Farewell", "Batch Feast")
        fun populateChips(titles: Collection<String>) {
            chipGroupReasons.removeAllViews()
            for (r in titles) {
                val chip = Chip(this@ClassMateBatchFundActivity).apply {
                    text = r
                    isCheckable = false
                    isClickable = true
                    setOnClickListener {
                        etTitle.setText(r)
                        etTitle.setSelection(r.length)
                    }
                }
                chipGroupReasons.addView(chip)
            }
        }
        val cachedCollections = ClassMateBatchFundCache.getCollections(this, batchId)
        val initialTitles = linkedSetOf<String>()
        if (cachedCollections != null) {
            for (i in 0 until cachedCollections.length()) {
                val t = cachedCollections.getJSONObject(i).optString("title").trim()
                if (t.isNotBlank()) initialTitles.add(t)
            }
        }
        defaultReasons.forEach { initialTitles.add(it) }
        populateChips(initialTitles)

        lifecycleScope.launch {
            try {
                val colResponse = ClassMateAuthApi.rpcText(
                    "batch_fund_collections",
                    JSONObject().put("target_batch", batchId)
                )
                val colArray = JSONArray(colResponse)
                ClassMateBatchFundCache.saveCollections(this@ClassMateBatchFundActivity, batchId, colArray)
                val dynamicTitles = linkedSetOf<String>()
                for (i in 0 until colArray.length()) {
                    val t = colArray.getJSONObject(i).optString("title").trim()
                    if (t.isNotBlank()) dynamicTitles.add(t)
                }
                defaultReasons.forEach { dynamicTitles.add(it) }
                populateChips(dynamicTitles)
            } catch (_: Exception) {}
        }

        data class StudentOption(
            val profileId: String?,
            val fullName: String,
            val studentId: String?,
            val display: String
        ) {
            override fun toString(): String = display
        }

        val studentOptions = batchRoster.map {
            val name = it.optString("full_name").trim()
            val sid = it.optString("student_id").trim().takeIf { s -> s.isNotBlank() && s != "null" }
            val pid = it.optString("id").ifBlank { it.optString("profile_id") }.trim().takeIf { p -> p.isNotBlank() && p != "null" }
            val label = if (sid != null) "$name (ID: $sid)" else name
            StudentOption(pid, name, sid, label)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, studentOptions)
        actvStudent.setAdapter(adapter)

        if (!prefillStudentName.isNullOrBlank()) {
            actvStudent.setText(prefillStudentName, false)
            actvStudent.setSelection(prefillStudentName.length)
            etAmount.postDelayed({ etAmount.requestFocus() }, 120)
        }
        if (!prefillTitle.isNullOrBlank()) {
            etTitle.setText(prefillTitle)
            etTitle.setSelection(prefillTitle.length)
        }

        actvStudent.setOnItemClickListener { parent, _, position, _ ->
            val opt = parent.getItemAtPosition(position) as? StudentOption
            if (opt != null) {
                selectedStudentProfileId = opt.profileId
                selectedStudentName = opt.fullName
                actvStudent.setText(opt.fullName, false)
                actvStudent.setSelection(opt.fullName.length)
                tilStudent.error = null
                etAmount.requestFocus()
            }
        }

        actvStudent.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                etAmount.requestFocus()
                true
            } else false
        }

        etAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tilAmount.error = null
                val raw = s?.toString()?.toDoubleOrNull()
                if (raw != null && raw > 0) {
                    val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(raw)
                    tilAmount.helperText = "৳ $formatted"
                } else {
                    tilAmount.helperText = null
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        etAmount.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                etTitle.requestFocus()
                true
            } else false
        }

        actvStudent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentText = s?.toString()?.trim().orEmpty()
                if (selectedStudentName != null && currentText != selectedStudentName) {
                    selectedStudentProfileId = null
                    selectedStudentName = null
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        fun updateTypeUi() {
            val isDeposit = rgType.checkedRadioButtonId == R.id.rbManualDeposit
            tilStudent.visibility = if (isDeposit) View.VISIBLE else View.GONE
            tilTitle.hint = if (isDeposit) "Reason of Collection (e.g. Monthly Fund)" else "Expense Purpose (e.g. Tour Bus Rent)"
            hsvReasons.visibility = if (isDeposit) View.VISIBLE else View.GONE
            btnSaveAndAddAnother.visibility = if (isDeposit) View.VISIBLE else View.GONE
            btnSaveManual.text = if (isDeposit) "Save Transaction" else "Record Expense"
        }

        rgType.setOnCheckedChangeListener { _, _ -> updateTypeUi() }
        updateTypeUi()

        val todayStr = LocalDate.now().toString()

        fun performSave(keepOpen: Boolean) {
            val isDeposit = rgType.checkedRadioButtonId == R.id.rbManualDeposit
            val amt = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
            val title = etTitle.text?.toString()?.trim().orEmpty()
            val studentRawName = actvStudent.text?.toString()?.trim().orEmpty()

            if (isDeposit) {
                if (selectedStudentProfileId.isNullOrBlank()) {
                    val matched = studentOptions.firstOrNull {
                        it.fullName.equals(studentRawName, ignoreCase = true) ||
                        it.display.equals(studentRawName, ignoreCase = true) ||
                        (it.studentId != null && it.studentId.equals(studentRawName, ignoreCase = true))
                    }
                    if (matched != null) {
                        selectedStudentProfileId = matched.profileId
                        selectedStudentName = matched.fullName
                    } else {
                        tilStudent.error = "Please select a student from your batch"
                        actvStudent.requestFocus()
                        return
                    }
                }
                tilStudent.error = null
            }

            if (amt <= 0.0) {
                HapticHelper.warning(this@ClassMateBatchFundActivity)
                tilAmount.error = "Enter a valid amount"
                etAmount.requestFocus()
                return
            }
            tilAmount.error = null

            if (title.isBlank()) {
                HapticHelper.warning(this@ClassMateBatchFundActivity)
                tilTitle.error = if (isDeposit) "Enter reason of collection" else "Enter expense purpose"
                etTitle.requestFocus()
                return
            }
            tilTitle.error = null

            val btnToDisable = if (keepOpen) btnSaveAndAddAnother else btnSaveManual
            btnToDisable.isEnabled = false

            val type = if (isDeposit) "inflow" else "outflow"
            val sName = if (isDeposit) (selectedStudentName ?: studentRawName) else null
            val sId = if (isDeposit) studentOptions.firstOrNull { it.profileId == selectedStudentProfileId || it.fullName.equals(sName, ignoreCase = true) }?.studentId else null

            fun saveOffline() {
                val pendingTx = PendingFundTransaction(
                    batchId = batchId,
                    type = type,
                    amount = amt,
                    title = title,
                    studentName = sName,
                    studentId = sId,
                    studentProfileId = if (isDeposit) selectedStudentProfileId else null
                )
                OfflineFundQueue.enqueue(this@ClassMateBatchFundActivity, pendingTx)
                HapticHelper.success(this@ClassMateBatchFundActivity)
                Toast.makeText(
                    this@ClassMateBatchFundActivity,
                    "Saved offline. Will sync to database automatically when online.",
                    Toast.LENGTH_LONG
                ).show()
                loadData()

                if (keepOpen) {
                    btnToDisable.isEnabled = true
                    actvStudent.text?.clear()
                    selectedStudentProfileId = null
                    selectedStudentName = null
                    etAmount.text?.clear()
                    actvStudent.requestFocus()
                } else {
                    dialog.dismiss()
                }
            }

            if (!NetworkMonitor.isOnline(this@ClassMateBatchFundActivity)) {
                saveOffline()
                return
            }

            lifecycleScope.launch {
                try {
                    ClassMateAuthApi.rpc(
                        "record_batch_fund_transaction",
                        JSONObject().apply {
                            put("target_batch", batchId)
                            put("trans_type", type)
                            put("trans_amount", amt)
                            put("trans_title", title)
                            put("trans_student_name", sName)
                            put("trans_student_id", sId)
                            put("trans_date", todayStr)
                            if (isDeposit && !selectedStudentProfileId.isNullOrBlank()) {
                                put("trans_student_profile_id", selectedStudentProfileId)
                            }
                        }
                    )

                    HapticHelper.success(this@ClassMateBatchFundActivity)
                    Toast.makeText(
                        this@ClassMateBatchFundActivity,
                        if (isDeposit) "Deposit recorded for $sName" else "Expense recorded",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadData()

                    if (keepOpen) {
                        btnToDisable.isEnabled = true
                        actvStudent.text?.clear()
                        selectedStudentProfileId = null
                        selectedStudentName = null
                        etAmount.text?.clear()
                        actvStudent.requestFocus()
                    } else {
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    val isNetwork = e is java.io.IOException || e.message?.contains("network", ignoreCase = true) == true
                    if (isNetwork) {
                        saveOffline()
                    } else {
                        HapticHelper.warning(this@ClassMateBatchFundActivity)
                        btnToDisable.isEnabled = true
                        Toast.makeText(this@ClassMateBatchFundActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnSaveAndAddAnother.setOnClickListener { performSave(keepOpen = true) }
        btnSaveManual.setOnClickListener { performSave(keepOpen = false) }

        etTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performSave(keepOpen = false)
                true
            } else false
        }

        dialog.show()
    }

    // ==========================================
    // VIEW LEDGER SHEET
    // ==========================================
    private fun showLedgerSheet(initialQuery: String = "") {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_fund_ledger_sheet, null, false)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val tabFilter = view.findViewById<TabLayout>(R.id.tabLedgerFilter)
        val etSearch = view.findViewById<TextInputEditText>(R.id.etLedgerSearch)
        val container = view.findViewById<LinearLayout>(R.id.ledgerItemsContainer)
        val emptyStateLedger = view.findViewById<View>(R.id.emptyStateLedger)
        val shimmerLedger = view.findViewById<ShimmerFrameLayout>(R.id.shimmerLedger)

        var currentFilter = "all"
        var currentQuery = initialQuery.trim()
        var ledgerJob: Job? = null
        var searchDebounceJob: Job? = null
        var isInitializingTabs = true

        fun fetchLedger() {
            ledgerJob?.cancel()
            val cachedArray = ClassMateBatchFundCache.getTransactions(this@ClassMateBatchFundActivity, batchId)
            val pendingList = OfflineFundQueue.getForBatch(this@ClassMateBatchFundActivity, batchId)

            fun renderLedgerItems(serverArray: JSONArray?) {
                val mergedList = mutableListOf<JSONObject>()
                val filteredPending = pendingList.filter {
                    val typeMatch = currentFilter == "all" || it.type == currentFilter
                    val queryMatch = currentQuery.isBlank() ||
                        it.title.contains(currentQuery, ignoreCase = true) ||
                        (it.studentName?.contains(currentQuery, ignoreCase = true) == true) ||
                        (it.studentId?.contains(currentQuery, ignoreCase = true) == true)
                    typeMatch && queryMatch
                }
                filteredPending.forEach { mergedList.add(it.toLedgerJsonObject()) }

                if (serverArray != null) {
                    val seenIds = HashSet<String>()
                    for (i in 0 until serverArray.length()) {
                        val tx = serverArray.getJSONObject(i)
                        val id = tx.optString("id")
                        if (id.isNotBlank() && !seenIds.add(id)) continue
                        mergedList.add(tx)
                    }
                }

                shimmerLedger.stopShimmer()
                shimmerLedger.visibility = View.GONE
                container.visibility = View.VISIBLE
                container.removeAllViews()

                if (mergedList.isEmpty()) {
                    EmptyStateHelper.show(
                        emptyStateLedger,
                        iconRes = R.drawable.ic_search_modern,
                        title = if (currentQuery.isNotBlank()) "No matching transactions" else "No transactions found",
                        subtitle = if (currentQuery.isNotBlank()) "No records match '$currentQuery'. Try searching something else." else "No transactions recorded under this filter yet."
                    )
                } else {
                    EmptyStateHelper.hide(emptyStateLedger)
                    for (tx in mergedList) {
                        val row = createTransactionRow(tx)
                        val isPending = tx.optBoolean("is_offline_pending", false)
                        if (isCr && !isPending) {
                            row.setOnClickListener {
                                dialog.dismiss()
                                showTransactionActionsDialog(tx)
                            }
                        }
                        container.addView(row)
                    }
                }
            }

            if (cachedArray != null || pendingList.isNotEmpty()) {
                renderLedgerItems(cachedArray)
            } else {
                shimmerLedger.visibility = View.VISIBLE
                shimmerLedger.startShimmer()
                EmptyStateHelper.hide(emptyStateLedger)
                container.visibility = View.GONE
            }

            ledgerJob = lifecycleScope.launch {
                try {
                    val response = ClassMateAuthApi.rpcText(
                        "batch_fund_transactions",
                        JSONObject().apply {
                            put("target_batch", batchId)
                            put("filter_type", currentFilter)
                            put("query_text", currentQuery)
                            put("result_offset", 0)
                            put("page_limit", 500)
                        }
                    )
                    val array = JSONArray(response)
                    if (currentFilter == "all" && currentQuery.isBlank()) {
                        ClassMateBatchFundCache.saveTransactions(this@ClassMateBatchFundActivity, batchId, array)
                    }
                    renderLedgerItems(array)
                } catch (e: Exception) {
                    if (e is CancellationException) return@launch
                    if (cachedArray == null && pendingList.isEmpty()) {
                        shimmerLedger.stopShimmer()
                        shimmerLedger.visibility = View.GONE
                        container.visibility = View.VISIBLE
                        container.removeAllViews()
                        EmptyStateHelper.showError(
                            emptyStateLedger,
                            title = "Could not load ledger",
                            errorMessage = e.message ?: "Failed to retrieve transactions.",
                            onRetry = { fetchLedger() }
                        )
                    }
                }
            }
        }

        tabFilter.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (isInitializingTabs) return
                HapticHelper.selectionTick(tabFilter)
                val newFilter = when (tab?.position) {
                    1 -> "inflow"
                    2 -> "outflow"
                    else -> "all"
                }
                if (newFilter != currentFilter) {
                    currentFilter = newFilter
                    fetchLedger()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        isInitializingTabs = false

        etSearch.doAfterTextChanged {
            val query = it?.toString()?.trim().orEmpty()
            if (query == currentQuery) return@doAfterTextChanged
            currentQuery = query
            searchDebounceJob?.cancel()
            searchDebounceJob = lifecycleScope.launch {
                delay(250) // 250ms debounce
                fetchLedger()
            }
        }

        if (initialQuery.isNotBlank()) {
            etSearch.setText(initialQuery)
            etSearch.setSelection(initialQuery.length)
        }

        dialog.setOnDismissListener {
            ledgerJob?.cancel()
            searchDebounceJob?.cancel()
        }

        fetchLedger()
        dialog.show()
    }

    // ==========================================
    // PAYERS TRACKER SHEET (COLLECTION CHIPS + UNIFIED PAID/UNPAID)
    // ==========================================
    private fun showPayersSheet(initialCollection: String? = null) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_fund_payers_sheet, null, false)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val root = view.findViewById<View>(R.id.dialogPayersRoot)
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBar.bottom + dp(16))
                insets
            }
        }

        val hsvCollections = view.findViewById<HorizontalScrollView>(R.id.hsvPayersCollections)
        val chipGroupCollections = view.findViewById<ChipGroup>(R.id.chipGroupPayersCollections)
        val cardCampaignSummary = view.findViewById<View>(R.id.cardCampaignSummary)
        val tvSheetCampaignTitle = view.findViewById<TextView>(R.id.tvSheetCampaignTitle)
        val tvSheetPaidStat = view.findViewById<TextView>(R.id.tvSheetPaidStat)
        val tvSheetUnpaidStat = view.findViewById<TextView>(R.id.tvSheetUnpaidStat)
        val pbSheetCampaign = view.findViewById<ProgressBar>(R.id.pbSheetCampaign)
        val btnCopyUnpaid = view.findViewById<MaterialButton>(R.id.btnCopyUnpaid)
        val btnRemindUnpaid = view.findViewById<MaterialButton>(R.id.btnRemindUnpaid)
        val etSearch = view.findViewById<TextInputEditText>(R.id.etPayersSearch)
        val container = view.findViewById<LinearLayout>(R.id.payersItemsContainer)
        val emptyStatePayers = view.findViewById<View>(R.id.emptyStatePayers)
        val shimmerPayers = view.findViewById<ShimmerFrameLayout>(R.id.shimmerPayers)
        val tvPayersSubtitle = view.findViewById<TextView>(R.id.tvPayersSubtitle)

        val tabUnpaid = view.findViewById<TextView>(R.id.tabPayersUnpaid)
        val tabPaid = view.findViewById<TextView>(R.id.tabPayersPaid)
        val tabAll = view.findViewById<TextView>(R.id.tabPayersAll)

        var selectedCollection = initialCollection?.trim()?.takeIf { it.isNotBlank() }
            ?: if (ongoingTitle.isNotBlank()) ongoingTitle else "all"
        var currentSearchQuery = ""
        var filterMode = "unpaid" // Default to actionable Unpaid view
        var payersJob: Job? = null
        var searchDebounceJob: Job? = null
        val allPayers = mutableListOf<JSONObject>()

        fun isNight(): Boolean {
            return (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        fun updateFilterTabsUi() {
            val isDark = isNight()
            val tabs = listOf(
                Triple(tabUnpaid, "unpaid", 0xFFFEE2E2.toInt()),
                Triple(tabPaid, "paid", 0xFFDCFCE7.toInt()),
                Triple(tabAll, "all", if (isDark) 0xFF3F3F46.toInt() else 0xFFE2E8F0.toInt())
            )
            for ((tv, mode, activeBg) in tabs) {
                val isActive = filterMode == mode
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    if (isActive) {
                        setColor(activeBg)
                        if (!isDark && mode == "all") setStroke(dp(1), 0xFFCBD5E1.toInt())
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), if (isDark) 0xFF27272A.toInt() else 0xFFE2E8F0.toInt())
                    }
                }
                val activeTextCol = when (mode) {
                    "paid" -> 0xFF15803D.toInt()
                    "unpaid" -> 0xFFB91C1C.toInt()
                    else -> getColor(R.color.cm_text_primary)
                }
                tv.setTextColor(if (isActive) activeTextCol else getColor(R.color.cm_text_secondary))
            }
        }

        fun updateChipsVisualState() {
            val isDark = isNight()
            for (idx in 0 until chipGroupCollections.childCount) {
                val chip = chipGroupCollections.getChildAt(idx) as? Chip ?: continue
                val tagStr = chip.tag as? String ?: "all"
                val isSelected = selectedCollection.equals(tagStr, ignoreCase = true) ||
                        (selectedCollection == "all" && tagStr == "all")
                chip.isChecked = isSelected
                chip.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                if (isSelected) {
                    chip.chipBackgroundColor = ColorStateList.valueOf(0xFF0D9488.toInt()) // Teal
                    chip.setTextColor(Color.WHITE)
                    chip.chipStrokeWidth = 0f
                } else {
                    val bg = if (isDark) 0xFF27272A.toInt() else 0xFFF1F5F9.toInt()
                    val textCol = if (isDark) 0xFFE4E4E7.toInt() else 0xFF475569.toInt()
                    val strokeCol = if (isDark) 0xFF3F3F46.toInt() else 0xFFCBD5E1.toInt()
                    chip.chipBackgroundColor = ColorStateList.valueOf(bg)
                    chip.setTextColor(textCol)
                    chip.chipStrokeColor = ColorStateList.valueOf(strokeCol)
                    chip.chipStrokeWidth = dp(1).toFloat()
                }
            }
        }

        fun renderList(query: String) {
            container.removeAllViews()
            val filtered = allPayers.filter {
                val name = it.optString("full_name").lowercase()
                val sid = it.optString("student_id").lowercase()
                query.isBlank() || name.contains(query.lowercase()) || sid.contains(query.lowercase())
            }

            val paidList = filtered.filter { it.optDouble("total_paid", 0.0) > 0 }
            val unpaidList = filtered.filter { it.optDouble("total_paid", 0.0) <= 0 }
            val paidSum = paidList.sumOf { it.optDouble("total_paid", 0.0) }

            // Update stats tabs - exact counts shown ONCE here in the segmented control
            tabUnpaid.text = "Unpaid (${unpaidList.size})"
            tabPaid.text = "Paid (${paidList.size})"
            tabAll.text = "All (${filtered.size})"
            updateFilterTabsUi()

            val colDisplay = if (selectedCollection != "all") selectedCollection else "All Collections"
            tvPayersSubtitle.text = "Tracking: $colDisplay"

            // Update Campaign Summary Card
            tvSheetCampaignTitle.text = colDisplay
            tvSheetPaidStat.text = "${paidList.size} of ${filtered.size} Paid"
            tvSheetUnpaidStat.text = "৳ ${formatMoney(paidSum)} Collected"
            val campaignProgress = if (filtered.isNotEmpty()) (paidList.size * 100) / filtered.size else 0
            pbSheetCampaign.progress = campaignProgress

            // Contextual Copy Unpaid button inside summary card
            if (unpaidList.isNotEmpty()) {
                btnCopyUnpaid.visibility = View.VISIBLE
                btnCopyUnpaid.text = "Copy (${unpaidList.size})"
                btnCopyUnpaid.setOnClickListener {
                    HapticHelper.lightPop(it)
                    val sb = StringBuilder()
                    sb.append("📋 [$colDisplay] Unpaid Students (${unpaidList.size}):\n")
                    unpaidList.forEachIndexed { index, student ->
                        val sid = student.optString("student_id").trim().takeIf { s -> s.isNotBlank() && s != "null" }
                        val name = student.optString("full_name").trim()
                        val sidStr = if (sid != null) " (ID: $sid)" else ""
                        sb.append("${index + 1}. $name$sidStr\n")
                    }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Unpaid Students", sb.toString().trimEnd()))
                    Toast.makeText(this@ClassMateBatchFundActivity, "Copied ${unpaidList.size} unpaid students to clipboard!", Toast.LENGTH_SHORT).show()
                }
            } else {
                btnCopyUnpaid.visibility = View.GONE
            }

            // Remind Unpaid Button inside summary card (Option B)
            if (unpaidList.isNotEmpty() && isCr) {
                btnRemindUnpaid.visibility = View.VISIBLE
                btnRemindUnpaid.text = "Remind (${unpaidList.size})"
                btnRemindUnpaid.setOnClickListener {
                    HapticHelper.lightPop(it)
                    if (!NetworkMonitor.isOnline(this@ClassMateBatchFundActivity)) {
                        HapticHelper.warning(this@ClassMateBatchFundActivity)
                        Toast.makeText(this@ClassMateBatchFundActivity, "You are offline. Connect to internet to send reminders.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    MaterialAlertDialogBuilder(this@ClassMateBatchFundActivity)
                        .setTitle("Send Reminder to ${unpaidList.size} Classmates?")
                        .setMessage("A push notification will be sent to remind everyone who hasn't paid for '$colDisplay' yet.")
                        .setPositiveButton("Send Reminder") { _, _ ->
                            btnRemindUnpaid.isEnabled = false
                            NotificationSender.sendToBatch(
                                batchId = batchId,
                                title = "Batch Fund Reminder: $colDisplay",
                                message = "Gentle reminder from your CR to clear pending dues for $colDisplay.",
                                type = "batch_fund_reminder",
                                extraData = mapOf("campaign_title" to colDisplay, "action" to "batch_fund"),
                                onSuccess = {
                                    runOnUiThread {
                                        btnRemindUnpaid.isEnabled = true
                                        HapticHelper.success(this@ClassMateBatchFundActivity)
                                        Toast.makeText(this@ClassMateBatchFundActivity, "Reminders sent to ${unpaidList.size} unpaid students!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFailure = { err ->
                                    runOnUiThread {
                                        btnRemindUnpaid.isEnabled = true
                                        HapticHelper.warning(this@ClassMateBatchFundActivity)
                                        Toast.makeText(this@ClassMateBatchFundActivity, "Failed to send: $err", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                btnRemindUnpaid.visibility = View.GONE
            }

            if (filterMode == "unpaid" && unpaidList.isEmpty() && filtered.isNotEmpty()) {
                EmptyStateHelper.show(
                    emptyStatePayers,
                    iconRes = R.drawable.ic_sparkle,
                    title = "Everyone has paid!",
                    subtitle = "All classmates are clear for $colDisplay."
                )
                return
            }

            val itemsToDisplay = when (filterMode) {
                "paid" -> paidList
                "unpaid" -> unpaidList
                else -> null // will render in sections
            }

            if (filtered.isEmpty()) {
                EmptyStateHelper.show(
                    emptyStatePayers,
                    iconRes = R.drawable.ic_search_modern,
                    title = if (query.isNotBlank()) "No matching classmates" else "No records found",
                    subtitle = if (query.isNotBlank()) "No records matched '$query'." else "No classmates found for $colDisplay."
                )
                return
            }

            if (itemsToDisplay != null && itemsToDisplay.isEmpty()) {
                EmptyStateHelper.show(
                    emptyStatePayers,
                    iconRes = R.drawable.ic_users,
                    title = "No classmates found",
                    subtitle = "No students in the $filterMode category."
                )
                return
            }
            EmptyStateHelper.hide(emptyStatePayers)

            fun createStudentCard(payer: JSONObject): View {
                val name = payer.optString("full_name")
                val sid = payer.optString("student_id")
                val totalPaid = payer.optDouble("total_paid", 0.0)
                val count = payer.optInt("payment_count", 0)
                val isPaid = totalPaid > 0

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = getDrawable(R.drawable.bg_fund_card)
                    val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                    layoutParams = lp
                    isClickable = true
                    isFocusable = true
                }

                // Avatar
                val avatar = FrameLayout(this).apply {
                    val size = dp(36)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isPaid) 0xFFDCFCE7.toInt() else 0xFFF1F5F9.toInt())
                    }
                }
                val tvInit = TextView(this).apply {
                    text = name.take(1).uppercase()
                    setTextColor(if (isPaid) 0xFF16A34A.toInt() else 0xFF64748B.toInt())
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                }
                avatar.addView(tvInit)
                card.addView(avatar)

                // Info
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val tvName = TextView(this).apply {
                    text = name
                    setTextColor(getColor(R.color.cm_text_primary))
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val tvSid = TextView(this).apply {
                    val sidText = if (sid.isNotBlank() && sid != "null") "ID: $sid" else ""
                    text = sidText
                    setTextColor(getColor(R.color.cm_text_secondary))
                    textSize = 12f
                }
                info.addView(tvName)
                if (tvSid.text.isNotBlank()) {
                    info.addView(tvSid)
                }
                card.addView(info)

                // Paid / Unpaid Status Badge
                if (isPaid) {
                    val rightContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    }
                    val tvPaid = TextView(this).apply {
                        text = "৳ ${formatMoney(totalPaid)}"
                        setTextColor(0xFF16A34A.toInt())
                        textSize = 14.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.END
                    }
                    rightContainer.addView(tvPaid)
                    if (count > 1) {
                        val tvDepositCount = TextView(this).apply {
                            text = "Total ($count deposits)"
                            setTextColor(getColor(R.color.cm_text_secondary))
                            textSize = 11f
                            gravity = Gravity.END
                        }
                        rightContainer.addView(tvDepositCount)
                    }
                    card.addView(rightContainer)
                } else {
                    val tvUnpaid = TextView(this).apply {
                        text = "Unpaid"
                        setTextColor(0xFFDC2626.toInt())
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(6).toFloat()
                            setColor(0xFFFEE2E2.toInt())
                        }
                    }
                    card.addView(tvUnpaid)
                }

                // Interaction
                card.setOnClickListener {
                    if (isPaid) {
                        dialog.dismiss()
                        showLedgerSheet(name)
                    } else if (isCr) {
                        dialog.dismiss()
                        val colTitle = if (selectedCollection != "all") selectedCollection else (if (ongoingTitle.isNotBlank()) ongoingTitle else "Monthly Fund")
                        showAddFundSheet(
                            prefillProfileId = payer.optString("profile_id").ifBlank { null },
                            prefillStudentName = name,
                            prefillTitle = colTitle
                        )
                    } else {
                        val colName = if (selectedCollection != "all") selectedCollection else "batch fund"
                        Toast.makeText(this@ClassMateBatchFundActivity, "$name has not paid for $colName.", Toast.LENGTH_SHORT).show()
                    }
                }
                return card
            }

            fun createSectionHeader(title: String, isGreen: Boolean): View {
                val headerLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(2))
                    val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
                    layoutParams = lp
                }
                val tv = TextView(this).apply {
                    text = title
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isGreen) 0xFF15803D.toInt() else 0xFFB91C1C.toInt())
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12).toFloat()
                        setColor(if (isGreen) 0xFFDCFCE7.toInt() else 0xFFFEE2E2.toInt())
                    }
                }
                headerLayout.addView(tv)
                return headerLayout
            }

            if (filterMode == "all") {
                if (paidList.isNotEmpty()) {
                    container.addView(createSectionHeader("PAID CLASSMATES", true))
                    for (payer in paidList) {
                        container.addView(createStudentCard(payer))
                    }
                }
                if (unpaidList.isNotEmpty()) {
                    container.addView(createSectionHeader("UNPAID CLASSMATES", false))
                    for (payer in unpaidList) {
                        container.addView(createStudentCard(payer))
                    }
                }
            } else {
                val list = if (filterMode == "paid") paidList else unpaidList
                for (payer in list) {
                    container.addView(createStudentCard(payer))
                }
            }
        }

        tabUnpaid.setOnClickListener {
            HapticHelper.selectionTick(it)
            if (filterMode != "unpaid") {
                filterMode = "unpaid"
                renderList(currentSearchQuery)
            }
        }
        tabPaid.setOnClickListener {
            HapticHelper.selectionTick(it)
            if (filterMode != "paid") {
                filterMode = "paid"
                renderList(currentSearchQuery)
            }
        }
        tabAll.setOnClickListener {
            HapticHelper.selectionTick(it)
            if (filterMode != "all") {
                filterMode = "all"
                renderList(currentSearchQuery)
            }
        }

        fun fetchPayers() {
            payersJob?.cancel()
            val cachedPayers = ClassMateBatchFundCache.getPayers(this@ClassMateBatchFundActivity, batchId, selectedCollection)
            if (cachedPayers != null && cachedPayers.length() > 0) {
                allPayers.clear()
                val seenPayers = HashSet<String>()
                for (i in 0 until cachedPayers.length()) {
                    val payer = cachedPayers.getJSONObject(i)
                    val pid = payer.optString("profile_id").ifBlank { payer.optString("student_id") }
                    if (pid.isNotBlank() && !seenPayers.add(pid)) continue
                    allPayers.add(payer)
                }
                shimmerPayers.stopShimmer()
                shimmerPayers.visibility = View.GONE
                container.visibility = View.VISIBLE
                renderList(currentSearchQuery)
            } else {
                shimmerPayers.visibility = View.VISIBLE
                shimmerPayers.startShimmer()
                EmptyStateHelper.hide(emptyStatePayers)
                container.visibility = View.GONE
            }

            payersJob = lifecycleScope.launch {
                try {
                    val params = JSONObject().apply {
                        put("target_batch", batchId)
                        if (selectedCollection != "all") {
                            put("target_collection", selectedCollection)
                        }
                    }
                    val response = ClassMateAuthApi.rpcText("batch_fund_payers", params)
                    val array = JSONArray(response)
                    ClassMateBatchFundCache.savePayers(this@ClassMateBatchFundActivity, batchId, selectedCollection, array)
                    val seenPayers = HashSet<String>()
                    allPayers.clear()
                    for (i in 0 until array.length()) {
                        val payer = array.getJSONObject(i)
                        val pid = payer.optString("profile_id").ifBlank { payer.optString("student_id") }
                        if (pid.isNotBlank() && !seenPayers.add(pid)) continue
                        allPayers.add(payer)
                    }
                    shimmerPayers.stopShimmer()
                    shimmerPayers.visibility = View.GONE
                    container.visibility = View.VISIBLE
                    renderList(currentSearchQuery)
                } catch (e: Exception) {
                    if (e is CancellationException) return@launch
                    if (allPayers.isEmpty()) {
                        shimmerPayers.stopShimmer()
                        shimmerPayers.visibility = View.GONE
                        container.visibility = View.VISIBLE
                        container.removeAllViews()
                        EmptyStateHelper.showError(
                            emptyStatePayers,
                            title = "Could not load contributors",
                            errorMessage = e.message ?: "Failed to retrieve student records.",
                            onRetry = { fetchPayers() }
                        )
                    }
                }
            }
        }

        fun populateCollectionChips(colArray: JSONArray) {
            val collectionsList = mutableListOf<JSONObject>()
            for (i in 0 until colArray.length()) {
                val item = colArray.getJSONObject(i)
                if (item.optString("title").trim().isNotBlank()) {
                    collectionsList.add(item)
                }
            }
            collectionsList.sortWith { a, b ->
                val modA = a.optString("last_modified_at").ifBlank { a.optString("last_transacted_at") }
                val modB = b.optString("last_modified_at").ifBlank { b.optString("last_transacted_at") }
                modB.compareTo(modA)
            }
            val matching = collectionsList.firstOrNull {
                it.optString("title").equals(selectedCollection, ignoreCase = true)
            }
            if (matching != null) {
                selectedCollection = matching.optString("title")
            }
            chipGroupCollections.removeAllViews()

            val ongoingItem = if (ongoingTitle.isNotBlank()) {
                collectionsList.firstOrNull { it.optString("title").equals(ongoingTitle, ignoreCase = true) }
            } else null

            val remainingCollections = if (ongoingItem != null) {
                collectionsList.filterNot { it.optString("title").equals(ongoingTitle, ignoreCase = true) }
            } else {
                collectionsList
            }

            if (ongoingItem != null) {
                val colTitle = ongoingItem.optString("title").trim()
                val isInitialSelected = selectedCollection.equals(colTitle, ignoreCase = true)
                val chip = Chip(this@ClassMateBatchFundActivity).apply {
                    text = "★ $colTitle"
                    tag = colTitle
                    isCheckable = true
                    isChecked = isInitialSelected
                    setOnClickListener {
                        HapticHelper.selectionTick(it)
                        if (!selectedCollection.equals(colTitle, ignoreCase = true)) {
                            selectedCollection = colTitle
                            updateChipsVisualState()
                            fetchPayers()
                        }
                    }
                }
                chipGroupCollections.addView(chip)
            }

            for (item in remainingCollections) {
                val colTitle = item.optString("title").trim()
                val isInitialSelected = selectedCollection.equals(colTitle, ignoreCase = true)
                val chip = Chip(this@ClassMateBatchFundActivity).apply {
                    text = colTitle
                    tag = colTitle
                    isCheckable = true
                    isChecked = isInitialSelected
                    setOnClickListener {
                        HapticHelper.selectionTick(it)
                        if (!selectedCollection.equals(colTitle, ignoreCase = true)) {
                            selectedCollection = colTitle
                            updateChipsVisualState()
                            fetchPayers()
                        }
                    }
                }
                chipGroupCollections.addView(chip)
            }

            val allChip = Chip(this@ClassMateBatchFundActivity).apply {
                text = "All Collections"
                tag = "all"
                isCheckable = true
                isChecked = selectedCollection == "all"
                setOnClickListener {
                    HapticHelper.selectionTick(it)
                    if (selectedCollection != "all") {
                        selectedCollection = "all"
                        updateChipsVisualState()
                        fetchPayers()
                    }
                }
            }
            chipGroupCollections.addView(allChip)
            updateChipsVisualState()

            chipGroupCollections.post {
                for (i in 0 until chipGroupCollections.childCount) {
                    val chip = chipGroupCollections.getChildAt(i) as? Chip ?: continue
                    if (chip.isChecked) {
                        hsvCollections.smoothScrollTo(chip.left - dp(16), 0)
                        break
                    }
                }
            }
        }

        val cachedCols = ClassMateBatchFundCache.getCollections(this@ClassMateBatchFundActivity, batchId)
        if (cachedCols != null && cachedCols.length() > 0) {
            populateCollectionChips(cachedCols)
        }

        // Fetch dynamic collection titles sorted by latest modified first
        lifecycleScope.launch {
            try {
                val colResponse = ClassMateAuthApi.rpcText(
                    "batch_fund_collections",
                    JSONObject().put("target_batch", batchId)
                )
                val colArray = JSONArray(colResponse)
                ClassMateBatchFundCache.saveCollections(this@ClassMateBatchFundActivity, batchId, colArray)
                populateCollectionChips(colArray)
            } catch (_: Exception) {}

            fetchPayers()
        }

        etSearch.doAfterTextChanged {
            val q = it?.toString()?.trim().orEmpty()
            if (q == currentSearchQuery) return@doAfterTextChanged
            currentSearchQuery = q
            searchDebounceJob?.cancel()
            searchDebounceJob = lifecycleScope.launch {
                delay(200)
                renderList(currentSearchQuery)
            }
        }

        dialog.setOnDismissListener {
            payersJob?.cancel()
            searchDebounceJob?.cancel()
        }

        dialog.show()
    }

    // ==========================================
    // SUMMARY & SHARE STATEMENT SHEET
    // ==========================================
    private fun showSummarySheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_fund_summary_sheet, null, false)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.tvSumBalance).text = "৳ ${formatMoney(currentBalance)}"
        view.findViewById<TextView>(R.id.tvSumCollected).text = "৳ ${formatMoney(totalCollected)}"
        view.findViewById<TextView>(R.id.tvSumSpent).text = "৳ ${formatMoney(totalSpent)}"
        view.findViewById<TextView>(R.id.tvSumCount).text = "$txCount"

        view.findViewById<MaterialButton>(R.id.btnShareStatement).setOnClickListener {
            shareStatement()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareStatement() {
        val text = """
            📊 *Batch Fund Statement*
            🎓 $batchName • $university

            💰 *Current Balance:* ৳ ${formatMoney(currentBalance)}
            📥 *Total Collected:* ৳ ${formatMoney(totalCollected)}
            📤 *Total Spent:* ৳ ${formatMoney(totalSpent)}
            📝 *Total Transactions:* $txCount

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

    // ==========================================
    // EDIT / DELETE TRANSACTION DIALOG
    // ==========================================
    private fun showTransactionActionsDialog(tx: JSONObject) {
        val id = tx.optString("id")
        val currentTitle = tx.optString("title")
        val currentAmount = tx.optDouble("amount")
        val currentStudent = tx.optString("student_name")
        val isDeposit = tx.optString("type") == "inflow"

        val options = arrayOf("Edit Transaction", "Delete Transaction")
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isDeposit) "Manage Deposit" else "Manage Expense")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditTransactionDialog(tx)
                    1 -> confirmDeleteTransaction(id, currentTitle, currentAmount)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditTransactionDialog(tx: JSONObject) {
        val id = tx.optString("id")
        val view = layoutInflater.inflate(R.layout.dialog_edit_transaction, null, false)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etEditAmount)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etEditTitle)
        val etStudent = view.findViewById<TextInputEditText>(R.id.etEditStudentName)

        val isDeposit = tx.optString("type", "inflow") == "inflow"
        val curAmt = tx.optDouble("amount", 0.0)
        etAmount.setText(if (curAmt == curAmt.toLong().toDouble()) curAmt.toLong().toString() else curAmt.toString())
        etTitle.setText(tx.optString("title"))
        val currentStudent = tx.optString("student_name")
        if (currentStudent.isNotBlank() && currentStudent != "null") {
            etStudent.setText(currentStudent)
        }
        if (!isDeposit) {
            (etStudent.parent?.parent as? View)?.visibility = View.GONE
        }

        val tilEditAmount = view.findViewById<TextInputLayout>(R.id.tilEditAmount)
        etAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tilEditAmount.error = null
                val raw = s?.toString()?.toDoubleOrNull()
                if (raw != null && raw > 0) {
                    val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(raw)
                    tilEditAmount.helperText = "৳ $formatted"
                } else {
                    tilEditAmount.helperText = null
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        etAmount.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                etTitle.requestFocus()
                true
            } else false
        }

        etTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT && isDeposit) {
                etStudent.requestFocus()
                true
            } else false
        }

        val editDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit Transaction")
            .setView(view)
            .setPositiveButton("Save Changes") { _, _ ->
                val newAmt = etAmount.text?.toString()?.toDoubleOrNull() ?: return@setPositiveButton
                val newTitle = etTitle.text?.toString()?.trim().orEmpty()
                val newStudent = etStudent.text?.toString()?.trim().orEmpty()

                if (newAmt <= 0 || newTitle.isBlank()) {
                    Toast.makeText(this, "Invalid amount or title", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val origStudent = tx.optString("student_name").trim()
                var updatedProfileId = tx.optString("student_profile_id").ifBlank { null }
                var updatedStudentId = tx.optString("student_id").ifBlank { null }
                if (isDeposit) {
                    if (!newStudent.equals(origStudent, ignoreCase = true)) {
                        val matched = batchRoster.firstOrNull {
                            it.optString("full_name").trim().equals(newStudent, ignoreCase = true) ||
                            it.optString("student_id").trim().equals(newStudent, ignoreCase = true)
                        }
                        updatedProfileId = matched?.let { it.optString("id").ifBlank { it.optString("profile_id") }.ifBlank { null } }
                        updatedStudentId = matched?.optString("student_id")?.takeIf { s -> s.isNotBlank() && s != "null" }
                    }
                } else {
                    updatedProfileId = null
                    updatedStudentId = null
                }

                lifecycleScope.launch {
                    try {
                        ClassMateAuthApi.rpc(
                            "update_batch_fund_transaction",
                            JSONObject().apply {
                                put("trans_id", id)
                                put("target_transaction", id)
                                put("trans_type", tx.optString("type", "inflow"))
                                put("trans_amount", newAmt)
                                put("new_amount", newAmt)
                                put("trans_title", newTitle)
                                put("new_title", newTitle)
                                put("trans_student_name", newStudent.ifBlank { null })
                                put("new_student_name", newStudent.ifBlank { null })
                                put("trans_student_id", updatedStudentId)
                                put("trans_date", tx.optString("transacted_at").ifBlank { null })
                                put("trans_student_profile_id", updatedProfileId)
                            }
                        )
                        Toast.makeText(this@ClassMateBatchFundActivity, "Transaction updated!", Toast.LENGTH_SHORT).show()
                        loadData()
                    } catch (e: Exception) {
                        Toast.makeText(this@ClassMateBatchFundActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        editDialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editDialog.show()
    }

    private fun confirmDeleteTransaction(id: String, title: String, amount: Double) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Transaction")
            .setMessage("Are you sure you want to delete '$title' (৳ ${formatMoney(amount)})? This will adjust the fund balance.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ClassMateAuthApi.rpc(
                            "delete_batch_fund_transaction",
                            JSONObject().apply {
                                put("trans_id", id)
                                put("target_transaction", id)
                            }
                        )
                        Toast.makeText(this@ClassMateBatchFundActivity, "Transaction deleted", Toast.LENGTH_SHORT).show()
                        loadData()
                    } catch (e: Exception) {
                        Toast.makeText(this@ClassMateBatchFundActivity, "Error deleting: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==========================================
    // EDIT FUND DESCRIPTION
    // ==========================================
    private fun showEditDescriptionDialog() {
        val input = TextInputEditText(this).apply {
            setText(fundDescription)
            hint = "Fund purpose or notes"
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Batch Fund Purpose")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newDesc = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    try {
                        ClassMateAuthApi.rpc(
                            "update_batch_fund_description",
                            JSONObject().apply {
                                put("target_batch", batchId)
                                put("new_description", newDesc)
                            }
                        )
                        fundDescription = newDesc
                        tvFundDescription.text = newDesc
                        Toast.makeText(this@ClassMateBatchFundActivity, "Purpose updated!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@ClassMateBatchFundActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatMoney(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            String.format(java.util.Locale.US, "%,d", amount.toLong())
        } else {
            String.format(java.util.Locale.US, "%,.2f", amount)
        }
    }
}
