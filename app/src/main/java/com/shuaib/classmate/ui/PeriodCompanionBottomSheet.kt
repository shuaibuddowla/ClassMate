package com.shuaib.classmate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.models.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object PeriodCompanionBottomSheet {

    private const val TAG = "PeriodCompanion"

    private data class SessionRecord(
        val sessionId: String,
        val date: String,
        val time: String,
        val room: String,
        val status: String
    )

    private data class CourseAttendanceInfo(
        val semesterCourseId: String,
        val courseCode: String,
        val courseTitle: String,
        val classesHeld: Int,
        val classesAttended: Int,
        val percentage: Double,
        val history: List<SessionRecord>
    )

    private data class TeacherInfo(
        val name: String,
        val designation: String,
        val department: String,
        val email: String,
        val courses: List<String>
    )

    fun show(
        fragment: Fragment,
        period: Period,
        isAdmin: Boolean,
        batchId: String,
        onChangePeriod: (() -> Unit)? = null,
        onOpenResources: (() -> Unit)? = null
    ) {
        val act = fragment.activity as? FragmentActivity ?: return
        show(act, period, isAdmin, batchId, onChangePeriod, onOpenResources)
    }

    fun show(
        activity: FragmentActivity,
        period: Period,
        isAdmin: Boolean,
        batchId: String,
        onChangePeriod: (() -> Unit)? = null,
        onOpenResources: (() -> Unit)? = null
    ) {
        try {
            Log.d(TAG, "show() invoked for subject: ${period.subject}")

            val dialog = BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)
            val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_period_companion, null, false)
            dialog.setContentView(view)

            dialog.behavior.apply {
                isFitToContents = true
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }

            val density = activity.resources.displayMetrics.density
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                v.setPadding(0, 0, 0, (20 * density).toInt() + navBars.bottom)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(view)

            dialog.setOnShowListener {
                val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet != null) {
                    val behavior = BottomSheetBehavior.from(bottomSheet)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                    behavior.isFitToContents = true
                }
            }

            // ViewFlipper and container views
            val viewFlipper = view.findViewById<ViewFlipper>(R.id.viewFlipperCompanion)

            // --- View 0 (Companion Home) Views ---
            val tvCourseTypeBadge = view.findViewById<TextView>(R.id.tvCourseTypeBadge)
            val tvRoutineSlotBadge = view.findViewById<TextView>(R.id.tvRoutineSlotBadge)
            val tvRoomBadge = view.findViewById<TextView>(R.id.tvRoomBadge)
            val tvCourseCode = view.findViewById<TextView>(R.id.tvCourseCode)
            val tvCourseName = view.findViewById<TextView>(R.id.tvCourseName)
            val btnChangePeriod = view.findViewById<MaterialButton>(R.id.btnChangePeriod)
            val cardOptionAttendance = view.findViewById<MaterialCardView>(R.id.cardOptionAttendance)
            val tvAttendanceSummary = view.findViewById<TextView>(R.id.tvAttendanceSummary)
            val tvAttendancePill = view.findViewById<TextView>(R.id.tvAttendancePill)
            val cardOptionResources = view.findViewById<MaterialCardView>(R.id.cardOptionResources)
            val cardOptionTeacher = view.findViewById<MaterialCardView>(R.id.cardOptionTeacher)
            val tvTeacherSummary = view.findViewById<TextView>(R.id.tvTeacherSummary)

            // --- View 1 (Attendance Detail) Views ---
            val btnBackAttendance = view.findViewById<LinearLayout>(R.id.btnBackAttendance)
            val tvAttendanceDetailCourseTitle = view.findViewById<TextView>(R.id.tvAttendanceDetailCourseTitle)
            val tvAttendanceDetailPct = view.findViewById<TextView>(R.id.tvAttendanceDetailPct)
            val tvAttendanceHealthBadge = view.findViewById<TextView>(R.id.tvAttendanceHealthBadge)
            val tvAttendanceDetailClassesSummary = view.findViewById<TextView>(R.id.tvAttendanceDetailClassesSummary)
            val pbAttendanceDetail = view.findViewById<ProgressBar>(R.id.pbAttendanceDetail)
            val layoutSessionHistoryContainer = view.findViewById<LinearLayout>(R.id.layoutSessionHistoryContainer)

            // --- View 2 (Teacher Detail) Views ---
            val btnBackTeacher = view.findViewById<LinearLayout>(R.id.btnBackTeacher)
            val tvTeacherDetailInitials = view.findViewById<TextView>(R.id.tvTeacherDetailInitials)
            val tvTeacherDetailName = view.findViewById<TextView>(R.id.tvTeacherDetailName)
            val tvTeacherDetailDesignation = view.findViewById<TextView>(R.id.tvTeacherDetailDesignation)
            val tvTeacherDetailEmail = view.findViewById<TextView>(R.id.tvTeacherDetailEmail)
            val btnTeacherDetailCopyEmail = view.findViewById<MaterialButton>(R.id.btnTeacherDetailCopyEmail)
            val btnTeacherDetailSendEmail = view.findViewById<MaterialButton>(R.id.btnTeacherDetailSendEmail)
            val tvTeacherOtherCoursesLabel = view.findViewById<TextView>(R.id.tvTeacherOtherCoursesLabel)
            val chipGroupTeacherCourses = view.findViewById<ChipGroup>(R.id.chipGroupTeacherCourses)

            // 1. Parse Course Code, Course Title, and Class Type
            val rawSubject = period.subject.trim()
            val parsedCode: String
            val parsedName: String
            val isUuidCode = period.courseCode.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

            if (period.courseCode.isNotBlank() && !isUuidCode) {
                parsedCode = period.courseCode.trim()
                parsedName = if (rawSubject.isNotBlank() && !rawSubject.equals(parsedCode, ignoreCase = true)) rawSubject else ""
            } else {
                val codePattern = Regex("(?i)\\b([A-Z]{2,5}[-\\s]?\\d{3,4}[A-Z]?)\\b")
                val match = codePattern.find(rawSubject)
                if (match != null) {
                    parsedCode = match.value.trim().uppercase(Locale.getDefault())
                    val remaining = rawSubject.removeRange(match.range).trim(' ', '-', ':', '–', '·', '(', ')')
                    parsedName = if (remaining.isNotBlank() && !remaining.equals(parsedCode, ignoreCase = true)) remaining else ""
                } else {
                    parsedCode = rawSubject
                    parsedName = ""
                }
            }

            tvCourseCode.text = parsedCode
            if (parsedName.isNotBlank() && !parsedName.equals(parsedCode, ignoreCase = true)) {
                tvCourseName.text = parsedName
                tvCourseName.isVisible = true
            } else {
                tvCourseName.isVisible = false
            }

            // Class Type Badge
            val isLab = period.classKind.equals("lab", true) ||
                rawSubject.endsWith("lab", true) ||
                rawSubject.endsWith("labs", true)
            val isSessional = period.classKind.equals("sessional", true)

            when {
                isLab -> {
                    tvCourseTypeBadge.text = "LAB"
                    tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DCFCE7"))
                    tvCourseTypeBadge.setTextColor(Color.parseColor("#15803D"))
                }
                isSessional -> {
                    tvCourseTypeBadge.text = "SESSIONAL"
                    tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E8FF"))
                    tvCourseTypeBadge.setTextColor(Color.parseColor("#7E22CE"))
                }
                else -> {
                    tvCourseTypeBadge.text = "THEORY"
                    tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0F2FE"))
                    tvCourseTypeBadge.setTextColor(Color.parseColor("#0284C7"))
                }
            }

            // Routine Slot Badge
            val start12 = formatTo12Hour(period.startTime)
            val end12 = formatTo12Hour(period.endTime)
            tvRoutineSlotBadge.text = if (start12.isNotBlank() && end12.isNotBlank()) {
                "$start12 – $end12"
            } else {
                "Schedule not set"
            }

            // Room Badge
            if (!period.room.isNullOrBlank()) {
                val roomStr = period.room.trim()
                tvRoomBadge.text = if (roomStr.startsWith("Room", ignoreCase = true)) roomStr else "Room $roomStr"
                tvRoomBadge.isVisible = true
            } else {
                tvRoomBadge.isVisible = false
            }

            // 2. CR & Authorized Admin Action: Change Period Button
            btnChangePeriod.isVisible = isAdmin && onChangePeriod != null
            btnChangePeriod.setOnClickListener {
                dialog.dismiss()
                onChangePeriod?.invoke()
            }

            // Teacher summary initial preview
            val teacherNameDisplay = period.teacher.trim()
            if (teacherNameDisplay.isNotBlank()) {
                tvTeacherSummary.text = teacherNameDisplay
            } else {
                tvTeacherSummary.text = "Instructor profile & contacts"
            }

            // 3. Setup Course Resources Action
            cardOptionResources.setOnClickListener {
                dialog.dismiss()
                if (onOpenResources != null) {
                    onOpenResources.invoke()
                } else {
                    val authAct = activity as? com.shuaib.classmate.activities.ClassMateAuthActivity
                    if (authAct != null) {
                        authAct.selectHomeTab(R.id.nav_pdf)
                    } else {
                        val bundle = bundleOf("subjectName" to period.subject)
                        (activity as? MainActivity)?.openChildDestination(
                            R.id.nav_pdf,
                            R.id.fragment_subject_pdf_list,
                            bundle
                        )
                    }
                }
            }

            // Sub-navigation Back Buttons
            fun switchView(index: Int) {
                viewFlipper.displayedChild = index
                viewFlipper.post {
                    val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    bottomSheet?.requestLayout()
                }
            }

            btnBackAttendance.setOnClickListener {
                switchView(0)
            }
            btnBackTeacher.setOnClickListener {
                switchView(0)
            }

            // Dialog Back Key Interception (Nested Sheet Navigation)
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (viewFlipper.displayedChild != 0) {
                        switchView(0)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }

            // State holders for background data
            var isAttendanceLoading = true
            var matchedAttendance: CourseAttendanceInfo? = null
            var matchedTeacher: TeacherInfo? = null

            fun populateAttendanceDetail(att: CourseAttendanceInfo) {
                tvAttendanceDetailCourseTitle.text = "${att.courseCode} — ${att.courseTitle}"
                val pctInt = att.percentage.toInt()
                tvAttendanceDetailPct.text = if (att.classesHeld > 0) "$pctInt%" else "0%"

                val (healthText, badgeBg, badgeText, healthColor) = when {
                    att.percentage >= 75.0 -> Quadruple("Eligible", "#DCFCE7", "#15803D", "#16A34A")
                    att.percentage >= 60.0 -> Quadruple("Borderline", "#FEF3C7", "#B45309", "#D97706")
                    else -> Quadruple("At Risk", "#FEE2E2", "#B91C1C", "#DC2626")
                }

                tvAttendanceHealthBadge.text = healthText
                tvAttendanceHealthBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(badgeBg))
                tvAttendanceHealthBadge.setTextColor(Color.parseColor(badgeText))
                tvAttendanceDetailPct.setTextColor(Color.parseColor(healthColor))
                pbAttendanceDetail.progressTintList = ColorStateList.valueOf(Color.parseColor(healthColor))
                pbAttendanceDetail.progress = pctInt.coerceIn(0, 100)

                tvAttendanceDetailClassesSummary.text = "${att.classesAttended} attended of ${att.classesHeld} classes held"

                // Populate Session History List
                layoutSessionHistoryContainer.removeAllViews()
                val density = activity.resources.displayMetrics.density
                val dp = { v: Int -> (v * density).toInt() }

                if (att.history.isEmpty()) {
                    val emptyTv = TextView(activity).apply {
                        text = "No individual class sessions logged yet."
                        textSize = 12.5f
                        setTextColor(ContextCompat.getColor(activity, R.color.cm_text_muted))
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    }
                    layoutSessionHistoryContainer.addView(emptyTv)
                } else {
                    att.history.forEachIndexed { index, session ->
                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                        }

                        val dateFormatted = try {
                            LocalDate.parse(session.date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                        } catch (_: Exception) {
                            session.date
                        }
                        val timeStr = if (session.time.isNotBlank()) " · ${session.time}" else ""
                        val roomStr = if (session.room.isNotBlank()) " (${session.room})" else ""

                        val tvDate = TextView(activity).apply {
                            text = "$dateFormatted$timeStr$roomStr"
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(activity, R.color.cm_text_primary))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val isAttended = session.status == "present" || session.status == "late"
                        val (sLabel, sColor, sBg) = if (isAttended) {
                            Triple("✓ Present", "#15803D", "#DCFCE7")
                        } else {
                            Triple("✗ Absent", "#B91C1C", "#FEE2E2")
                        }

                        val tvStatus = TextView(activity).apply {
                            text = sLabel
                            textSize = 11.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.parseColor(sColor))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(12).toFloat()
                                setColor(Color.parseColor(sBg))
                            }
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                        }

                        row.addView(tvDate)
                        row.addView(tvStatus)
                        layoutSessionHistoryContainer.addView(row)

                        if (index < att.history.size - 1) {
                            val divider = View(activity).apply {
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                                    topMargin = dp(2)
                                    bottomMargin = dp(2)
                                }
                                setBackgroundColor(Color.parseColor("#E2E8F0"))
                            }
                            layoutSessionHistoryContainer.addView(divider)
                        }
                    }
                }
            }

            fun populateTeacherDetail(teacher: TeacherInfo?, fallbackName: String) {
                val tName = teacher?.name?.takeUnless { it.equals("null", true) }?.ifBlank { fallbackName } ?: fallbackName
                val tDesignation = teacher?.designation?.takeUnless { it.equals("null", true) }?.ifBlank { "Faculty Member" } ?: "Faculty Member"
                val tDept = teacher?.department?.takeUnless { it.equals("null", true) }?.takeIf { it.isNotBlank() }?.let { formatDeptName(it) } ?: "Academic Department"
                val tEmail = teacher?.email?.takeUnless { it.equals("null", true) }.orEmpty()

                tvTeacherDetailName.text = tName
                tvTeacherDetailDesignation.text = if (tDesignation != "Faculty Member" || tDept != "Academic Department") "$tDesignation · $tDept" else tDesignation

                // Initials
                val initials = tName.split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    .joinToString("")
                tvTeacherDetailInitials.text = if (initials.isNotBlank()) initials else "T"

                if (tEmail.isNotBlank()) {
                    tvTeacherDetailEmail.text = tEmail
                    tvTeacherDetailEmail.isVisible = true
                    btnTeacherDetailCopyEmail.isEnabled = true
                    btnTeacherDetailSendEmail.isEnabled = true

                    btnTeacherDetailCopyEmail.setOnClickListener {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Teacher Email", tEmail))
                        Toast.makeText(activity, "Teacher email copied", Toast.LENGTH_SHORT).show()
                    }

                    btnTeacherDetailSendEmail.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$tEmail")
                                putExtra(Intent.EXTRA_SUBJECT, "Query regarding $parsedCode")
                            }
                            activity.startActivity(Intent.createChooser(intent, "Email $tName"))
                        } catch (_: Exception) {
                            Toast.makeText(activity, "No email client installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    tvTeacherDetailEmail.text = "Email address not available"
                    tvTeacherDetailEmail.isVisible = true
                    btnTeacherDetailCopyEmail.isEnabled = false
                    btnTeacherDetailSendEmail.isEnabled = false
                }

                val otherCourses = teacher?.courses?.filter { !it.equals(parsedCode, ignoreCase = true) }.orEmpty()
                if (otherCourses.isNotEmpty()) {
                    tvTeacherOtherCoursesLabel.isVisible = true
                    chipGroupTeacherCourses.isVisible = true
                    chipGroupTeacherCourses.removeAllViews()

                    for (c in otherCourses) {
                        val chip = Chip(activity).apply {
                            text = c
                            isCheckable = false
                            isClickable = false
                            textSize = 11.5f
                            setChipBackgroundColor(ColorStateList.valueOf(Color.parseColor("#F1F5F9")))
                            setTextColor(ContextCompat.getColor(activity, R.color.cm_text_primary))
                        }
                        chipGroupTeacherCourses.addView(chip)
                    }
                } else {
                    tvTeacherOtherCoursesLabel.isVisible = false
                    chipGroupTeacherCourses.isVisible = false
                }
            }

            cardOptionAttendance.setOnClickListener {
                if (isAttendanceLoading) {
                    Toast.makeText(activity, "Loading attendance records...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val att = matchedAttendance
                if (att != null) {
                    populateAttendanceDetail(att)
                    switchView(1)
                } else {
                    Toast.makeText(activity, "Attendance is not tracked digitally for this course yet.", Toast.LENGTH_SHORT).show()
                }
            }

            cardOptionTeacher.setOnClickListener {
                if (matchedTeacher != null || teacherNameDisplay.isNotBlank()) {
                    populateTeacherDetail(matchedTeacher, teacherNameDisplay)
                    switchView(2)
                } else {
                    Toast.makeText(activity, "No teacher assigned to this period yet.", Toast.LENGTH_SHORT).show()
                }
            }

            // SHOW DIALOG IMMEDIATELY
            dialog.show()
            Log.d(TAG, "dialog.show() called successfully")

            // Background Async Fetch: Attendance & Teacher Directory
            activity.lifecycleScope.launch {
                try {
                    // 1. Fetch Student Attendance Records
                    val attendanceData = withContext(Dispatchers.IO) {
                        try {
                            val response = ClassMateAuthApi.rpc("student_my_attendance", JSONObject().put("target_batch", batchId))
                            val coursesArray = response.optJSONArray("courses") ?: JSONArray()
                            val parsed = mutableListOf<CourseAttendanceInfo>()
                            for (i in 0 until coursesArray.length()) {
                                val cObj = coursesArray.optJSONObject(i) ?: continue
                                val histArray = cObj.optJSONArray("history") ?: JSONArray()
                                val hList = mutableListOf<SessionRecord>()
                                for (j in 0 until histArray.length()) {
                                    val hObj = histArray.optJSONObject(j) ?: continue
                                    hList.add(
                                        SessionRecord(
                                            sessionId = hObj.optString("session_id"),
                                            date = hObj.optString("date"),
                                            time = hObj.optString("time"),
                                            room = hObj.optString("room"),
                                            status = hObj.optString("status", "absent").lowercase(Locale.getDefault())
                                        )
                                    )
                                }
                                parsed.add(
                                    CourseAttendanceInfo(
                                        semesterCourseId = cObj.optString("semester_course_id"),
                                        courseCode = cObj.optString("course_code"),
                                        courseTitle = cObj.optString("course_title"),
                                        classesHeld = cObj.optInt("classes_held", 0),
                                        classesAttended = cObj.optInt("classes_attended", 0),
                                        percentage = cObj.optDouble("percentage", 0.0),
                                        history = hList
                                    )
                                )
                            }
                            parsed
                        } catch (e: Exception) {
                            Log.w(TAG, "student_my_attendance fetch failed: ${e.message}")
                            emptyList<CourseAttendanceInfo>()
                        }
                    }

                    // Match attendance for this course
                    matchedAttendance = findMatchingCourse(attendanceData, period, parsedCode, parsedName)
                    isAttendanceLoading = false

                    if (matchedAttendance != null) {
                        val att = matchedAttendance!!
                        val hasClasses = att.classesHeld > 0
                        tvAttendanceSummary.text = if (hasClasses) {
                            "${att.classesAttended} / ${att.classesHeld} classes attended"
                        } else {
                            "No class sessions logged yet"
                        }

                        if (hasClasses) {
                            val pctInt = att.percentage.toInt()
                            tvAttendancePill.text = "$pctInt%"
                            val (pColor, pBg) = when {
                                att.percentage >= 75.0 -> Pair("#15803D", "#DCFCE7")
                                att.percentage >= 60.0 -> Pair("#B45309", "#FEF3C7")
                                else -> Pair("#B91C1C", "#FEE2E2")
                            }
                            tvAttendancePill.setTextColor(Color.parseColor(pColor))
                            tvAttendancePill.backgroundTintList = ColorStateList.valueOf(Color.parseColor(pBg))
                            tvAttendancePill.isVisible = true
                        } else {
                            tvAttendancePill.isVisible = false
                        }

                        // If user already navigated to attendance details page
                        if (viewFlipper.displayedChild == 1) {
                            populateAttendanceDetail(att)
                        }
                    } else {
                        tvAttendanceSummary.text = "Digital attendance not active for this course"
                        tvAttendancePill.isVisible = false
                    }

                    // 2. Fetch Teacher Directory & Course Catalog
                    val teachersDirectory = withContext(Dispatchers.IO) {
                        try {
                            val arr = JSONArray(ClassMateAuthApi.rpcText("batch_teachers_directory", JSONObject().put("target_batch", batchId)))
                            val list = mutableListOf<TeacherInfo>()
                            for (i in 0 until arr.length()) {
                                val tObj = arr.optJSONObject(i) ?: continue
                                val cArr = tObj.optJSONArray("courses")
                                val cList = mutableListOf<String>()
                                if (cArr != null) {
                                    for (k in 0 until cArr.length()) {
                                        val cStr = cArr.optString(k).trim()
                                        if (cStr.isNotBlank()) cList.add(cStr)
                                    }
                                }
                                list.add(
                                    TeacherInfo(
                                        name = tObj.optString("full_name").trim().takeUnless { it.equals("null", true) }.orEmpty(),
                                        designation = tObj.optString("designation").trim().takeUnless { it.equals("null", true) }.orEmpty(),
                                        department = tObj.optString("department_code").trim().takeUnless { it.equals("null", true) }.orEmpty(),
                                        email = tObj.optString("email").trim().takeUnless { it.equals("null", true) }.orEmpty(),
                                        courses = cList
                                    )
                                )
                            }
                            list
                        } catch (e: Exception) {
                            Log.w(TAG, "batch_teachers_directory fetch failed: ${e.message}")
                            emptyList<TeacherInfo>()
                        }
                    }

                    // Match Teacher Info
                    matchedTeacher = findMatchingTeacher(teachersDirectory, period.teacher, parsedCode, parsedName)
                    if (matchedTeacher != null) {
                        val t = matchedTeacher!!
                        val sub = if (t.designation.isNotBlank() && !t.designation.equals("null", true)) "${t.name} (${t.designation})" else t.name
                        tvTeacherSummary.text = sub

                        // If user already opened teacher page
                        if (viewFlipper.displayedChild == 2) {
                            populateTeacherDetail(t, teacherNameDisplay)
                        }
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Error matching async companion data: ${e.message}")
                    isAttendanceLoading = false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying PeriodCompanionBottomSheet", e)
            Toast.makeText(activity, "Could not open class companion", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findMatchingCourse(
        courses: List<CourseAttendanceInfo>,
        period: Period,
        parsedCode: String,
        parsedName: String
    ): CourseAttendanceInfo? {
        if (courses.isEmpty()) return null

        // 1. By offering ID
        val offeringId = period.courseOfferingId.ifBlank {
            if (period.courseCode.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) period.courseCode else ""
        }
        if (offeringId.isNotBlank()) {
            courses.firstOrNull { it.semesterCourseId == offeringId }?.let { return it }
        }

        // 2. By Course Code exact / normalized match
        val normTargetCode = normalize(parsedCode)
        if (normTargetCode.length >= 3) {
            courses.firstOrNull { normalize(it.courseCode) == normTargetCode }?.let { return it }
            courses.firstOrNull { normalize(it.courseCode).contains(normTargetCode) || normTargetCode.contains(normalize(it.courseCode)) }?.let { return it }
        }

        // 3. By Course Title match
        val normTargetName = normalize(parsedName)
        if (normTargetName.length >= 4) {
            courses.firstOrNull { normalize(it.courseTitle) == normTargetName }?.let { return it }
            courses.firstOrNull { normalize(it.courseTitle).contains(normTargetName) || normTargetName.contains(normalize(it.courseTitle)) }?.let { return it }
        }

        // 4. By full subject string match
        val normSubject = normalize(period.subject)
        courses.firstOrNull {
            val cCode = normalize(it.courseCode)
            val cTitle = normalize(it.courseTitle)
            normSubject.contains(cCode) || normSubject.contains(cTitle)
        }?.let { return it }

        return null
    }

    private fun findMatchingTeacher(
        teachers: List<TeacherInfo>,
        periodTeacher: String,
        courseCode: String,
        courseName: String
    ): TeacherInfo? {
        val tNorm = normalize(periodTeacher)
        if (tNorm.length >= 3) {
            teachers.firstOrNull { normalize(it.name) == tNorm }?.let { return it }
            teachers.firstOrNull { normalize(it.name).contains(tNorm) || tNorm.contains(normalize(it.name)) }?.let { return it }
        }

        // If teacher is assigned to this course code/name in the directory
        val cNorm = normalize(courseCode)
        if (cNorm.length >= 3) {
            teachers.firstOrNull { t -> t.courses.any { normalize(it).contains(cNorm) } }?.let { return it }
        }

        return null
    }

    private fun normalize(str: String): String {
        return str.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
    }

    private fun formatDeptName(code: String): String {
        return when (code.lowercase(Locale.getDefault())) {
            "cse" -> "Department of CSE"
            "math" -> "Department of Mathematics"
            "phy" -> "Department of Physics"
            "chem" -> "Department of Chemistry"
            "eco" -> "Department of Economics"
            "acct" -> "Department of Accounting"
            else -> if (code.isNotBlank()) code.uppercase(Locale.getDefault()) else "Academic Faculty"
        }
    }

    private fun formatTo12Hour(time24: String): String {
        return try {
            val time = LocalTime.parse(time24.trim())
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
            time.format(formatter)
        } catch (_: Exception) {
            time24
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
