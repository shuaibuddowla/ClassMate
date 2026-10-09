package com.shuaib.classmate.activities

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
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
    private lateinit var tvEmptyActivity: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

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
        tvEmptyActivity = findViewById(R.id.tvEmptyActivity)

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
            if (!isCr) {
                Toast.makeText(this, "Only Class Representatives can record transactions.", Toast.LENGTH_SHORT).show()
            } else {
                showAddFundSheet()
            }
        }

        findViewById<View>(R.id.tileViewLedger).setOnClickListener {
            showLedgerSheet()
        }

        findViewById<View>(R.id.btnSeeAllActivity).setOnClickListener {
            showLedgerSheet()
        }

        findViewById<View>(R.id.tilePayers).setOnClickListener {
            showPayersSheet()
        }

        findViewById<View>(R.id.tileSummary).setOnClickListener {
            showSummarySheet()
        }

        findViewById<View>(R.id.cardBottomDesc).setOnClickListener {
            if (isCr) {
                showEditDescriptionDialog()
            }
        }

        lifecycleScope.launch {
            if (!ClassMateAuthApi.restoreSession()) {
                finish()
                return@launch
            }
            if (batchId.isBlank()) {
                batchId = ClassMateAuthApi.initializeProfile().optString("batch_id")
            }
            loadData()
            loadBatchRoster()
        }
    }

    private fun loadBatchRoster() {
        if (batchId.isBlank()) return
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
                    if (p.optString("role") != "teacher") {
                        list.add(p)
                    }
                }
                batchRoster = list
            } catch (_: Exception) {}
        }
    }

    private fun loadData() {
        if (batchId.isBlank()) return
        lifecycleScope.launch {
            try {
                // Fetch summary
                val summary = ClassMateAuthApi.rpc(
                    "batch_fund_summary",
                    JSONObject().put("target_batch", batchId)
                )

                currentBalance = summary.optDouble("current_balance", 0.0)
                totalCollected = summary.optDouble("total_collected", 0.0)
                totalSpent = summary.optDouble("total_spent", 0.0)
                txCount = summary.optInt("transaction_count", 0)
                isCr = summary.optBoolean("is_cr", false)
                fundDescription = summary.optString("fund_description", "Used for mess, events, jersey, and other batch expenses.")
                batchName = summary.optString("batch_name", batchLabel.ifBlank { "Batch Fund" })
                university = summary.optString("university", "MBSTU")

                // Update UI
                toolbar.title = "Batch Fund"
                toolbar.subtitle = "$batchName • $university"
                tvCurrentBalance.text = "৳ ${formatMoney(currentBalance)}"
                tvBalanceStats.text = "Total Collected: ৳ ${formatMoney(totalCollected)} • Total Spent: ৳ ${formatMoney(totalSpent)}"
                tvFundDescription.text = fundDescription
                ivEditDescHint.visibility = if (isCr) View.VISIBLE else View.GONE

                // Load recent 5 transactions
                loadRecentTransactions()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchFundActivity, "Error loading fund: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun loadRecentTransactions() {
        try {
            val responseText = ClassMateAuthApi.rpcText(
                "batch_fund_transactions",
                JSONObject().apply {
                    put("target_batch", batchId)
                    put("filter_type", "all")
                    put("query_text", "")
                    put("result_offset", 0)
                    put("page_limit", 5)
                }
            )
            val txArray = JSONArray(responseText)
            recentActivityContainer.removeAllViews()

            if (txArray.length() == 0) {
                tvEmptyActivity.visibility = View.VISIBLE
            } else {
                tvEmptyActivity.visibility = View.GONE
                for (i in 0 until txArray.length()) {
                    val tx = txArray.getJSONObject(i)
                    recentActivityContainer.addView(createTransactionRow(tx))
                }
            }
        } catch (e: Exception) {
            tvEmptyActivity.visibility = View.VISIBLE
            tvEmptyActivity.text = "Could not load recent activity."
        }
    }

    private fun createTransactionRow(tx: JSONObject): View {
        val isInflow = tx.optString("type") == "inflow"
        val amount = tx.optDouble("amount", 0.0)
        val title = tx.optString("title")
        val studentName = tx.optString("student_name")
        val dateStr = tx.optString("transacted_at")
        val canEdit = tx.optBoolean("can_edit", isCr)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.bg_fund_card)
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            isClickable = canEdit
            isFocusable = canEdit
            if (canEdit) {
                setOnClickListener { showTransactionActionsDialog(tx) }
            }
        }

        // Avatar
        val avatar = FrameLayout(this).apply {
            val size = dp(40)
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
            textSize = 16f
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
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            val subText = if (studentName.isNotBlank() && studentName != "null") {
                "$title • $dateStr"
            } else {
                "From batch fund • $dateStr"
            }
            text = subText
            setTextColor(getColor(R.color.cm_text_secondary))
            textSize = 12f
        }
        infoCol.addView(tvMainTitle)
        infoCol.addView(tvSub)
        card.addView(infoCol)

        // Amount
        val tvAmount = TextView(this).apply {
            text = if (isInflow) "+ ৳ ${formatMoney(amount)}" else "- ৳ ${formatMoney(amount)}"
            setTextColor(if (isInflow) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(tvAmount)

        return card
    }

    // ==========================================
    // ADD FUND SHEET (DIRECT MANUAL ENTRY & INSTANT ROSTER SEARCH)
    // ==========================================
    private fun showAddFundSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_fund_sheet, null, false)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
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

        var selectedStudentProfileId: String? = null
        var selectedStudentName: String? = null

        // Quick reason chips
        val commonReasons = listOf("Monthly Fund", "Jersey", "Tour", "Picnic", "Farewell", "Batch Feast")
        chipGroupReasons.removeAllViews()
        for (r in commonReasons) {
            val chip = Chip(this).apply {
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

        actvStudent.setOnItemClickListener { parent, _, position, _ ->
            val opt = parent.getItemAtPosition(position) as? StudentOption
            if (opt != null) {
                selectedStudentProfileId = opt.profileId
                selectedStudentName = opt.fullName
                actvStudent.setText(opt.fullName, false)
                actvStudent.setSelection(opt.fullName.length)
                tilStudent.error = null
            }
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
                        it.display.equals(studentRawName, ignoreCase = true)
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
                tilAmount.error = "Enter a valid amount"
                etAmount.requestFocus()
                return
            }
            tilAmount.error = null

            if (title.isBlank()) {
                tilTitle.error = if (isDeposit) "Enter reason of collection" else "Enter expense purpose"
                etTitle.requestFocus()
                return
            }
            tilTitle.error = null

            val btnToDisable = if (keepOpen) btnSaveAndAddAnother else btnSaveManual
            btnToDisable.isEnabled = false

            lifecycleScope.launch {
                try {
                    val type = if (isDeposit) "inflow" else "outflow"
                    val sName = if (isDeposit) (selectedStudentName ?: studentRawName) else null

                    ClassMateAuthApi.rpc(
                        "record_batch_fund_transaction",
                        JSONObject().apply {
                            put("target_batch", batchId)
                            put("trans_type", type)
                            put("trans_amount", amt)
                            put("trans_title", title)
                            put("trans_student_name", sName)
                            put("trans_date", todayStr)
                            if (isDeposit && !selectedStudentProfileId.isNullOrBlank()) {
                                put("trans_student_profile_id", selectedStudentProfileId)
                            }
                        }
                    )

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
                    btnToDisable.isEnabled = true
                    Toast.makeText(this@ClassMateBatchFundActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnSaveAndAddAnother.setOnClickListener { performSave(keepOpen = true) }
        btnSaveManual.setOnClickListener { performSave(keepOpen = false) }

        dialog.show()
    }

    // ==========================================
    // VIEW LEDGER SHEET
    // ==========================================
    private fun showLedgerSheet() {
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
        val tvEmpty = view.findViewById<TextView>(R.id.tvLedgerEmpty)
        val progressBar = view.findViewById<ProgressBar>(R.id.ledgerProgressBar)

        var currentFilter = "all"
        var currentQuery = ""

        fun fetchLedger() {
            progressBar.visibility = View.VISIBLE
            container.removeAllViews()
            tvEmpty.visibility = View.GONE

            lifecycleScope.launch {
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
                    progressBar.visibility = View.GONE
                    val array = JSONArray(response)
                    if (array.length() == 0) {
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        tvEmpty.visibility = View.GONE
                        for (i in 0 until array.length()) {
                            val tx = array.getJSONObject(i)
                            val row = createTransactionRow(tx)
                            if (isCr) {
                                row.setOnClickListener {
                                    dialog.dismiss()
                                    showTransactionActionsDialog(tx)
                                }
                            }
                            container.addView(row)
                        }
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Error loading ledger: ${e.message}"
                }
            }
        }

        tabFilter.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilter = when (tab?.position) {
                    1 -> "inflow"
                    2 -> "outflow"
                    else -> "all"
                }
                fetchLedger()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.doAfterTextChanged {
            currentQuery = it?.toString()?.trim().orEmpty()
            fetchLedger()
        }

        fetchLedger()
        dialog.show()
    }

    // ==========================================
    // PAYERS TRACKER SHEET
    // ==========================================
    private fun showPayersSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_fund_payers_sheet, null, false)
        dialog.setContentView(view)

        val etSearch = view.findViewById<TextInputEditText>(R.id.etPayersSearch)
        val container = view.findViewById<LinearLayout>(R.id.payersItemsContainer)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPayersEmpty)
        val progressBar = view.findViewById<ProgressBar>(R.id.payersProgressBar)

        var allPayers = mutableListOf<JSONObject>()

        fun renderList(query: String) {
            container.removeAllViews()
            val filtered = allPayers.filter {
                val name = it.optString("full_name").lowercase()
                val sid = it.optString("student_id").lowercase()
                query.isBlank() || name.contains(query.lowercase()) || sid.contains(query.lowercase())
            }

            if (filtered.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                for (payer in filtered) {
                    val name = payer.optString("full_name")
                    val sid = payer.optString("student_id")
                    val totalPaid = payer.optDouble("total_paid", 0.0)
                    val count = payer.optInt("payment_count", 0)

                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = getDrawable(R.drawable.bg_fund_card)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                    }

                    // Avatar
                    val avatar = FrameLayout(this).apply {
                        val size = dp(38)
                        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (totalPaid > 0) 0xFFDCFCE7.toInt() else 0xFFF1F5F9.toInt())
                        }
                    }
                    val tvInit = TextView(this).apply {
                        text = name.take(1).uppercase()
                        setTextColor(if (totalPaid > 0) 0xFF16A34A.toInt() else 0xFF64748B.toInt())
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
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val tvSid = TextView(this).apply {
                        text = if (sid.isNotBlank() && sid != "null") "ID: $sid • $count deposits" else "$count deposits"
                        setTextColor(getColor(R.color.cm_text_secondary))
                        textSize = 12f
                    }
                    info.addView(tvName)
                    info.addView(tvSid)
                    card.addView(info)

                    // Paid Badge
                    val tvPaid = TextView(this).apply {
                        text = if (totalPaid > 0) "৳ ${formatMoney(totalPaid)}" else "Unpaid"
                        setTextColor(if (totalPaid > 0) 0xFF16A34A.toInt() else 0xFF94A3B8.toInt())
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    card.addView(tvPaid)

                    container.addView(card)
                }
            }
        }

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            try {
                val response = ClassMateAuthApi.rpcText(
                    "batch_fund_payers",
                    JSONObject().put("target_batch", batchId)
                )
                val array = JSONArray(response)
                allPayers.clear()
                for (i in 0 until array.length()) {
                    allPayers.add(array.getJSONObject(i))
                }
                progressBar.visibility = View.GONE
                renderList("")
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Error loading payers: ${e.message}"
            }
        }

        etSearch.doAfterTextChanged {
            renderList(it?.toString()?.trim().orEmpty())
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

        etAmount.setText(tx.optDouble("amount").toString())
        etTitle.setText(tx.optString("title"))
        val currentStudent = tx.optString("student_name")
        if (currentStudent.isNotBlank() && currentStudent != "null") {
            etStudent.setText(currentStudent)
        }

        MaterialAlertDialogBuilder(this)
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
                                put("trans_student_id", tx.optString("student_id").ifBlank { null })
                                put("trans_date", tx.optString("transacted_at").ifBlank { null })
                                put("trans_student_profile_id", tx.optString("student_profile_id").ifBlank { null })
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
            .show()
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
            String.format("%,d", amount.toLong())
        } else {
            String.format("%,.2f", amount)
        }
    }
}
