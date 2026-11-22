package com.shuaib.classmate.ui.assessment

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import com.shuaib.classmate.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.models.AssessmentType
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

data class CourseChoice(
    val courseOfferingId: String,
    val courseCode: String,
    val courseTitle: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PostAssessmentBottomSheet(
    initialAssessment: AssessmentItem? = null,
    currentBatchId: String = "",
    availableCourses: List<CourseChoice>,
    routineLookup: (courseOfferingId: String, dayOfWeek: Int) -> Pair<String, String>?, // returns (startTime, room)
    onDismiss: () -> Unit,
    onSubmit: suspend (
        assessment: AssessmentItem,
        alsoPostNotice: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedType by remember {
        mutableStateOf(initialAssessment?.assessmentType ?: AssessmentType.CLASS_TEST)
    }

    val allCourses = remember(availableCourses, initialAssessment) {
        val list = availableCourses.toMutableList()
        if (initialAssessment != null && (initialAssessment.courseId.isNotBlank() || initialAssessment.courseCode.isNotBlank())) {
            val exists = list.any { 
                (it.courseOfferingId.isNotBlank() && it.courseOfferingId == initialAssessment.courseId) ||
                (it.courseCode.isNotBlank() && it.courseCode.equals(initialAssessment.courseCode, ignoreCase = true))
            }
            if (!exists) {
                list.add(
                    0,
                    CourseChoice(
                        courseOfferingId = initialAssessment.courseId,
                        courseCode = initialAssessment.courseCode,
                        courseTitle = initialAssessment.courseTitle
                    )
                )
            }
        }
        list
    }

    var selectedCourse by remember(initialAssessment, allCourses) {
        mutableStateOf(
            if (initialAssessment != null) {
                allCourses.firstOrNull { it.courseOfferingId.isNotBlank() && it.courseOfferingId == initialAssessment.courseId }
                    ?: allCourses.firstOrNull { it.courseCode.isNotBlank() && it.courseCode.equals(initialAssessment.courseCode, ignoreCase = true) }
                    ?: allCourses.firstOrNull { it.courseTitle.isNotBlank() && it.courseTitle.equals(initialAssessment.courseTitle, ignoreCase = true) }
                    ?: if (initialAssessment.courseId.isNotBlank() || initialAssessment.courseCode.isNotBlank() || initialAssessment.courseTitle.isNotBlank()) {
                        CourseChoice(
                            courseOfferingId = initialAssessment.courseId,
                            courseCode = initialAssessment.courseCode,
                            courseTitle = initialAssessment.courseTitle
                        )
                    } else null
            } else null
        )
    }
    var courseDropdownExpanded by remember { mutableStateOf(false) }

    var title by remember { mutableStateOf(initialAssessment?.title.orEmpty()) }

    var deadlineDate by remember {
        mutableStateOf(initialAssessment?.deadlineDate.orEmpty())
    }

    var deadlineTime by remember {
        mutableStateOf(initialAssessment?.deadlineTime.orEmpty())
    }

    var room by remember { mutableStateOf(initialAssessment?.room.orEmpty()) }
    var syllabus by remember { mutableStateOf(initialAssessment?.syllabus.orEmpty()) }
    var alsoPostNotice by remember { mutableStateOf(initialAssessment == null) }
    var autoFilledFromRoutine by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Smart Routine Auto-fill on Course or Date Change
    fun triggerRoutineAutofill(offeringId: String, dateStr: String) {
        if (offeringId.isBlank() || dateStr.isBlank()) return
        try {
            val date = LocalDate.parse(dateStr)
            val dayOfWeek = date.dayOfWeek.value % 7 // 0=Sun, 1=Mon...
            val match = routineLookup(offeringId, dayOfWeek)
            if (match != null) {
                if (match.first.isNotBlank()) deadlineTime = match.first
                if (match.second.isNotBlank()) room = match.second
                autoFilledFromRoutine = true
                android.widget.Toast.makeText(
                    context,
                    "Autofilled routine: ${match.first} · ${match.second}".trim().removeSuffix("·").trim(),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                autoFilledFromRoutine = false
                android.widget.Toast.makeText(
                    context,
                    "No class scheduled on this day. Please enter time & room manually.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
            autoFilledFromRoutine = false
        }
    }

    fun showTimePicker() {
        val cal = Calendar.getInstance()
        var initHour = 10
        var initMinute = 0
        try {
            val trimmed = deadlineTime.trim().uppercase()
            val isPm = trimmed.contains("PM")
            val isAm = trimmed.contains("AM")
            val parts = trimmed.replace("AM", "").replace("PM", "").trim().split(":")
            if (parts.isNotEmpty()) {
                var h = parts[0].toInt()
                if (isPm && h < 12) h += 12
                if (isAm && h == 12) h = 0
                initHour = h
            }
            if (parts.size > 1) {
                initMinute = parts[1].toInt()
            }
        } catch (_: Exception) {}

        val dialogTheme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        TimePickerDialog(
            context,
            dialogTheme,
            { _, hourOfDay, minute ->
                val amPm = if (hourOfDay < 12) "AM" else "PM"
                val displayHour = when {
                    hourOfDay == 0 -> 12
                    hourOfDay > 12 -> hourOfDay - 12
                    else -> hourOfDay
                }
                deadlineTime = String.format(java.util.Locale.US, "%02d:%02d %s", displayHour, minute, amPm)
            },
            initHour,
            initMinute,
            false
        ).show()
    }

    // Trigger autofill only when both course and deadline date are selected
    LaunchedEffect(selectedCourse, deadlineDate) {
        if (initialAssessment == null && selectedCourse != null && deadlineDate.isNotBlank()) {
            triggerRoutineAutofill(selectedCourse!!.courseOfferingId, deadlineDate)
        }
    }

    val assessmentTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = if (isDark) Color(0xFF131A2A) else Color.Transparent,
        unfocusedContainerColor = if (isDark) Color(0xFF131A2A) else Color.Transparent,
        focusedTextColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
        unfocusedTextColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B),
        focusedBorderColor = Color(0xFF6366F1),
        unfocusedBorderColor = if (isDark) Color(0x28FFFFFF) else Color(0x22000000),
        focusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
        unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
        cursorColor = Color(0xFF6366F1)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = if (isDark) Color(0xFF0B1120) else Color(0xFFFFFFFF),
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0x44FFFFFF) else Color(0x33000000))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (initialAssessment == null) "Post Assessment" else "Edit Assessment",
                        fontSize = 20.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Text(
                        text = "Add to Upcoming timeline & notify batch",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Close",
                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Select Assessment Type (CT, Assignment, Quiz, etc.)
            Text(
                text = "Assessment Type",
                fontSize = 13.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssessmentType.values().forEach { type ->
                    val isSelected = selectedType == type
                    val typeColor = Color(type.colorHex)

                    Surface(
                        onClick = { selectedType = type },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) typeColor.copy(alpha = if (isDark) 0.28f else 0.16f) else (if (isDark) Color(0xFF131A2A) else Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, if (isSelected) typeColor else (if (isDark) Color(0x28FFFFFF) else Color(0x14000000)))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                painter = painterResource(type.iconRes),
                                contentDescription = null,
                                tint = if (isSelected) typeColor else (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = type.shortName,
                                fontSize = 12.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) typeColor else (if (isDark) Color(0xFFE2E8F0) else Color(0xFF475569))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Select Course
            Text(
                text = "Course",
                fontSize = 13.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box {
                Surface(
                    onClick = { courseDropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF131A2A) else Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x22000000)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedCourse?.let { "${it.courseCode} · ${it.courseTitle}" } ?: "Select course",
                            fontSize = 13.5.sp,
                            fontFamily = PlusJakartaBold,
                            color = if (selectedCourse != null) {
                                AssessmentColors.primaryTextColor(isDark)
                            } else {
                                if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            }
                        )
                        Text(text = "▼", fontSize = 11.sp, color = if (isDark) Color(0xFF94A3B8) else Color.Gray)
                    }
                }

                DropdownMenu(
                    expanded = courseDropdownExpanded,
                    onDismissRequest = { courseDropdownExpanded = false },
                    modifier = Modifier.background(if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF))
                ) {
                    allCourses.forEach { course ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${course.courseCode} · ${course.courseTitle}",
                                    fontFamily = PlusJakartaBold,
                                    fontSize = 13.sp,
                                    color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E293B)
                                )
                            },
                            onClick = {
                                selectedCourse = course
                                courseDropdownExpanded = false
                                if (deadlineDate.isNotBlank()) {
                                    triggerRoutineAutofill(course.courseOfferingId, deadlineDate)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Assessment Title
            Text(
                text = "Assessment Title / Topic",
                fontSize = 13.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("e.g. CT-2 on Chapter 3 & 4, Lab Report 03", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = assessmentTextFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Deadline Date Picker
            Text(
                text = "Deadline Date",
                fontSize = 13.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                onClick = {
                    val initialCalendar = Calendar.getInstance()
                    try {
                        val parsed = LocalDate.parse(deadlineDate)
                        initialCalendar.set(parsed.year, parsed.monthValue - 1, parsed.dayOfMonth)
                    } catch (_: Exception) {}

                    val dialogTheme = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                    DatePickerDialog(
                        context,
                        dialogTheme,
                        { _, y, m, d ->
                            val chosen = LocalDate.of(y, m + 1, d).toString()
                            deadlineDate = chosen
                            selectedCourse?.let { triggerRoutineAutofill(it.courseOfferingId, chosen) }
                        },
                        initialCalendar.get(Calendar.YEAR),
                        initialCalendar.get(Calendar.MONTH),
                        initialCalendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                },
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) Color(0xFF131A2A) else Color(0xFFF8FAFC),
                border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x22000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF818CF8) else Color(0xFF3B82F6),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (deadlineDate.isNotBlank()) deadlineDate else "Select date",
                        fontSize = 13.5.sp,
                        fontFamily = PlusJakartaBold,
                        color = if (deadlineDate.isNotBlank()) {
                            AssessmentColors.primaryTextColor(isDark)
                        } else {
                            if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Time & Room Number (Editable Fallbacks)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Time
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Time",
                        fontSize = 13.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = deadlineTime,
                        onValueChange = { deadlineTime = it },
                        placeholder = { Text("e.g. 10:00 AM", fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = {
                            IconButton(
                                onClick = { showTimePicker() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_clock),
                                    contentDescription = "Pick time",
                                    tint = if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = assessmentTextFieldColors
                    )
                }

                // Room
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Room / Venue",
                        fontSize = 13.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = room,
                        onValueChange = { room = it },
                        placeholder = { Text("e.g. 338 or Online", fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF38BDF8) else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = assessmentTextFieldColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. Syllabus / Guidelines Text
            Text(
                text = "Syllabus Topics & Instructions",
                fontSize = 13.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = syllabus,
                onValueChange = { syllabus = it },
                placeholder = { Text("Enter topics, chapters, materials allowed, submission format...", fontSize = 12.5.sp) },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                colors = assessmentTextFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Checkbox: Also post to notice feed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alsoPostNotice = !alsoPostNotice }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = alsoPostNotice,
                    onCheckedChange = { alsoPostNotice = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF6366F1),
                        uncheckedColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Also post to notice feed",
                        fontSize = 13.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Text(
                        text = "Broadcasts notice card with 'View in Upcoming' button",
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444),
                    fontFamily = PlusJakartaBold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 8. Submit Button
            Button(
                onClick = {
                    if (title.isBlank()) {
                        errorMessage = "Please enter an assessment title."
                        return@Button
                    }
                    if (selectedCourse == null) {
                        errorMessage = "Please select a course."
                        return@Button
                    }
                    if (deadlineDate.isBlank()) {
                        errorMessage = "Please select a deadline date."
                        return@Button
                    }
                    errorMessage = null
                    isSubmitting = true

                    val validId = initialAssessment?.id?.ifBlank { java.util.UUID.randomUUID().toString() }
                        ?: java.util.UUID.randomUUID().toString()

                    val itemToSave = AssessmentItem(
                        id = validId,
                        batchId = initialAssessment?.batchId?.ifBlank { currentBatchId } ?: currentBatchId,
                        courseId = selectedCourse!!.courseOfferingId,
                        courseCode = selectedCourse!!.courseCode,
                        courseTitle = selectedCourse!!.courseTitle,
                        type = selectedType.code,
                        title = title.trim(),
                        deadlineDate = deadlineDate.trim(),
                        deadlineTime = deadlineTime.trim(),
                        room = room.trim(),
                        syllabus = syllabus.trim(),
                        noticeId = initialAssessment?.noticeId.orEmpty(),
                        postedBy = initialAssessment?.postedBy?.ifBlank { "CR / Admin" } ?: "CR / Admin",
                        createdAt = initialAssessment?.createdAt ?: System.currentTimeMillis(),
                        isMock = false
                    )

                    scope.launch {
                        try {
                            onSubmit(itemToSave, alsoPostNotice)
                            onDismiss()
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Failed to save assessment"
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6366F1)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (initialAssessment == null) "Post Assessment & Add to Upcoming" else "Save Changes",
                    fontFamily = PlusJakartaBold,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
