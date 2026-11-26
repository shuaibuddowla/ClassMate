package com.shuaib.classmate.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.app.PendingIntent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import com.shuaib.classmate.nfc.NfcCardManager
import com.shuaib.classmate.nfc.NfcCardLocalCache
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
    private lateinit var cardHardwareStation: MaterialCardView
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
    private lateinit var layoutCourseFilterHeader: View
    private lateinit var tvBatchAttendanceSummary: TextView
    private lateinit var etCourseSearch: EditText
    private lateinit var ivClearCourseSearch: View
    private lateinit var progressCourses: ProgressBar
    private lateinit var tvCoursesEmpty: TextView
    private lateinit var emptyCoursesState: View
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
    private var pendingAutoStartAttendance: Boolean = false
    private var courseSearchQuery: String = ""
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var activeCardWriteDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_attendance)

        batchId = intent.getStringExtra("EXTRA_BATCH_ID") ?: ""
        batchLabel = intent.getStringExtra("EXTRA_BATCH_LABEL") ?: "Batch Attendance"
        pendingAutoStartAttendance = intent.getBooleanExtra("EXTRA_AUTO_START_ATTENDANCE", false)

        bindViews()
        applySafeInsets()
        setupListeners()
        selectTab(true)

        loadAllData()

        setupNfcDispatch()
        val passedCardUid = intent.getStringExtra("EXTRA_CARD_UID")
        if (!passedCardUid.isNullOrEmpty()) {
            selectTab(false)
            etCardUid.setText(passedCardUid)
            Toast.makeText(this, "Card $passedCardUid ready. Select a student to assign.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        startStationPoller()
        try {
            nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        pollerJob?.cancel()
        pollerJob = null
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {}
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

        cardHardwareStation = findViewById(R.id.cardHardwareStation)
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
        layoutCourseFilterHeader = findViewById(R.id.layoutCourseFilterHeader)
        tvBatchAttendanceSummary = findViewById(R.id.tvBatchAttendanceSummary)
        etCourseSearch = findViewById(R.id.etCourseSearch)
        ivClearCourseSearch = findViewById(R.id.ivClearCourseSearch)
        progressCourses = findViewById(R.id.progressCourses)
        tvCoursesEmpty = findViewById(R.id.tvCoursesEmpty)
        emptyCoursesState = findViewById(R.id.emptyCoursesState)
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

        etCourseSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                courseSearchQuery = s?.toString()?.trim().orEmpty()
                ivClearCourseSearch.visibility = if (courseSearchQuery.isNotBlank()) View.VISIBLE else View.GONE
                renderCourseCards()
            }
        })

        ivClearCourseSearch.setOnClickListener {
            ClassMateHaptics.selection(it)
            etCourseSearch.setText("")
        }
    }

    private fun applySafeInsets() {
        val topBar = findViewById<View>(R.id.layoutTopAppBar)
        val initialTop = topBar.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        val initialBottom = swipeRefreshCourses.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshCourses) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialBottom + bars.bottom)
            scrollCardsView.setPadding(scrollCardsView.paddingLeft, scrollCardsView.paddingTop, scrollCardsView.paddingRight, bars.bottom)
            insets
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
                    val density = resources.displayMetrics.density
                    cardHardwareStation.strokeColor = Color.parseColor("#10B981")
                    cardHardwareStation.strokeWidth = (2 * density).toInt()
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
                    val density = resources.displayMetrics.density
                    cardHardwareStation.strokeColor = getColor(R.color.cm_border)
                    cardHardwareStation.strokeWidth = (1 * density).toInt()
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

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val density = resources.displayMetrics.density
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
            setBackgroundColor(getColor(R.color.cm_surface))
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.cm_border))
        }
        rootLayout.addView(handle)

        val tvSheetTitle = TextView(this).apply {
            text = "Start Attendance"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.cm_text_primary))
        }
        val tvSheetSub = TextView(this).apply {
            text = "Select a course below to open an attendance session"
            textSize = 13f
            setTextColor(getColor(R.color.cm_text_secondary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        }
        rootLayout.addView(tvSheetTitle)
        rootLayout.addView(tvSheetSub)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
            isVerticalScrollBarEnabled = false
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val sortedCourses = coursesList.sortedWith(
            compareByDescending<JSONObject> { it.optInt("total_sessions", 0) > 0 }
                .thenBy { it.optString("course_code") }
        )

        sortedCourses.forEach { course ->
            val code = course.optString("course_code")
            val title = course.optString("course_title")
            val teacher = course.optString("teacher_name").takeIf { it.isNotBlank() && it != "null" } ?: "Instructor"
            val sessionsCount = course.optInt("total_sessions", 0)
            val offeringId = course.optString("semester_course_id")

            val itemCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (10 * density).toInt()
                }
                radius = 14 * density
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.cm_card))
                strokeColor = getColor(R.color.cm_border)
                strokeWidth = (1 * density).toInt()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    sheet.dismiss()
                    confirmStartAdHocSession(offeringId, code, title)
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            }

            val infoCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val badgeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvCodeBadge = TextView(this).apply {
                text = code
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#10B981"))
                setBackgroundResource(R.drawable.bg_badge_green)
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            }
            val tvSessBadge = TextView(this).apply {
                text = if (sessionsCount > 0) "$sessionsCount sessions" else "No sessions yet"
                textSize = 11f
                setTextColor(getColor(R.color.cm_text_muted))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * density).toInt()
                }
            }
            badgeRow.addView(tvCodeBadge)
            badgeRow.addView(tvSessBadge)

            val tvTitleView = TextView(this).apply {
                text = title
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.cm_text_primary))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (5 * density).toInt()
                }
            }
            val tvTeacherView = TextView(this).apply {
                text = teacher
                textSize = 12f
                setTextColor(getColor(R.color.cm_text_secondary))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (2 * density).toInt()
                }
            }

            infoCol.addView(badgeRow)
            infoCol.addView(tvTitleView)
            infoCol.addView(tvTeacherView)

            val ivChevron = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                    marginStart = (8 * density).toInt()
                }
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(getColor(R.color.cm_text_muted))
            }

            row.addView(infoCol)
            row.addView(ivChevron)
            itemCard.addView(row)
            listLayout.addView(itemCard)
        }

        scrollView.addView(listLayout)
        rootLayout.addView(scrollView)
        sheet.setContentView(rootLayout)
        sheet.show()
    }

    private fun confirmStartAdHocSession(courseOfferingId: String, courseCode: String, courseTitle: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Attendance Session")
            .setMessage("Are you sure you want to start an attendance session for $courseCode · $courseTitle?")
            .setPositiveButton("Yes, Start Session") { _, _ ->
                startAdHocSession(courseOfferingId, courseCode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAdHocSession(courseOfferingId: String, courseCode: String) {
        btnTakeAttendance.isEnabled = false
        btnTakeAttendance.text = "Starting..."
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Starting session on ESP32 station...", Toast.LENGTH_SHORT).show()
                val params = JSONObject()
                    .put("target_semester_course", courseOfferingId)
                    .put("target_device", "ESP32-READER-01")

                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("start_adhoc_attendance_session", params)
                }

                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Attendance started for $courseCode!", Toast.LENGTH_LONG).show()
                checkStationStatus()
                loadCourses()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to start: ${com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)}", Toast.LENGTH_LONG).show()
            } finally {
                btnTakeAttendance.isEnabled = true
                btnTakeAttendance.text = "Take Attendance"
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
        btnCloseSession.isEnabled = false
        btnCloseSession.text = "Closing..."
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
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to close: ${com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)}", Toast.LENGTH_LONG).show()
            } finally {
                btnCloseSession.isEnabled = true
                btnCloseSession.text = "Close Attendance Session"
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
                if (pendingAutoStartAttendance) {
                    pendingAutoStartAttendance = false
                    if (activeSessionId == null && coursesList.isNotEmpty()) {
                        showTakeAttendanceCoursePicker()
                    } else if (activeSessionId != null) {
                        Toast.makeText(this@ClassMateAdminAttendanceActivity, "An attendance session is already running.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                pendingAutoStartAttendance = false
                tvCoursesEmpty.visibility = View.GONE
                layoutCourseFilterHeader.visibility = View.GONE
                containerCourseCards.removeAllViews()
                com.shuaib.classmate.utils.EmptyStateHelper.showError(
                    container = emptyCoursesState,
                    title = "Unable to load courses",
                    errorMessage = com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e),
                    onRetry = { loadCourses() }
                )
            } finally {
                progressCourses.visibility = View.GONE
                swipeRefreshCourses.isRefreshing = false
            }
        }
    }

    private fun renderCourseCards() {
        containerCourseCards.removeAllViews()
        tvCoursesEmpty.visibility = View.GONE
        if (coursesList.isEmpty()) {
            layoutCourseFilterHeader.visibility = View.GONE
            com.shuaib.classmate.utils.EmptyStateHelper.show(
                container = emptyCoursesState,
                iconRes = R.drawable.ic_library_outline,
                title = "No courses found",
                subtitle = "Add semester courses first so you can start attendance sessions and export rosters.",
                actionText = "Refresh",
                onAction = { loadCourses() }
            )
            return
        }

        layoutCourseFilterHeader.visibility = View.VISIBLE
        val totalSessionsAll = coursesList.sumOf { it.optInt("total_sessions", 0) }
        val avgAll = coursesList.map { it.optInt("average_percentage", 0) }.average().toInt()
        tvBatchAttendanceSummary.text = "${coursesList.size} ${if (coursesList.size == 1) "Course" else "Courses"} · $totalSessionsAll Sessions · $avgAll% Avg"

        val filteredCourses = if (courseSearchQuery.isBlank()) {
            coursesList
        } else {
            coursesList.filter {
                it.optString("course_code").contains(courseSearchQuery, true) ||
                    it.optString("course_title").contains(courseSearchQuery, true) ||
                    it.optString("teacher_name").contains(courseSearchQuery, true)
            }
        }

        if (filteredCourses.isEmpty()) {
            com.shuaib.classmate.utils.EmptyStateHelper.show(
                container = emptyCoursesState,
                iconRes = R.drawable.ic_search_modern,
                title = "No matching courses",
                subtitle = "No courses matched \"$courseSearchQuery\".",
                actionText = "Clear Search",
                onAction = { etCourseSearch.setText("") }
            )
            return
        }

        com.shuaib.classmate.utils.EmptyStateHelper.hide(emptyCoursesState)

        val takenCourses = filteredCourses.filter { it.optInt("total_sessions", 0) > 0 }
        val untakenCourses = filteredCourses.filter { it.optInt("total_sessions", 0) <= 0 }
        val density = resources.displayMetrics.density

        fun addSectionHeader(label: String, count: Int) {
            val tvHeader = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
                text = "$label ($count)"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(getColor(R.color.cm_text_muted))
            }
            containerCourseCards.addView(tvHeader)
        }

        fun addCourseCard(course: JSONObject) {
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

            val totalSessions = course.optInt("total_sessions", 0)
            val avgPct = course.optInt("average_percentage", 0).coerceIn(0, 100)
            val tvPct = TextView(this).apply {
                text = if (totalSessions > 0) "$avgPct% Avg" else "No sessions"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    when {
                        totalSessions <= 0 -> getColor(R.color.cm_text_muted)
                        avgPct >= 75 -> Color.parseColor("#10B981")
                        else -> Color.parseColor("#F59E0B")
                    }
                )
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

            val btnStart = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 0.9f).apply {
                    marginEnd = (6 * density).toInt()
                }
                text = "Start"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#10B981"))
                strokeColor = ColorStateList.valueOf(Color.parseColor("#10B981"))
                cornerRadius = (10 * density).toInt()
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    if (activeSessionId != null) {
                        Toast.makeText(this@ClassMateAdminAttendanceActivity, "Close the active session first.", Toast.LENGTH_SHORT).show()
                    } else {
                        confirmStartAdHocSession(
                            course.optString("semester_course_id"),
                            course.optString("course_code"),
                            course.optString("course_title")
                        )
                    }
                }
            }

            val btnViewRoster = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                text = "Roster"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.cm_text_primary))
                strokeColor = ColorStateList.valueOf(getColor(R.color.cm_border))
                cornerRadius = (10 * density).toInt()
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    showCourseMatrixDialog(course)
                }
            }

            val btnExport = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
                text = "Excel"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                setTextColor(Color.WHITE)
                icon = ContextCompat.getDrawable(this@ClassMateAdminAttendanceActivity, R.drawable.ic_download_outline)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconTint = ColorStateList.valueOf(Color.WHITE)
                cornerRadius = (10 * density).toInt()
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    exportCourseExcel(course.optString("semester_course_id"), course.optString("course_code"))
                }
            }

            actionRow.addView(btnStart)
            actionRow.addView(btnViewRoster)
            actionRow.addView(btnExport)
            inner.addView(actionRow)

            card.addView(inner)
            containerCourseCards.addView(card)
        }

        if (takenCourses.isNotEmpty()) {
            addSectionHeader("SESSIONS TAKEN", takenCourses.size)
            takenCourses.forEach { addCourseCard(it) }
        }
        if (untakenCourses.isNotEmpty()) {
            addSectionHeader("NOT STARTED YET", untakenCourses.size)
            untakenCourses.forEach { addCourseCard(it) }
        }
    }

    // 4. Course Matrix Dialog
        private fun isSessionPresent(records: JSONObject, sessionId: String): Boolean {
        val recObj = records.optJSONObject(sessionId)
        if (recObj != null) {
            if (recObj.optBoolean("present", false)) return true
            val st = recObj.optString("status", "")
            return st.equals("P", ignoreCase = true) || st.equals("present", ignoreCase = true)
        }
        val str = records.optString(sessionId, "")
        return str.equals("present", ignoreCase = true) || str.equals("P", ignoreCase = true) || str == "1" || str.equals("true", ignoreCase = true)
    }

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

    private fun sortStudentsWithReaddLast(list: MutableList<JSONObject>) {
        val pfxRegex = Regex("^([A-Za-z]+)(\\d{2})")
        val counts = mutableMapOf<String, Int>()
        list.forEach { s ->
            val roll = s.optString("roll").ifEmpty { s.optString("roll_number") }.trim().uppercase()
            val m = pfxRegex.find(roll)
            if (m != null) counts[m.value] = (counts[m.value] ?: 0) + 1
        }
        val majorityPfx = counts.maxByOrNull { it.value }?.key.orEmpty()
        val majorityYear = pfxRegex.find(majorityPfx)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        fun isReadd(s: JSONObject): Boolean {
            if (s.optBoolean("is_readd", false)) return true
            val roll = s.optString("roll").ifEmpty { s.optString("roll_number") }.trim().uppercase()
            val m = pfxRegex.find(roll)
            val yr = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            return majorityYear > 0 && yr in 1 until majorityYear
        }

        list.sortWith { a, b ->
            val aReadd = isReadd(a)
            val bReadd = isReadd(b)
            if (aReadd != bReadd) {
                if (aReadd) 1 else -1
            } else {
                val rollA = a.optString("roll").ifEmpty { a.optString("roll_number") }
                val rollB = b.optString("roll").ifEmpty { b.optString("roll_number") }
                ClassMateTeacherLiveAttendanceActivity.compareRolls(rollA, rollB)
            }
        }
    }

    private fun formatSessionDateDDMM(dateStr: String): String {
        val clean = dateStr.trim()
        val parts = clean.split("-")
        if (parts.size >= 3) {
            val day = parts[2].take(2).padStart(2, '0')
            val month = parts[1].padStart(2, '0')
            return "$day-$month"
        }
        return clean.takeLast(5)
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
            val d = formatSessionDateDDMM(sess.optString("date"))
            headerRow.addView(cellText(d, true))
        }
        headerRow.addView(cellText("Attendance (%)", true))
        table.addView(headerRow)

        // Sort students with re-add students at the very last
        val studentList = mutableListOf<JSONObject>()
        for (i in 0 until students.length()) {
            studentList.add(students.getJSONObject(i))
        }
        sortStudentsWithReaddLast(studentList)

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
                val isPresent = isSessionPresent(records, sessId)
                val cell = cellText(
                    if (isPresent) "P" else "—",
                    isHeader = false,
                    color = if (isPresent) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
                )
                cell.typeface = Typeface.DEFAULT_BOLD
                row.addView(cell)
            }

            val pCount = s.optInt("present_count", 0)
            val total = s.optInt("total_sessions", sessions.length())
            val pct = s.optInt("percentage", 0)
            val summaryCell = cellText("$pCount/$total ($pct%)", false, if (pct >= 75) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
            summaryCell.typeface = Typeface.DEFAULT_BOLD
            row.addView(summaryCell)

            table.addView(row)
        }
    }

    // 5. Excel Export
    private fun exportCourseExcel(courseOfferingId: String, courseCode: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Generating Excel sheet...", Toast.LENGTH_SHORT).show()
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val token = ClassMateAuthApi.accessToken
                val apiUrl = "https://classmatebd.vercel.app/api/export-attendance"

                val body = JSONObject()
                    .put("semester_course_id", courseOfferingId)
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                var bytes: ByteArray? = null
                try {
                    bytes = withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) response.body?.bytes() else null
                        }
                    }
                } catch (_: Exception) {
                    bytes = null
                }

                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val cleanCode = courseCode.ifBlank { "Class" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir

                if (bytes != null && bytes.isNotEmpty()) {
                    val fileName = "Attendance_${cleanCode}_$dateStr.xlsx"
                    val file = File(dir, fileName)
                    withContext(Dispatchers.IO) {
                        FileOutputStream(file).use { it.write(bytes) }
                    }

                    Toast.makeText(this@ClassMateAdminAttendanceActivity, "Downloaded: ${file.name}", Toast.LENGTH_LONG).show()

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
                } else {
                    // Reliable local CSV fallback
                    val matrix = withContext(Dispatchers.IO) {
                        ClassMateAuthApi.rpc("course_attendance_matrix", JSONObject().put("target_semester_course", courseOfferingId))
                    }
                    val sessions = matrix.optJSONArray("sessions") ?: JSONArray()
                    val students = matrix.optJSONArray("students") ?: JSONArray()
                    val studentList = mutableListOf<JSONObject>()
                    for (i in 0 until students.length()) studentList.add(students.getJSONObject(i))
                    sortStudentsWithReaddLast(studentList)

                    val csv = StringBuilder()
                    csv.append("Student ID / Roll,Student Name")
                    for (i in 0 until sessions.length()) {
                        val sess = sessions.getJSONObject(i)
                        val d = formatSessionDateDDMM(sess.optString("date"))
                        csv.append(",$d")
                    }
                    csv.append(",Percentage (%)\n")

                    studentList.forEachIndexed { idx, s ->
                        val roll = s.optString("roll").replace(",", " ")
                        val name = s.optString("full_name").replace(",", " ")
                        csv.append("\"$roll\",\"$name\"")
                        val records = s.optJSONObject("records") ?: JSONObject()
                        for (i in 0 until sessions.length()) {
                            val sid = sessions.getJSONObject(i).optString("session_id")
                            val isP = isSessionPresent(records, sid)
                            csv.append(if (isP) ",P" else ",—")
                        }
                        val p = s.optInt("present_count", 0)
                        val tot = s.optInt("total_sessions", sessions.length())
                        val pct = s.optInt("percentage", 0)
                        csv.append(",$p/$tot ($pct%)\n")
                    }

                    val fileName = "Attendance_${cleanCode}_$dateStr.csv"
                    val file = File(dir, fileName)
                    withContext(Dispatchers.IO) {
                        file.writeText(csv.toString(), Charsets.UTF_8)
                    }

                    Toast.makeText(this@ClassMateAdminAttendanceActivity, "Downloaded: ${file.name}", Toast.LENGTH_LONG).show()

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                            this@ClassMateAdminAttendanceActivity,
                            "${packageName}.fileprovider",
                            file
                        ))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Attendance Sheet"))
                }
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
                sortStudentsWithReaddLast(studentsList)
                NfcCardLocalCache.cacheRoster(this@ClassMateAdminAttendanceActivity, studentsList, batchLabel)

                val registeredCount = studentsList.count {
                    val uid = it.optString("rfid_card_uid").trim()
                    uid.isNotEmpty() && uid != "null"
                }
                tvStudentDirectoryCount.text = "$registeredCount/${studentsList.size} Cards Registered"
                setupStudentAutocomplete()
                renderStudentDirectoryCards()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to load students: ${com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)}", Toast.LENGTH_SHORT).show()
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
        val btnAdminRestartCard = findViewById<MaterialButton>(R.id.btnAdminRestartCard)
        val btnWritePhysicalCard = findViewById<MaterialButton>(R.id.btnWritePhysicalCard)
        btnWritePhysicalCard?.visibility = if (uid.isNotEmpty() && uid != "null") View.VISIBLE else View.GONE
        btnWritePhysicalCard?.setOnClickListener {
            ClassMateHaptics.selection(it)
            val writeDialog = MaterialAlertDialogBuilder(this)
                .setTitle("Write Card for $name")
                .setMessage("Touch the NFC card to the back of your phone now.\n\nThis will program the card with the instant ClassMate shortcut and public web identity link.")
                .setIcon(R.drawable.ic_rfid_signal)
                .setNegativeButton("Cancel", null)
                .create()
            activeCardWriteDialog = writeDialog
            writeDialog.setOnDismissListener { activeCardWriteDialog = null }
            writeDialog.show()
        }

        if (uid.isNotEmpty() && uid != "null") {
            tvSelectedStudentCardStatus.text = "Card Registered: ••••${uid.takeLast(4).uppercase()}"
            tvSelectedStudentCardStatus.setTextColor(Color.parseColor("#10B981"))
            etCardUid.setText(uid)
            btnAdminRestartCard?.visibility = View.VISIBLE
            btnAdminRestartCard?.setOnClickListener {
                ClassMateHaptics.selection(it)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Restart Student Card?")
                    .setMessage("Unlink this card from $name ($roll)? The card can then be assigned to someone else.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Restart Card") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val studentId = student.optString("student_id")
                                withContext(Dispatchers.IO) {
                                    ClassMateAuthApi.rpc("restart_student_rfid", JSONObject().put("p_student_id", studentId))
                                }
                                student.put("rfid_card_uid", "")
                                selectStudentForCard(student)
                                renderStudentDirectoryCards()
                                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Card unlinked from $name", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@ClassMateAdminAttendanceActivity, "Failed to unlink: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        } else {
            tvSelectedStudentCardStatus.text = "No card registered yet"
            tvSelectedStudentCardStatus.setTextColor(getColor(R.color.cm_text_muted))
            etCardUid.setText("")
            btnAdminRestartCard?.visibility = View.GONE
        }
    }

    private fun saveStudentCard(writtenToHardware: Boolean = false) {
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
                val studentId = student.optString("student_id")
                val params = JSONObject()
                    .put("p_student_id", studentId)
                    .put("p_card_uid", cleanUid)

                withContext(Dispatchers.IO) {
                    try {
                        ClassMateAuthApi.rpc("enroll_student_rfid", params)
                    } catch (e: Exception) {
                        if (e.message?.contains("schema cache", ignoreCase = true) == true) {
                            val altParams = JSONObject()
                                .put("target_student", studentId)
                                .put("target_card_uid", cleanUid)
                            ClassMateAuthApi.rpc("enroll_student_rfid", altParams)
                        } else {
                            throw e
                        }
                    }
                }

                val hwNote = if (writtenToHardware) " & written to card memory" else ""
                Toast.makeText(
                    this@ClassMateAdminAttendanceActivity,
                    "Card $cleanUid assigned to ${student.optString("roll")}$hwNote!",
                    Toast.LENGTH_LONG
                ).show()

                student.put("rfid_card_uid", cleanUid)
                selectStudentForCard(student)
                renderStudentDirectoryCards()
                NfcCardLocalCache.cacheRoster(this@ClassMateAdminAttendanceActivity, studentsList, batchLabel)
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

    private fun setupNfcDispatch() {
        try {
            nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            val nfcIntent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            nfcPendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, flags)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val scannedUid = NfcCardManager.extractUid(intent)
        if (scannedUid.isNotEmpty()) {
            ClassMateHaptics.selection(window.decorView)
            selectTab(false)
            etCardUid.setText(scannedUid)

            val student = selectedStudentObj
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            }

            if (activeCardWriteDialog != null && student != null) {
                if (tag != null) {
                    val payload = NfcCardManager.StudentCardPayload(
                        uid = scannedUid,
                        studentId = student.optString("student_id"),
                        name = student.optString("full_name"),
                        roll = student.optString("roll"),
                        batch = batchLabel,
                        dept = "MBSTU",
                        phone = student.optString("phone", student.optString("mobile_number", "")),
                        blood = student.optString("blood_group", "")
                    )
                    val writeRes = NfcCardManager.writeStudentCard(tag, payload)
                    activeCardWriteDialog?.dismiss()
                    activeCardWriteDialog = null
                    if (writeRes.isSuccess) {
                        ClassMateHaptics.selection(window.decorView)
                        Toast.makeText(this, "✓ Card written & programmed for ${student.optString("full_name")}!", Toast.LENGTH_LONG).show()
                        saveStudentCard(true)
                    } else {
                        ClassMateHaptics.selection(window.decorView)
                        Toast.makeText(this, "⚠️ Failed to write card: ${writeRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }

            if (student != null) {
                if (tag != null) {
                    val payload = NfcCardManager.StudentCardPayload(
                        uid = scannedUid,
                        studentId = student.optString("student_id"),
                        name = student.optString("full_name"),
                        roll = student.optString("roll"),
                        batch = batchLabel,
                        dept = "MBSTU",
                        phone = student.optString("phone", student.optString("mobile_number", "")),
                        blood = student.optString("blood_group", "")
                    )
                    val writeRes = NfcCardManager.writeStudentCard(tag, payload)
                    saveStudentCard(writeRes.isSuccess)
                } else {
                    saveStudentCard(false)
                }
            } else {
                val matched = studentsList.firstOrNull {
                    it.optString("rfid_card_uid").equals(scannedUid, ignoreCase = true)
                }
                if (matched != null) {
                    selectStudentForCard(matched)
                    actvStudentSearch.setText("${matched.optString("roll")} - ${matched.optString("full_name")}", false)
                    Toast.makeText(this, "Identified student: ${matched.optString("roll")}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Card $scannedUid scanned. Select a student to assign.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

