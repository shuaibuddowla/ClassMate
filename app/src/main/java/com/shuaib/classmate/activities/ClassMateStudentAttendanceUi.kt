package com.shuaib.classmate.activities

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.ClassMateHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Dedicated UI for student attendance in the Timetable screen.
 * Displays only courses enabled for attendance tracking, dynamically updates
 * summary scope, provides compact course cards with semantic health accents,
 * thin progress bars, and expandable chronological history.
 */
internal class ClassMateStudentAttendanceUi(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val batchId: () -> String,
) {
    val scroll = ScrollView(activity).apply {
        isFillViewport = true
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        setPadding(0, 0, 0, dp(120))
    }

    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(24))
    }

    data class SessionHistory(
        val sessionId: String,
        val date: String,
        val time: String,
        val room: String,
        val status: String, // "present", "absent", "late"
        val markedAt: String?,
    )

    data class StudentCourse(
        val semesterCourseId: String,
        val courseCode: String,
        val courseTitle: String,
        val teacherName: String,
        val classesHeld: Int,
        val classesAttended: Int,
        val percentage: Double,
        val history: List<SessionHistory>,
    )

    private var isLoading = true
    private var errorMessage: String? = null
    private var overallPercentage = 0.0
    private var totalHeld = 0
    private var totalAttended = 0
    private var courses: List<StudentCourse> = emptyList()

    private var selectedCourseId: String? = null
    private val expandedCourseIds = mutableSetOf<String>()
    private val showAllHistoryCourseIds = mutableSetOf<String>()

    init {
        scroll.addView(panel)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = activity.getColor(id)

    private fun healthColor(pct: Double): Int {
        return when {
            pct >= 75.0 -> Color.parseColor("#22C55E") // Healthy / Eligible
            pct >= 60.0 -> Color.parseColor("#F59E0B") // Borderline / Warning
            else -> Color.parseColor("#EF4444")        // Critical / At Risk
        }
    }

    fun show(force: Boolean = false) {
        if (!force && courses.isNotEmpty() && !isLoading) {
            render()
            return
        }

        isLoading = true
        errorMessage = null
        render()

        scope.launch {
            try {
                val currentBatch = batchId()
                val response = withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("student_my_attendance", JSONObject().put("target_batch", currentBatch))
                }

                overallPercentage = response.optDouble("overall_percentage", 0.0)
                totalHeld = response.optInt("total_classes_held", 0)
                totalAttended = response.optInt("total_classes_attended", 0)

                val coursesArray = response.optJSONArray("courses") ?: JSONArray()
                val parsedCourses = mutableListOf<StudentCourse>()

                for (i in 0 until coursesArray.length()) {
                    val cObj = coursesArray.optJSONObject(i) ?: continue
                    val histArray = cObj.optJSONArray("history") ?: JSONArray()
                    val historyList = mutableListOf<SessionHistory>()

                    for (j in 0 until histArray.length()) {
                        val hObj = histArray.optJSONObject(j) ?: continue
                        historyList.add(
                            SessionHistory(
                                sessionId = hObj.optString("session_id"),
                                date = hObj.optString("date"),
                                time = hObj.optString("time"),
                                room = hObj.optString("room"),
                                status = hObj.optString("status", "absent").lowercase(),
                                markedAt = if (hObj.isNull("marked_at")) null else hObj.optString("marked_at"),
                            )
                        )
                    }

                    parsedCourses.add(
                        StudentCourse(
                            semesterCourseId = cObj.optString("semester_course_id"),
                            courseCode = cObj.optString("course_code"),
                            courseTitle = cObj.optString("course_title"),
                            teacherName = cObj.optString("teacher_name").let {
                                if (it.isBlank() || it.equals("null", true) || it.equals("Instructor", true)) "" else it
                            },
                            classesHeld = cObj.optInt("classes_held", 0),
                            classesAttended = cObj.optInt("classes_attended", 0),
                            percentage = cObj.optDouble("percentage", 0.0),
                            history = historyList,
                        )
                    )
                }

                courses = parsedCourses
                isLoading = false
                errorMessage = null

                // If only 1 course is available, default to expanded
                if (courses.size == 1) {
                    expandedCourseIds.add(courses[0].semesterCourseId)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = e.message ?: "Could not load attendance records"
            }

            if (scroll.isAttachedToWindow) {
                render()
            }
        }
    }

    private fun render() {
        panel.removeAllViews()

        // 1. Loading State
        if (isLoading) {
            val loadingContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(48))
            }
            val progress = ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(color(R.color.cm_primary))
            }
            val loadingText = TextView(activity).apply {
                text = "Loading attendance records..."
                textSize = 13.5f
                setTextColor(color(R.color.cm_text_muted))
                setPadding(0, dp(14), 0, 0)
            }
            loadingContainer.addView(progress)
            loadingContainer.addView(loadingText)
            panel.addView(loadingContainer)
            return
        }

        // 2. Error State
        if (errorMessage != null) {
            val errorCard = MaterialCardView(activity).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#FCA5A5")
                setCardBackgroundColor(Color.parseColor("#FEF2F2"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            }
            val errorInner = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }
            val errorLabel = TextView(activity).apply {
                text = "Failed to load attendance"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#B91C1C"))
            }
            val errorSub = TextView(activity).apply {
                text = errorMessage
                textSize = 12.5f
                setTextColor(Color.parseColor("#7F1D1D"))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(14))
            }
            val retryBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Retry"
                isAllCaps = false
                textSize = 13f
                cornerRadius = dp(14)
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                setTextColor(Color.parseColor("#DC2626"))
                setOnClickListener { show(force = true) }
            }
            errorInner.addView(errorLabel)
            errorInner.addView(errorSub)
            errorInner.addView(retryBtn)
            errorCard.addView(errorInner)
            panel.addView(errorCard)
            return
        }

        // 3. Empty State (No tracked courses in batch)
        if (courses.isEmpty()) {
            val emptyCard = MaterialCardView(activity).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = color(R.color.cm_border)
                setCardBackgroundColor(color(R.color.cm_surface))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
            }
            val emptyInner = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(36), dp(24), dp(36))
            }
            val emptyTitle = TextView(activity).apply {
                text = "No Attendance-Enabled Courses"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.cm_text_primary))
            }
            val emptySub = TextView(activity).apply {
                text = "Attendance tracking has not been enabled for any courses in this batch yet."
                textSize = 12.5f
                setTextColor(color(R.color.cm_text_muted))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            emptyInner.addView(emptyTitle)
            emptyInner.addView(emptySub)
            emptyCard.addView(emptyInner)
            panel.addView(emptyCard)
            return
        }

        // 4. Determine Dynamic Summary Scope
        val activeCourse = if (courses.size == 1) {
            courses[0]
        } else {
            selectedCourseId?.let { id -> courses.find { it.semesterCourseId == id } }
        }

        val displayPct: Double
        val displayPctStr: String
        val pctColor: Int
        val healthLabel: String
        val badgeBgColor: Int
        val badgeTextColor: Int
        val summaryText: String

        if (activeCourse != null) {
            // Course-specific summary
            val hasClasses = activeCourse.classesHeld > 0
            displayPct = activeCourse.percentage
            displayPctStr = if (hasClasses) {
                if (displayPct % 1.0 == 0.0) "${displayPct.toInt()}%" else "${String.format("%.1f", displayPct)}%"
            } else {
                "0%"
            }
            if (hasClasses) {
                pctColor = healthColor(displayPct)
                healthLabel = when {
                    displayPct >= 75.0 -> "Eligible"
                    displayPct >= 60.0 -> "Warning"
                    else -> "At Risk"
                }
                badgeBgColor = when {
                    displayPct >= 75.0 -> Color.parseColor("#DCFCE7") // Light green
                    displayPct >= 60.0 -> Color.parseColor("#FEF3C7") // Light amber
                    else -> Color.parseColor("#FEE2E2")               // Light red
                }
                badgeTextColor = pctColor
            } else {
                pctColor = color(R.color.cm_text_muted)
                healthLabel = "No classes yet"
                badgeBgColor = Color.parseColor("#F1F5F9")
                badgeTextColor = color(R.color.cm_text_muted)
            }

            summaryText = if (activeCourse.classesHeld == 0) {
                "No classes held yet in ${activeCourse.courseCode}"
            } else {
                "${activeCourse.classesAttended} of ${activeCourse.classesHeld} classes attended in ${activeCourse.courseCode}"
            }
        } else {
            // Overall summary across multiple tracked courses
            val hasClasses = totalHeld > 0
            displayPct = overallPercentage
            displayPctStr = if (hasClasses) {
                if (displayPct % 1.0 == 0.0) "${displayPct.toInt()}%" else "${String.format("%.1f", displayPct)}%"
            } else {
                "0%"
            }
            if (hasClasses) {
                pctColor = healthColor(displayPct)
                healthLabel = when {
                    displayPct >= 75.0 -> "Eligible"
                    displayPct >= 60.0 -> "Warning"
                    else -> "At Risk"
                }
                badgeBgColor = when {
                    displayPct >= 75.0 -> Color.parseColor("#DCFCE7") // Light green
                    displayPct >= 60.0 -> Color.parseColor("#FEF3C7") // Light amber
                    else -> Color.parseColor("#FEE2E2")               // Light red
                }
                badgeTextColor = pctColor
            } else {
                pctColor = color(R.color.cm_text_muted)
                healthLabel = "No classes yet"
                badgeBgColor = Color.parseColor("#F1F5F9")
                badgeTextColor = color(R.color.cm_text_muted)
            }

            val trackedCount = courses.size
            summaryText = if (totalHeld == 0) {
                "No classes held yet across $trackedCount tracked courses"
            } else {
                "$totalAttended of $totalHeld classes attended across $trackedCount tracked courses"
            }
        }

        // 4a. Summary Overview Card (Polished, matching ClassMate hero cards)
        val summaryCard = MaterialCardView(activity).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.cm_border_glass)
            setCardBackgroundColor(color(R.color.cm_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val summaryInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // Top Row: Percentage + Health Badge Pill
        val summaryTopRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvBigPct = TextView(activity).apply {
            text = displayPctStr
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pctColor)
            letterSpacing = -0.02f
        }

        val tvHealthBadge = TextView(activity).apply {
            text = healthLabel
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(badgeTextColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(badgeBgColor)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10)
            }
        }

        summaryTopRow.addView(tvBigPct)
        summaryTopRow.addView(tvHealthBadge)
        summaryInner.addView(summaryTopRow)

        // Subtitle text
        val tvSummaryDesc = TextView(activity).apply {
            text = summaryText
            textSize = 13.5f
            setTextColor(color(R.color.cm_text_muted))
            setPadding(0, dp(6), 0, dp(10))
        }
        summaryInner.addView(tvSummaryDesc)

        // Overview Progress Bar
        val overviewTrack = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(Color.parseColor("#F1F5F9"))
            }
        }
        val safeOverviewPct = displayPct.coerceIn(0.0, 100.0)
        val overviewFill = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, safeOverviewPct.toFloat()).apply {
                if (safeOverviewPct <= 0.0) weight = 0.001f
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(pctColor)
            }
        }
        val overviewEmpty = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100.0 - safeOverviewPct).toFloat())
        }
        overviewTrack.addView(overviewFill)
        overviewTrack.addView(overviewEmpty)
        summaryInner.addView(overviewTrack)

        summaryCard.addView(summaryInner)
        panel.addView(summaryCard)

        // Section Label
        val sectionLabel = TextView(activity).apply {
            text = "TRACKED COURSES"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.cm_text_disabled))
            letterSpacing = 0.12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        panel.addView(sectionLabel)

        // 5. Course Cards List
        courses.forEach { course ->
            val isExpanded = expandedCourseIds.contains(course.semesterCourseId)
            val isSelected = selectedCourseId == course.semesterCourseId
            val courseColor = healthColor(course.percentage)

            val courseCard = MaterialCardView(activity).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = if (isSelected) color(R.color.cm_primary) else color(R.color.cm_border_glass)
                setCardBackgroundColor(color(R.color.cm_surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }

            val cardContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            // Left colored accent indicator bar (4dp width)
            val accentBar = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(courseColor)
                }
            }
            cardContainer.addView(accentBar)

            // Main card content area
            val cardContent = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Header Section (Clickable to toggle expand/select)
            val cardHeader = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ClassMateHaptics.selection(it)
                    if (isExpanded) {
                        expandedCourseIds.remove(course.semesterCourseId)
                        if (selectedCourseId == course.semesterCourseId) {
                            selectedCourseId = null
                        }
                    } else {
                        expandedCourseIds.add(course.semesterCourseId)
                        selectedCourseId = course.semesterCourseId
                    }
                    render()
                }
            }

            // Title & Code Row with Percentage Badge
            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(activity).apply {
                text = "${course.courseCode} — ${course.courseTitle}"
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.cm_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvPctBadge = TextView(activity).apply {
                val hasClasses = course.classesHeld > 0
                val pctStr = if (hasClasses) {
                    if (course.percentage % 1.0 == 0.0) "${course.percentage.toInt()}%" else "${String.format("%.1f", course.percentage)}%"
                } else {
                    "0%"
                }
                text = pctStr
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                if (hasClasses) {
                    setTextColor(courseColor)
                    val badgeBgColor = when {
                        course.percentage >= 75.0 -> Color.parseColor("#DCFCE7")
                        course.percentage >= 60.0 -> Color.parseColor("#FEF3C7")
                        else -> Color.parseColor("#FEE2E2")
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(badgeBgColor)
                    }
                } else {
                    setTextColor(color(R.color.cm_text_muted))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor("#F1F5F9"))
                    }
                }
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }

            titleRow.addView(tvTitle)
            titleRow.addView(tvPctBadge)
            cardHeader.addView(titleRow)

            // Instructor name row
            if (course.teacherName.isNotBlank()) {
                val teacherRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(6))
                }
                val ivPerson = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_person)
                    imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.cm_text_disabled))
                    layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply {
                        marginEnd = dp(5)
                    }
                }
                val tvTeacher = TextView(activity).apply {
                    text = course.teacherName
                    textSize = 12f
                    setTextColor(color(R.color.cm_text_muted))
                }
                teacherRow.addView(ivPerson)
                teacherRow.addView(tvTeacher)
                cardHeader.addView(teacherRow)
            } else {
                val spacer = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
                }
                cardHeader.addView(spacer)
            }

            // Progress Bar Row with classes count and chevron expand indicator
            val progressRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }

            val progressTrack = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(99).toFloat()
                    setColor(Color.parseColor("#F1F5F9"))
                }
            }

            val safePct = course.percentage.coerceIn(0.0, 100.0)
            val progressFill = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, safePct.toFloat()).apply {
                    if (safePct <= 0.0) weight = 0.001f
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(99).toFloat()
                    setColor(courseColor)
                }
            }
            val progressEmpty = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100.0 - safePct).toFloat())
            }

            progressTrack.addView(progressFill)
            progressTrack.addView(progressEmpty)

            val tvClassCount = TextView(activity).apply {
                text = "${course.classesAttended} / ${course.classesHeld} classes"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.cm_text_muted))
                setPadding(dp(10), 0, dp(6), 0)
            }

            val ivChevron = ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.cm_text_disabled))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                rotation = if (isExpanded) 90f else 0f
            }

            progressRow.addView(progressTrack)
            progressRow.addView(tvClassCount)
            progressRow.addView(ivChevron)
            cardHeader.addView(progressRow)

            cardContent.addView(cardHeader)

            // Expanded Session History
            if (isExpanded) {
                val historyContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor("#F8FAFC"))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

                if (course.history.isEmpty()) {
                    val emptyHistText = TextView(activity).apply {
                        text = "No attendance sessions recorded yet."
                        textSize = 12f
                        setTextColor(color(R.color.cm_text_muted))
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                    }
                    historyContainer.addView(emptyHistText)
                } else {
                    val showAll = showAllHistoryCourseIds.contains(course.semesterCourseId)
                    val displayList = if (showAll) course.history else course.history.take(4)

                    displayList.forEachIndexed { idx, hist ->
                        val historyRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(2), dp(6), dp(2), dp(6))
                        }

                        // Formatted date and time
                        val dateFormatted = try {
                            LocalDate.parse(hist.date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                        } catch (_: Exception) {
                            hist.date
                        }
                        val timeStr = if (hist.time.isNotBlank()) " · ${hist.time}" else ""

                        val tvDate = TextView(activity).apply {
                            text = "$dateFormatted$timeStr"
                            textSize = 12.5f
                            setTextColor(color(R.color.cm_text_primary))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        // Status right aligned as a refined pill badge
                        val isAttended = hist.status == "present" || hist.status == "late"
                        val statusLabel = if (isAttended) "✓ Present" else "✗ Absent"
                        val statusColor = if (isAttended) Color.parseColor("#15803D") else Color.parseColor("#B91C1C")
                        val statusBg = if (isAttended) Color.parseColor("#DCFCE7") else Color.parseColor("#FEE2E2")

                        val tvStatusBadge = TextView(activity).apply {
                            text = statusLabel
                            textSize = 11.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(statusColor)
                            background = GradientDrawable().apply {
                                cornerRadius = dp(12).toFloat()
                                setColor(statusBg)
                            }
                            setPadding(dp(9), dp(3), dp(9), dp(3))
                        }

                        historyRow.addView(tvDate)
                        historyRow.addView(tvStatusBadge)
                        historyContainer.addView(historyRow)

                        if (idx < displayList.size - 1) {
                            val rowDivider = View(activity).apply {
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                                    topMargin = dp(2)
                                    bottomMargin = dp(2)
                                }
                                setBackgroundColor(Color.parseColor("#E2E8F0"))
                            }
                            historyContainer.addView(rowDivider)
                        }
                    }

                    // View all / Show less toggle button if history > 4
                    if (course.history.size > 4) {
                        val toggleAllBtn = TextView(activity).apply {
                            text = if (showAll) "Show less" else "View all (${course.history.size} sessions)  ↓"
                            textSize = 12f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(color(R.color.cm_primary))
                            gravity = Gravity.CENTER
                            setPadding(0, dp(10), 0, dp(4))
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                ClassMateHaptics.selection(it)
                                if (showAll) {
                                    showAllHistoryCourseIds.remove(course.semesterCourseId)
                                } else {
                                    showAllHistoryCourseIds.add(course.semesterCourseId)
                                }
                                render()
                            }
                        }
                        historyContainer.addView(toggleAllBtn)
                    }
                }

                cardContent.addView(historyContainer)
            }

            cardContainer.addView(cardContent)
            courseCard.addView(cardContainer)
            panel.addView(courseCard)
        }
    }
}
