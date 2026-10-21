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
    private lateinit var tileTimetable: View
    private lateinit var tileTeacherAssignments: View
    private lateinit var tileStudentRoster: View
    private lateinit var tileManageCr: View
    private lateinit var tileBatchOverrides: View
    private lateinit var tileActiveSemester: View
    private lateinit var tileCloneSemester: View
    private lateinit var tileCourseCatalog: View
    private lateinit var tileSystemHealth: View
    private lateinit var headerSystemSection: View

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
        tileTimetable = findViewById(R.id.tileTimetable)
        tileTeacherAssignments = findViewById(R.id.tileTeacherAssignments)
        tileStudentRoster = findViewById(R.id.tileStudentRoster)
        tileManageCr = findViewById(R.id.tileManageCr)
        tileBatchOverrides = findViewById(R.id.tileBatchOverrides)
        tileActiveSemester = findViewById(R.id.tileActiveSemester)
        tileCloneSemester = findViewById(R.id.tileCloneSemester)
        tileCourseCatalog = findViewById(R.id.tileCourseCatalog)
        tileSystemHealth = findViewById(R.id.tileSystemHealth)
        headerSystemSection = findViewById(R.id.headerSystemSection)

        if (batchLabel.isNotBlank()) {
            tvHeroBatchTitle.text = batchLabel
        }
    }

    private fun setupActionCards() {
        // --- Section 1: Courses & Routine ---
        bindActionCard(
            cardView = tileManageCourses,
            title = "Manage Courses",
            subtitle = "Add, edit and organize courses for this batch",
            iconRes = R.drawable.ic_library_filled,
            accentColor = Color.parseColor("#3B82F6")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "courses")
            startActivity(intent)
        }

        bindActionCard(
            cardView = tileTimetable,
            title = "Class Timetable & Schedule",
            subtitle = "Configure weekly schedule, rooms, periods & timing",
            iconRes = R.drawable.ic_timetable,
            accentColor = Color.parseColor("#10B981")
        ) {
            startActivity(Intent(this, TimetableManagementActivity::class.java))
        }

        bindActionCard(
            cardView = tileTeacherAssignments,
            title = "Teacher Assignments",
            subtitle = "Assign instructors to courses and manage teaching access",
            iconRes = R.drawable.ic_person,
            accentColor = Color.parseColor("#8B5CF6")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "teachers")
            startActivity(intent)
        }

        // --- Section 2: Members & Permissions ---
        bindActionCard(
            cardView = tileStudentRoster,
            title = "Student Roster & Verification",
            subtitle = "View enrolled students, verify accounts & manage roles",
            iconRes = R.drawable.ic_users,
            accentColor = Color.parseColor("#0EA5E9")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "people")
            startActivity(intent)
        }

        bindActionCard(
            cardView = tileManageCr,
            title = "Class Representatives (CR)",
            subtitle = "Appoint or revoke Class Representative permissions",
            iconRes = R.drawable.ic_badge,
            accentColor = Color.parseColor("#F59E0B")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "people")
            startActivity(intent)
        }

        bindActionCard(
            cardView = tileBatchOverrides,
            title = "Batch Overrides & Dropouts",
            subtitle = "Manage retake students and cross-batch overrides",
            iconRes = R.drawable.ic_refresh,
            accentColor = Color.parseColor("#EC4899")
        ) {
            val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "overrides")
            startActivity(intent)
        }

        // --- Section 3: Semester & Progression ---
        bindActionCard(
            cardView = tileActiveSemester,
            title = "Switch Active Semester",
            subtitle = "Publish new semester or change active progression",
            iconRes = R.drawable.ic_academic_cap,
            accentColor = Color.parseColor("#06B6D4")
        ) {
            showSemesterSwitchDialog()
        }

        bindActionCard(
            cardView = tileCloneSemester,
            title = "Clone / Copy Semester Plan",
            subtitle = "Duplicate routine, courses & teachers to a new semester",
            iconRes = R.drawable.ic_copy,
            accentColor = Color.parseColor("#6366F1")
        ) {
            showCloneSemesterDialog()
        }

        // --- Section 4: System & Global Tools ---
        val canAccessGlobalTools = isServerOwner || role == "admin"
        if (canAccessGlobalTools) {
            headerSystemSection.visibility = View.VISIBLE
            tileCourseCatalog.visibility = View.VISIBLE
            tileSystemHealth.visibility = View.VISIBLE

            bindActionCard(
                cardView = tileCourseCatalog,
                title = "Global Course Catalog",
                subtitle = "Master curriculum database across all batches",
                iconRes = R.drawable.ic_storage,
                accentColor = Color.parseColor("#64748B")
            ) {
                val intent = ClassMateCourses.intent(this, batchId, batchLabel, role, profileId, "catalog")
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
        } else {
            headerSystemSection.visibility = View.GONE
            tileCourseCatalog.visibility = View.GONE
            tileSystemHealth.visibility = View.GONE
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
                        "select=id&semester_id=eq.$activeSemId&status=eq.active"
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
                val items = semesters.map { s ->
                    val num = s.optInt("semester_number")
                    val status = s.optString("status")
                    val label = when (status) {
                        "active" -> "Active"
                        "completed" -> "Completed"
                        "not_started" -> "Draft"
                        else -> status
                    }
                    "Semester $num ($label)"
                }.toTypedArray()

                MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                    .setTitle("Select Active Semester")
                    .setItems(items) { _, which ->
                        val selected = semesters[which]
                        val semNum = selected.optInt("semester_number")
                        val semId = selected.optString("id")
                        val status = selected.optString("status")

                        if (status == "active") {
                            Toast.makeText(this@ClassMateBatchAdminActivity, "Semester $semNum is already active", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }

                        MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                            .setTitle("Publish Semester $semNum?")
                            .setMessage("Publishing will make Semester $semNum active for this batch and complete the previous active semester.")
                            .setPositiveButton("Publish") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        ClassMateAuthApi.rpc("publish_semester", JSONObject().put("target_semester", semId))
                                        Toast.makeText(this@ClassMateBatchAdminActivity, "Semester $semNum is now active!", Toast.LENGTH_SHORT).show()
                                        loadBatchData()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@ClassMateBatchAdminActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchAdminActivity, "Could not load semesters: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCloneSemesterDialog() {
        lifecycleScope.launch {
            try {
                val sourceRows = ClassMateAuthApi.rows(
                    "semesters",
                    "select=id,semester_number,status&batch_id=eq.$batchId&status=in.(active,completed)&order=semester_number"
                )
                val targetRows = ClassMateAuthApi.rows(
                    "semesters",
                    "select=id,semester_number&batch_id=eq.$batchId&status=eq.not_started&order=semester_number"
                )

                if (sourceRows.length() == 0) {
                    Toast.makeText(this@ClassMateBatchAdminActivity, "No active or completed semesters found to copy from.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (targetRows.length() == 0) {
                    Toast.makeText(this@ClassMateBatchAdminActivity, "No draft semester available. Destination draft must exist to copy into.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val sources = (0 until sourceRows.length()).map { sourceRows.getJSONObject(it) }
                val sourceLabels = sources.map { "Semester ${it.optInt("semester_number")} (${it.optString("status")})" }.toTypedArray()

                MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                    .setTitle("Step 1: Choose Source Semester")
                    .setItems(sourceLabels) { _, srcIndex ->
                        val source = sources[srcIndex]
                        val targets = (0 until targetRows.length()).map { targetRows.getJSONObject(it) }
                        val targetLabels = targets.map { "Semester ${it.optInt("semester_number")} (Draft)" }.toTypedArray()

                        MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                            .setTitle("Step 2: Choose Destination Draft")
                            .setItems(targetLabels) { _, tgtIndex ->
                                val target = targets[tgtIndex]

                                MaterialAlertDialogBuilder(this@ClassMateBatchAdminActivity)
                                    .setTitle("Copy Semester Plan?")
                                    .setMessage("Duplicate courses, timetable routine slots, and teacher assignments from Semester ${source.optInt("semester_number")} into Semester ${target.optInt("semester_number")}?")
                                    .setPositiveButton("Copy Plan") { _, _ ->
                                        lifecycleScope.launch {
                                            try {
                                                ClassMateAuthApi.rpcText(
                                                    "clone_semester",
                                                    JSONObject()
                                                        .put("from_id", source.getString("id"))
                                                        .put("to_id", target.getString("id"))
                                                )
                                                Toast.makeText(this@ClassMateBatchAdminActivity, "Semester plan cloned successfully!", Toast.LENGTH_LONG).show()
                                                loadBatchData()
                                            } catch (e: Exception) {
                                                Toast.makeText(this@ClassMateBatchAdminActivity, "Clone failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(this@ClassMateBatchAdminActivity, "Failed to load semesters: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
