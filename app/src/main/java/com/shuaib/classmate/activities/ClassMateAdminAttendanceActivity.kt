package com.shuaib.classmate.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.ClassMateHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClassMateAdminAttendanceActivity : AppCompatActivity() {

    private var batchId: String = ""
    private var batchLabel: String = ""
    private var pollerJob: Job? = null

    // Views
    private lateinit var tvStationStatusTitle: TextView
    private lateinit var badgeStationStatus: TextView
    private lateinit var layoutActiveSessionDetails: View
    private lateinit var tvActiveCourseCode: TextView
    private lateinit var tvActiveCourseTitle: TextView
    private lateinit var tvActiveSessionStartedAt: TextView
    private lateinit var btnTakeAttendance: MaterialButton
    private lateinit var btnCloseSession: MaterialButton

    private lateinit var tabCourses: MaterialButton
    private lateinit var tabCards: MaterialButton
    private lateinit var swipeRefreshCourses: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var scrollCardsView: View
    private lateinit var progressCourses: ProgressBar
    private lateinit var tvCoursesEmpty: TextView
    private lateinit var containerCourseCards: LinearLayout

    private lateinit var actvStudentSearch: AutoCompleteTextView
    private lateinit var layoutSelectedStudentInfo: View
    private lateinit var tvSelectedStudentName: TextView
    private lateinit var tvSelectedStudentCardStatus: TextView
    private lateinit var etCardUid: EditText
    private lateinit var btnSaveCard: MaterialButton
    private lateinit var tvStudentDirectoryCount: TextView
    private lateinit var progressStudents: ProgressBar
    private lateinit var containerStudentCards: LinearLayout

    // State
    private var activeSessionId: String? = null
    private var coursesList: MutableList<JSONObject> = mutableListOf()
    private var studentsList: MutableList<JSONObject> = mutableListOf()
    private var selectedStudentObj: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_attendance)

        batchId = intent.getStringExtra("EXTRA_BATCH_ID") ?: ""
        batchLabel = intent.getStringExtra("EXTRA_BATCH_LABEL") ?: "Batch Attendance"

        bindViews()
        setupListeners()
        selectTab(true)

        loadAllData()
    }

    override fun onResume() {
        super.onResume()
        startStationPoller()
    }

    override fun onPause() {
        super.onPause()
        pollerJob?.cancel()
        pollerJob = null
    }

    private fun bindViews() {
        findViewById<View>(R.id.btnBack).setOnClickListener {
            ClassMateHaptics.selection(it)
            finish()
        }
        findViewById<TextView>(R.id.tvAdminAttendanceSubtitle).text = "$batchLabel · RFID Station & Hub"

        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            ClassMateHaptics.selection(it)
            loadAllData()
        }

        findViewById<View>(R.id.btnOpenWeb).setOnClickListener {
            ClassMateHaptics.selection(it)
            openWebAttendance()
        }

        tvStationStatusTitle = findViewById(R.id.tvStationStatusTitle)
        badgeStationStatus = findViewById(R.id.badgeStationStatus)
        layoutActiveSessionDetails = findViewById(R.id.layoutActiveSessionDetails)
        tvActiveCourseCode = findViewById(R.id.tvActiveCourseCode)
        tvActiveCourseTitle = findViewById(R.id.tvActiveCourseTitle)
        tvActiveSessionStartedAt = findViewById(R.id.tvActiveSessionStartedAt)
        btnTakeAttendance = findViewById(R.id.btnTakeAttendance)
        btnCloseSession = findViewById(R.id.btnCloseSession)

        tabCourses = findViewById(R.id.tabCourses)
        tabCards = findViewById(R.id.tabCards)
        swipeRefreshCourses = findViewById(R.id.swipeRefreshCourses)
        scrollCardsView = findViewById(R.id.scrollCardsView)
        progressCourses = findViewById(R.id.progressCourses)
        tvCoursesEmpty = findViewById(R.id.tvCoursesEmpty)
        containerCourseCards = findViewById(R.id.containerCourseCards)

        actvStudentSearch = findViewById(R.id.actvStudentSearch)
        layoutSelectedStudentInfo = findViewById(R.id.layoutSelectedStudentInfo)
        tvSelectedStudentName = findViewById(R.id.tvSelectedStudentName)
        tvSelectedStudentCardStatus = findViewById(R.id.tvSelectedStudentCardStatus)
        etCardUid = findViewById(R.id.etCardUid)
        btnSaveCard = findViewById(R.id.btnSaveCard)
        tvStudentDirectoryCount = findViewById(R.id.tvStudentDirectoryCount)
        progressStudents = findViewById(R.id.progressStudents)
        containerStudentCards = findViewById(R.id.containerStudentCards)
    }

    private fun setupListeners() {
        tabCourses.setOnClickListener {
            ClassMateHaptics.selection(it)
            selectTab(true)
        }
        tabCards.setOnClickListener {
            ClassMateHaptics.selection(it)
            selectTab(false)
        }

        swipeRefreshCourses.setOnRefreshListener {
            loadCourses()
            checkStationStatus()
        }

        btnTakeAttendance.setOnClickListener {
            ClassMateHaptics.selection(it)
            showTakeAttendanceCoursePicker()
        }

        btnCloseSession.setOnClickListener {
            ClassMateHaptics.selection(it)
            confirmCloseSession()
        }

        btnSaveCard.setOnClickListener {
            ClassMateHaptics.selection(it)
            saveStudentCard()
        }
    }

    private fun selectTab(isCourses: Boolean) {
        if (isCourses) {
            tabCourses.backgroundTintList = ColorStateList.valueOf(getColor(R.color.cm_primary))
            tabCourses.setTextColor(Color.WHITE)
            tabCards.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            tabCards.setTextColor(getColor(R.color.cm_text_muted))

            swipeRefreshCourses.visibility = View.VISIBLE
            scrollCardsView.visibility = View.GONE
        } else {
            tabCards.backgroundTintList = ColorStateList.valueOf(getColor(R.color.cm_primary))
            tabCards.setTextColor(Color.WHITE)
            tabCourses.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            tabCourses.setTextColor(getColor(R.color.cm_text_muted))

            swipeRefreshCourses.visibility = View.GONE
            scrollCardsView.visibility = View.VISIBLE
        }
    }

    private fun loadAllData() {
        checkStationStatus()
        loadCourses()
        loadStudents()
    }

    private fun startStationPoller() {
        if (pollerJob?.isActive == true) return
        pollerJob = lifecycleScope.launch {
            while (isActive) {
                checkStationStatus()
                delay(5000)
            }
        }
    }

    // 1. Station Device Status
    private fun checkStationStatus() {
        lifecycleScope.launch {
            try {
                val params = JSONObject().put("target_device", "ESP32-READER-01")
                val res = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("get_device_active_session", params)
                }

                if (res != null && res.optString("status") == "ACTIVE") {
                    activeSessionId = res.optString("session_id")
                    tvStationStatusTitle.text = "Now Taking Attendance"
                    badgeStationStatus.text = "LIVE"
                    badgeStationStatus.setBackgroundResource(R.drawable.bg_badge_green)
                    badgeStationStatus.setTextColor(getColor(R.color.cm_file_lab_text))

                    layoutActiveSessionDetails.visibility = View.VISIBLE
                    tvActiveCourseCode.text = res.optString("course_code")
                    tvActiveCourseTitle.text = res.optString("course_title")

                    val started = res.optString("started_at")
                    val timeStr = if (started.length >= 16) started.substring(11, 16) else "Live"
                    tvActiveSessionStartedAt.text = "Session active since $timeStr · Reader awaiting taps"

                    btnTakeAttendance.visibility = View.GONE
                    btnCloseSession.visibility = View.VISIBLE
                } else {
                    activeSessionId = null
                    tvStationStatusTitle.text = "Station Standby"
                    badgeStationStatus.text = "IDLE"
                    badgeStationStatus.setBackgroundResource(R.drawable.bg_badge_paused)
                    badgeStationStatus.setTextColor(getColor(R.color.cm_text_muted))

                    layoutActiveSessionDetails.visibility = View.GONE
                    btnTakeAttendance.visibility = View.VISIBLE
                    btnCloseSession.visibility = View.GONE
                }
            } catch (e: Exception) {
                // Keep current state on transient failure
            }
        }
    }

    // 2. Take Attendance (Ad-hoc)
    private fun showTakeAttendanceCoursePicker() {
        if (coursesList.isEmpty()) {
            Toast.makeText(this, "Loading courses, please wait...", Toast.LENGTH_SHORT).show()
            loadCourses()
            return
        }

        val courseNames = coursesList.map {
            "${it.optString("course_code")} · ${it.optString("course_title")}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Start Attendance for Course")
            .setItems(courseNames) { _, which ->
                val selected = coursesList[which]
                val courseOfferingId = selected.optString("semester_course_id")
                val courseCode = selected.optString("course_code")
                startAdHocSession(courseOfferingId, courseCode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAdHocSession(courseOfferingId: String, courseCode: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Starting session on ESP32 station...", Toast.LENGTH_SHORT).show()
                val params = JSONObject()
                    .put("target_semester_course", courseOfferingId)
                    .put("target_device", "ESP32-READER-01")

                val result = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("start_adhoc_attendance_session", params)
                }

                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Attendance started for $courseCode!", Toast.LENGTH_LONG).show()
                checkStationStatus()
                loadCourses()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmCloseSession() {
        val sid = activeSessionId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Close Attendance Session?")
            .setMessage("The RFID hardware station will return to sleep mode and stop accepting card taps.")
            .setPositiveButton("Close Session") { _, _ ->
                closeSession(sid)
            }
            .setNegativeButton("Keep Running", null)
            .show()
    }

    private fun closeSession(sessionId: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Closing session...", Toast.LENGTH_SHORT).show()
                val params = JSONObject().put("target_session", sessionId)
                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("close_adhoc_attendance_session", params)
                }

                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Session closed. Station is idle.", Toast.LENGTH_SHORT).show()
                checkStationStatus()
                loadCourses()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to close: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 3. Load Courses
    private fun loadCourses() {
        if (batchId.isBlank()) return
        progressCourses.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val params = JSONObject().put("target_batch", batchId)
                val raw = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpcText("teacher_course_attendance_overview", params)
                }

                val arr = JSONArray(raw)
                coursesList.clear()
                for (i in 0 until arr.length()) {
                    coursesList.add(arr.getJSONObject(i))
                }

                renderCourseCards()
            } catch (e: Exception) {
                tvCoursesEmpty.visibility = View.VISIBLE
                tvCoursesEmpty.text = "Failed to load courses: ${e.message}"
            } finally {
                progressCourses.visibility = View.GONE
                swipeRefreshCourses.isRefreshing = false
            }
        }
    }

    private fun renderCourseCards() {
        containerCourseCards.removeAllViews()
        if (coursesList.isEmpty()) {
            tvCoursesEmpty.visibility = View.VISIBLE
            return
        }
        tvCoursesEmpty.visibility = View.GONE

        val density = resources.displayMetrics.density
        coursesList.forEach { course ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
                radius = 16 * density
                cardElevation = 1 * density
                setCardBackgroundColor(getColor(R.color.cm_card))
                strokeColor = getColor(R.color.cm_border)
                strokeWidth = (1 * density).toInt()
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            }

            // Header Row: Course Code Pill + Avg Percentage Pill
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvCode = TextView(this).apply {
                text = course.optString("course_code")
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#10B981"))
                setBackgroundResource(R.drawable.bg_badge_green)
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            }

            val tvSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }

            val avgPct = course.optInt("average_percentage", 0)
            val tvPct = TextView(this).apply {
                text = "$avgPct% Avg"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (avgPct >= 75) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
            }

            headerRow.addView(tvCode)
            headerRow.addView(tvSpacer)
            headerRow.addView(tvPct)
            inner.addView(headerRow)

            // Course Title
            val tvTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                text = course.optString("course_title")
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.cm_text_primary))
            }
            inner.addView(tvTitle)

            // Teacher & Stats
            val teacherName = course.optString("teacher_name").takeIf { it.isNotBlank() && it != "null" } ?: "Assigned Faculty"
            val totalSessions = course.optInt("total_sessions", 0)
            val enrolled = course.optInt("enrolled_students", 0)

            val tvMeta = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (3 * density).toInt()
                }
                text = "$teacherName · $totalSessions Sessions Taken · $enrolled Students"
                textSize = 11.5f
                setTextColor(getColor(R.color.cm_text_secondary))
            }
            inner.addView(tvMeta)

            // Action Buttons: [ View Day-wise Roster ] & [ 📊 Export Excel ]
            val actionRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
                orientation = LinearLayout.HORIZONTAL
            }

            val btnViewRoster = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                    marginEnd = (8 * density).toInt()
                }
                text = "View Day-wise"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.cm_text_primary))
                strokeColor = ColorStateList.valueOf(getColor(R.color.cm_border))
                cornerRadius = (10 * density).toInt()
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    showCourseMatrixDialog(course)
                }
            }

            val btnExport = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
                text = "Export Excel"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
                icon = ContextCompat.getDrawable(this@ClassMateAdminAttendanceActivity, R.drawable.ic_download_outline)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconTint = ColorStateList.valueOf(Color.WHITE)
                cornerRadius = (10 * density).toInt()
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    exportCourseExcel(course.optString("semester_course_id"), course.optString("course_code"))
                }
            }

            actionRow.addView(btnViewRoster)
            actionRow.addView(btnExport)
            inner.addView(actionRow)

            card.addView(inner)
            containerCourseCards.addView(card)
        }
    }

    // 4. Course Matrix Dialog
    private fun showCourseMatrixDialog(course: JSONObject) {
        val courseOfferingId = course.optString("semester_course_id")
        val courseCode = course.optString("course_code")
        val courseTitle = course.optString("course_title")

        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_course_matrix, null, false)
        val tvCode = dialogView.findViewById<TextView>(R.id.tvMatrixCourseCode)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvMatrixCourseTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvMatrixSubtitle)
        val btnExport = dialogView.findViewById<MaterialButton>(R.id.btnMatrixExportExcel)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnMatrixClose)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressMatrix)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvMatrixEmpty)
        val scrollTable = dialogView.findViewById<View>(R.id.scrollMatrixTable)
        val table = dialogView.findViewById<TableLayout>(R.id.tableMatrix)

        tvCode.text = courseCode
        tvTitle.text = courseTitle
        val totalSessions = course.optInt("total_sessions", 0)
        val enrolled = course.optInt("enrolled_students", 0)
        val avgPct = course.optInt("average_percentage", 0)
        tvSubtitle.text = "$totalSessions Sessions · $enrolled Students · $avgPct% Avg"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        btnExport.setOnClickListener {
            ClassMateHaptics.selection(it)
            exportCourseExcel(courseOfferingId, courseCode)
        }
        btnClose.setOnClickListener {
            ClassMateHaptics.selection(it)
            dialog.dismiss()
        }

        // Fetch Matrix
        lifecycleScope.launch {
            try {
                val params = JSONObject().put("target_semester_course", courseOfferingId)
                val matrixRaw = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpcText("course_attendance_matrix", params)
                }

                val matrixObj = JSONObject(matrixRaw)
                val sessions = matrixObj.optJSONArray("sessions") ?: JSONArray()
                val students = matrixObj.optJSONArray("students") ?: JSONArray()

                progress.visibility = View.GONE
                if (sessions.length() == 0 || students.length() == 0) {
                    tvEmpty.visibility = View.VISIBLE
                    return@launch
                }

                scrollTable.visibility = View.VISIBLE
                renderMatrixTable(table, sessions, students)
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Failed to load matrix: ${e.message}"
            }
        }
    }

    private fun renderMatrixTable(table: TableLayout, sessions: JSONArray, students: JSONArray) {
        table.removeAllViews()
        val density = resources.displayMetrics.density

        // Table Header
        val headerRow = TableRow(this).apply {
            setBackgroundColor(getColor(R.color.cm_surface_glass))
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        fun cellText(txt: String, isHeader: Boolean = false, color: Int? = null): TextView {
            return TextView(this).apply {
                text = txt
                textSize = if (isHeader) 11f else 12f
                typeface = if (isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(color ?: (if (isHeader) getColor(R.color.cm_text_secondary) else getColor(R.color.cm_text_primary)))
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                gravity = Gravity.CENTER
            }
        }

        headerRow.addView(cellText("Roll", true))
        headerRow.addView(cellText("Name", true))
        for (i in 0 until sessions.length()) {
            val sess = sessions.getJSONObject(i)
            val d = sess.optString("date").takeLast(5) // MM-DD
            headerRow.addView(cellText(d, true))
        }
        headerRow.addView(cellText("Total", true))
        headerRow.addView(cellText("%", true))
        table.addView(headerRow)

        // Sort students naturally by roll
        val studentList = mutableListOf<JSONObject>()
        for (i in 0 until students.length()) {
            studentList.add(students.getJSONObject(i))
        }
        studentList.sortBy { it.optString("roll") }

        // Data Rows
        studentList.forEachIndexed { index, s ->
            val row = TableRow(this).apply {
                if (index % 2 == 1) {
                    setBackgroundColor(getColor(R.color.cm_surface_glass))
                }
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            }

            row.addView(cellText(s.optString("roll"), false))
            row.addView(cellText(s.optString("full_name").take(14), false))

            val records = s.optJSONObject("records") ?: JSONObject()
            for (i in 0 until sessions.length()) {
                val sessId = sessions.getJSONObject(i).optString("session_id")
                val isPresent = records.optString(sessId) == "present"
                val cell = cellText(
                    if (isPresent) "1" else "0",
                    isHeader = false,
                    color = if (isPresent) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
                )
                cell.typeface = Typeface.DEFAULT_BOLD
                row.addView(cell)
            }

            val pCount = s.optInt("present_count", 0)
            val total = s.optInt("total_sessions", sessions.length())
            row.addView(cellText("$pCount/$total", false))

            val pct = s.optInt("percentage", 0)
            val pctCell = cellText("$pct%", false, if (pct >= 75) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
            pctCell.typeface = Typeface.DEFAULT_BOLD
            row.addView(pctCell)

            table.addView(row)
        }
    }

    // 5. Excel Export
    private fun exportCourseExcel(courseOfferingId: String, courseCode: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Generating Excel sheet...", Toast.LENGTH_SHORT).show()
                val client = OkHttpClient()
                val token = ClassMateAuthApi.accessToken
                val url = "${BuildConfig.CLASSMATE_URL.trimEnd('/')}/functions/v1/export-attendance"

                val body = JSONObject()
                    .put("semester_course_id", courseOfferingId)
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val bytes = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Server returned ${response.code}")
                        response.body?.bytes() ?: throw Exception("Empty response")
                    }
                }

                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val fileName = "Attendance_${courseCode.ifBlank { "Class" }}_$dateStr.xlsx"
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { it.write(bytes) }
                }

                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Downloaded: ${file.name}", Toast.LENGTH_LONG).show()

                // Share / View Intent
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                        this@ClassMateAdminAttendanceActivity,
                        "${packageName}.fileprovider",
                        file
                    ))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Attendance Excel"))
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 6. Load Students & Register RFID Cards
    private fun loadStudents() {
        if (batchId.isBlank()) return
        progressStudents.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val params = JSONObject().put("target_batch", batchId)
                val raw = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpcText("admin_batch_students", params)
                }

                val arr = JSONArray(raw)
                studentsList.clear()
                for (i in 0 until arr.length()) {
                    studentsList.add(arr.getJSONObject(i))
                }

                tvStudentDirectoryCount.text = "${studentsList.size} Students"
                setupStudentAutocomplete()
                renderStudentDirectoryCards()
            } catch (e: Exception) {
                // Handle error
            } finally {
                progressStudents.visibility = View.GONE
            }
        }
    }

    private fun setupStudentAutocomplete() {
        val studentNames = studentsList.map {
            "${it.optString("roll")} - ${it.optString("full_name")}"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, studentNames)
        actvStudentSearch.setAdapter(adapter)

        actvStudentSearch.setOnItemClickListener { _, _, position, _ ->
            val selection = adapter.getItem(position) ?: return@setOnItemClickListener
            val roll = selection.substringBefore(" - ").trim()
            val student = studentsList.firstOrNull { it.optString("roll") == roll }
            if (student != null) {
                selectStudentForCard(student)
            }
        }
    }

    private fun selectStudentForCard(student: JSONObject) {
        selectedStudentObj = student
        layoutSelectedStudentInfo.visibility = View.VISIBLE
        val name = student.optString("full_name")
        val roll = student.optString("roll")
        tvSelectedStudentName.text = "$name ($roll)"

        val uid = student.optString("rfid_card_uid").trim()
        if (uid.isNotEmpty() && uid != "null") {
            tvSelectedStudentCardStatus.text = "Card Registered: ••••${uid.takeLast(4).uppercase()}"
            tvSelectedStudentCardStatus.setTextColor(Color.parseColor("#10B981"))
            etCardUid.setText(uid)
        } else {
            tvSelectedStudentCardStatus.text = "No card registered yet"
            tvSelectedStudentCardStatus.setTextColor(getColor(R.color.cm_text_muted))
            etCardUid.setText("")
        }
    }

    private fun saveStudentCard() {
        val student = selectedStudentObj
        if (student == null) {
            Toast.makeText(this, "Please select a student first", Toast.LENGTH_SHORT).show()
            return
        }

        val cardUid = etCardUid.text.toString().trim()
        if (cardUid.isEmpty()) {
            Toast.makeText(this, "Please enter a card UID", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Assigning card...", Toast.LENGTH_SHORT).show()
                val cleanUid = cardUid.uppercase().trim()
                val params = JSONObject()
                    .put("p_student_id", student.optString("student_id"))
                    .put("p_card_uid", cleanUid)
                    .put("target_student", student.optString("student_id"))
                    .put("target_card_uid", cleanUid)

                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("enroll_student_rfid", params)
                }

                Toast.makeText(
                    this@ClassMateAdminAttendanceActivity,
                    "Card $cleanUid assigned to ${student.optString("roll")}!",
                    Toast.LENGTH_LONG
                ).show()

                student.put("rfid_card_uid", cleanUid)
                selectStudentForCard(student)
                renderStudentDirectoryCards()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to assign card: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderStudentDirectoryCards() {
        containerStudentCards.removeAllViews()
        val density = resources.displayMetrics.density

        studentsList.forEach { student ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                radius = 12 * density
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.cm_card))
                strokeColor = getColor(R.color.cm_border)
                strokeWidth = (1 * density).toInt()
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    actvStudentSearch.setText("${student.optString("roll")} - ${student.optString("full_name")}", false)
                    selectStudentForCard(student)
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            }

            val details = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val tvRollName = TextView(this).apply {
                text = "${student.optString("roll")} · ${student.optString("full_name")}"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.cm_text_primary))
            }

            details.addView(tvRollName)
            row.addView(details)

            val uid = student.optString("rfid_card_uid").trim()
            val hasCard = uid.isNotEmpty() && uid != "null"

            val badge = TextView(this).apply {
                text = if (hasCard) "••••${uid.takeLast(4).uppercase()}" else "No Card"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (hasCard) Color.parseColor("#10B981") else getColor(R.color.cm_text_muted))
                setBackgroundResource(if (hasCard) R.drawable.bg_badge_green else R.drawable.bg_badge_paused)
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            }

            row.addView(badge)
            card.addView(row)
            containerStudentCards.addView(card)
        }
    }

    // 7. Web Dashboard Shortcut
    private fun openWebAttendance() {
        try {
            val url = "https://classmatebd.vercel.app/#attendance"
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://classmatebd.vercel.app/#attendance"))
            startActivity(browserIntent)
        }
    }
}
