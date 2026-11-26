package com.shuaib.classmate.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.ClassMateHaptics
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Dedicated, modern Batch Administration workspace for Class Representatives,
 * Teachers, and System Admins. Replaces legacy nested manage dialog menus with
 * organized, categorised action cards and instant batch telemetry.
 */
class ClassMateBatchAdminActivity : AppCompatActivity() {

    private var batchId: String = ""
    private var batchLabel: String = ""
    private var role: String = ""
    private var profileId: String = ""
    private var isServerOwner: Boolean = false
    private var isCr: Boolean = false

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvHeroBatchTitle: TextView
    private lateinit var tvHeroBatchSubtitle: TextView
    private lateinit var tvStatSemester: TextView
    private lateinit var tvStatStudents: TextView
    private lateinit var tvStatCourses: TextView

    private lateinit var tileManageCourses: View
    private lateinit var tileBatchOverrides: View
    private lateinit var tileActiveSemester: View
    private lateinit var layoutAdminSection: View
    private lateinit var tileAllUsersRoles: View
    private lateinit var tileCourseCatalog: View
    private lateinit var tileSystemHealth: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_admin)
        ClassMateAuthApi.attach(applicationContext)

        batchId = intent.getStringExtra("batch_id")
            ?: intent.getStringExtra("EXTRA_BATCH_ID").orEmpty()
        batchLabel = intent.getStringExtra("batch_label")
            ?: intent.getStringExtra("EXTRA_BATCH_LABEL").orEmpty()
        role = intent.getStringExtra("role")
            ?: intent.getStringExtra("EXTRA_ROLE").orEmpty()
        profileId = intent.getStringExtra("profile_id")
            ?: intent.getStringExtra("EXTRA_PROFILE_ID").orEmpty()
        isServerOwner = intent.getBooleanExtra("is_server_owner", false) || intent.getBooleanExtra("EXTRA_IS_SERVER_OWNER", false)
        isCr = intent.getBooleanExtra("is_cr", false) || intent.getBooleanExtra("EXTRA_IS_CR", false)

        initViews()
        setupActionCards()
        loadBatchData()
    }

    private fun initViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.cm_primary))
        swipeRefresh.setOnRefreshListener { loadBatchData() }

        tvHeroBatchTitle = findViewById(R.id.tvHeroBatchTitle)
        tvHeroBatchSubtitle = findViewById(R.id.tvHeroBatchSubtitle)
        tvStatSemester = findViewById(R.id.tvStatSemester)
        tvStatStudents = findViewById(R.id.tvStatStudents)
        tvStatCourses = findViewById(R.id.tvStatCourses)

        tileManageCourses = findViewById(R.id.tileManageCourses)
        tileBatchOverrides = findViewById(R.id.tileBatchOverrides)
        tileActiveSemester = findViewById(R.id.tileActiveSemester)
        layoutAdminSection = findViewById(R.id.layoutAdminSection)
        tileAllUsersRoles = findViewById(R.id.tileAllUsersRoles)
        tileCourseCatalog = findViewById(R.id.tileCourseCatalog)
        tileSystemHealth = findViewById(R.id.tileSystemHealth)

        if (batchLabel.isNotBlank()) {
            tvHeroBatchTitle.text = batchLabel
        }
    }

    private fun setupActionCards() {
        // --- Section 1: Batch & Academic Management (CR, Teacher & Admin) ---
        val isTeacherOnly = role == "teacher" && !isServerOwner && !isCr
        bindActionCard(
            cardView = tileManageCourses,
            title = "Manage Courses",
            subtitle = if (isTeacherOnly) "View and manage your assigned courses" else "Add, edit and organize courses and teacher assignments",
            iconRes = R.drawable.ic_library_filled,
            accentColor = Color.parseColor("#3B82F6")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "courses", isServerOwner, isCr)
            startActivity(intent)
        }

        if (isTeacherOnly) {
            tileBatchOverrides.visibility = View.GONE
            tileActiveSemester.visibility = View.GONE
        } else {
            tileBatchOverrides.visibility = View.VISIBLE
            tileActiveSemester.visibility = View.VISIBLE
            bindActionCard(
                cardView = tileBatchOverrides,
                title = "Batch Overrides & Dropouts",
                subtitle = if (isServerOwner || role == "admin") "Manage retake students across any batch" else "Add retake or repeat students to your batch",
                iconRes = R.drawable.ic_refresh,
                accentColor = Color.parseColor("#EC4899")
            ) {
                val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "overrides", isServerOwner, isCr)
                startActivity(intent)
            }

            bindActionCard(
                cardView = tileActiveSemester,
                title = "Switch Active Semester",
                subtitle = "Advance to next semester (keeps all batch notices)",
                iconRes = R.drawable.ic_academic_cap,
                accentColor = Color.parseColor("#06B6D4")
            ) {
                showSemesterSwitchDialog()
            }
        }

        // --- Section 2: Admin Only (Server Owner & Admins) ---
        val canAccessAdminTools = isServerOwner || role == "admin"
        layoutAdminSection.visibility = if (canAccessAdminTools) View.VISIBLE else View.GONE

        if (canAccessAdminTools) {
            bindActionCard(
                cardView = tileAllUsersRoles,
                title = "All Users & Roles",
                subtitle = "View all batch members, accounts and manage user permissions",
                iconRes = R.drawable.ic_users,
                accentColor = Color.parseColor("#0EA5E9")
            ) {
                val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "people", isServerOwner, isCr)
                startActivity(intent)
            }

            bindActionCard(
                cardView = tileCourseCatalog,
                title = "Global Course Catalog",
                subtitle = "Master curriculum database across all batches",
                iconRes = R.drawable.ic_storage,
                accentColor = Color.parseColor("#64748B")
            ) {
                val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "catalog", isServerOwner, isCr)
                startActivity(intent)
            }

            bindActionCard(
                cardView = tileSystemHealth,
                title = "System Health & Diagnostics",
                subtitle = "Monitor delivery queue, storage & database growth",
                iconRes = R.drawable.ic_monitor,
                accentColor = Color.parseColor("#14B8A6")
            ) {
                ClassMateHealthDialog.show(this, lifecycleScope)
            }
        }
    }

    private fun bindActionCard(
        cardView: View,
        title: String,
        subtitle: String,
        @DrawableRes iconRes: Int,
        accentColor: Int,
        onClick: () -> Unit
    ) {
        cardView.findViewById<TextView>(R.id.tvActionTitle).text = title
        cardView.findViewById<TextView>(R.id.tvActionSubtitle).text = subtitle
        cardView.findViewById<ImageView>(R.id.ivActionIcon).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accentColor)
        }
        val alphaBg = ColorUtils.setAlphaComponent(accentColor, 32)
        cardView.findViewById<FrameLayout>(R.id.iconContainer).backgroundTintList = ColorStateList.valueOf(alphaBg)
        cardView.setOnClickListener {
            ClassMateHaptics.selection(it)
            onClick()
        }
    }

    private fun loadBatchData() {
        if (batchId.isBlank()) {
            swipeRefresh.isRefreshing = false
            return
        }
        swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                // 1. Fetch batch & department
                val batchObj = ClassMateAuthApi.rows(
                    "batches",
                    "select=batch_number,academic_session,department_id,departments(code,name)&id=eq.$batchId&limit=1"
                ).optJSONObject(0)

                if (batchObj != null) {
                    val bNum = batchObj.optInt("batch_number")
                    val deptObj = batchObj.optJSONObject("departments")
                    val deptCode = deptObj?.optString("code")?.uppercase() ?: "MBSTU"
                    val deptName = deptObj?.optString("name") ?: "Department of $deptCode"
                    val sess = ClassMateAcademicSession.format(batchObj.optInt("academic_session"))

                    tvHeroBatchTitle.text = "$deptCode Batch $bNum"
                    tvHeroBatchSubtitle.text = "$deptName • Session $sess"
                    batchLabel = "$deptCode Batch $bNum • Session $sess"
                }

                // 2. Fetch active semester
                val activeSemObj = ClassMateAuthApi.rows(
                    "semesters",
                    "select=id,semester_number,status&batch_id=eq.$batchId&status=eq.active&limit=1"
                ).optJSONObject(0)

                val activeSemNum = activeSemObj?.optInt("semester_number")
                val activeSemId = activeSemObj?.optString("id").orEmpty()

                if (activeSemNum != null && activeSemNum > 0) {
                    tvStatSemester.text = "Sem $activeSemNum"
                    tileActiveSemester.findViewById<TextView>(R.id.tvActionSubtitle).text =
                        "Active: Semester $activeSemNum · Tap to switch or publish"
                } else {
                    tvStatSemester.text = "None"
                    tileActiveSemester.findViewById<TextView>(R.id.tvActionSubtitle).text =
                        "No active semester · Tap to publish draft"
                }

                // 3. Student count
                val studentRows = ClassMateAuthApi.rows(
                    "profiles",
                    "select=id&batch_id=eq.$batchId&verification_status=eq.active"
                )
                tvStatStudents.text = "${studentRows.length()}"

                // 4. Course count
                val courseCount = if (activeSemId.isNotBlank()) {
                    val semCourses = ClassMateAuthApi.rows(
                        "semester_courses",
                        "select=id&semester_id=eq.$activeSemId"
                    )
                    semCourses.length()
                } else {
                    val catalog = runCatching { ClassMateCourses.catalog(batchId) }.getOrNull()
                    catalog?.length() ?: 0
                }
                tvStatCourses.text = "$courseCount"

            } catch (_: Exception) {
                // Non-fatal network fallback
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showSemesterSwitchDialog() {
        lifecycleScope.launch {
            try {
                val rows = ClassMateAuthApi.rows(
                    "semesters",
                    "select=id,semester_number,status&batch_id=eq.$batchId&order=semester_number"
                )
                if (rows.length() == 0) {
                    Toast.makeText(this@ClassMateBatchAdminActivity, "No semesters found for this batch.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val semesters = (0 until rows.length()).map { rows.getJSONObject(it) }
                val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this@ClassMateBatchAdminActivity)
                val d = resources.displayMetrics.density
                val rootLayout = android.widget.LinearLayout(this@ClassMateBatchAdminActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
                    setBackgroundColor(getColor(R.color.cm_surface))
                }
                rootLayout.addView(View(this@ClassMateBatchAdminActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (4 * d).toInt()).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (14 * d).toInt()
                    }
                    setBackgroundColor(getColor(R.color.cm_border))
                })
                rootLayout.addView(TextView(this@ClassMateBatchAdminActivity).apply {
                    text = "Switch Active Semester"
                    textSize = 19f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_primary))
                })
                rootLayout.addView(TextView(this@ClassMateBatchAdminActivity).apply {
                    text = "Courses, timetable, files & assessments switch cleanly. Batch notices stay preserved."
                    textSize = 12.5f
                    setTextColor(getColor(R.color.cm_text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = (4 * d).toInt()
                        bottomMargin = (14 * d).toInt()
                    }
                })

                val scroll = android.widget.ScrollView(this@ClassMateBatchAdminActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.52).toInt())
                }
                val listLayout = android.widget.LinearLayout(this@ClassMateBatchAdminActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

                semesters.forEach { selected ->
                    val semNum = selected.optInt("semester_number")
                    val semId = selected.optString("id")
                    val status = selected.optString("status")
                    val isCurrent = status == "active"

                    val itemCard = com.google.android.material.card.MaterialCardView(this@ClassMateBatchAdminActivity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
                        radius = 14 * d
                        cardElevation = 0f
                        setCardBackgroundColor(getColor(R.color.cm_card))
                        strokeColor = if (isCurrent) getColor(R.color.cm_primary) else getColor(R.color.cm_border)
                        strokeWidth = if (isCurrent) (2 * d).toInt() else (1 * d).toInt()
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            ClassMateHaptics.selection(it)
                            if (isCurrent) {
                                Toast.makeText(this@ClassMateBatchAdminActivity, "Semester $semNum is already active", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            sheet.dismiss()
                            val actionText = if (status == "completed") "Reactivate" else "Switch to"
                            val messageText = "This switches the whole batch to Semester $semNum. Previous semester courses, routine, files, and assessments will be hidden, while all batch notices remain intact."
                            MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                                .setTitle("$actionText Semester $semNum?")
                                .setMessage(messageText)
                                .setPositiveButton("Confirm Switch") { _, _ ->
                                    lifecycleScope.launch {
                                        try {
                                            ClassMateAuthApi.rpc("publish_semester", JSONObject().put("target_semester", semId))
                                            ClassMateAcademicCache.clearSemesterScopedCaches(this@ClassMateBatchAdminActivity, batchId)
                                            ClassMateAcademicCache.syncActiveSemester(this@ClassMateBatchAdminActivity, batchId, semId)
                                            setResult(RESULT_OK)
                                            Toast.makeText(this@ClassMateBatchAdminActivity, "Batch switched to Semester $semNum!", Toast.LENGTH_SHORT).show()
                                            loadBatchData()
                                        } catch (e: Exception) {
                                            Toast.makeText(this@ClassMateBatchAdminActivity, "Failed: ${com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }

                    val row = android.widget.LinearLayout(this@ClassMateBatchAdminActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                    }
                    row.addView(TextView(this@ClassMateBatchAdminActivity).apply {
                        text = "Semester $semNum"
                        textSize = 15f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(getColor(R.color.cm_text_primary))
                    }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))

                    row.addView(TextView(this@ClassMateBatchAdminActivity).apply {
                        text = when (status) {
                            "active" -> "ACTIVE"
                            "completed" -> "COMPLETED"
                            else -> "DRAFT"
                        }
                        textSize = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
                        when (status) {
                            "active" -> {
                                setBackgroundResource(R.drawable.bg_badge_green)
                                setTextColor(Color.parseColor("#10B981"))
                            }
                            "completed" -> {
                                setBackgroundResource(R.drawable.bg_badge_blue)
                                setTextColor(getColor(R.color.cm_primary))
                            }
                            else -> {
                                setBackgroundResource(R.drawable.bg_badge_paused)
                                setTextColor(getColor(R.color.cm_text_muted))
                            }
                        }
                    })

                    itemCard.addView(row)
                    listLayout.addView(itemCard)
                }

                scroll.addView(listLayout)
                rootLayout.addView(scroll)
                sheet.setContentView(rootLayout)
                sheet.show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchAdminActivity, "Could not load semesters: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
