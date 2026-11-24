package com.shuaib.classmate.ui.notice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.ui.assessment.AssessmentColors
import com.shuaib.classmate.ui.assessment.PlusJakartaBold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostNoticeScreen(
    batchLabel: String,
    semesterLabel: String,
    isTeacher: Boolean,
    courses: List<NoticeCourseChoice>,
    recentNotices: List<RecentNoticeItem> = emptyList(),
    messageText: String,
    onMessageChange: (String) -> Unit,
    attachments: List<NoticeAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onAddPhotosClick: () -> Unit,
    onAddPdfClick: () -> Unit,
    voiceState: VoiceRecordingState,
    voiceTimerText: String,
    onVoiceClick: () -> Unit,
    manualTitle: String = "",
    onManualTitleChange: (String) -> Unit = {},
    showManualTitleBox: Boolean = false,
    onToggleManualTitleBox: (Boolean) -> Unit = {},
    onSubmitGeneralNotice: (title: String, body: String, courseOfferingId: String?) -> Unit,
    onSubmitCancellation: (courseOfferingId: String, dateOption: String) -> Unit,
    onSubmitPoll: (question: String, options: List<String>, isMulti: Boolean, isAnon: Boolean, durationHours: Any) -> Unit,
    onClose: () -> Unit,
    isSubmitting: Boolean,
    submissionStepText: String,
    showAiProgressDialog: Boolean,
    onRunInBackground: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    var noticeType by remember { mutableStateOf(NoticePostingType.GENERAL) }
    var previewNotice by remember { mutableStateOf<RecentNoticeItem?>(null) }

    // General Notice State
    var selectedTeacherCourse by remember { mutableStateOf(courses.firstOrNull()) }

    // Cancellation State
    var selectedCancellationCourse by remember { mutableStateOf(courses.firstOrNull()) }
    var cancellationDateOption by remember { mutableStateOf("Today") }

    // Poll State
    var pollQuestion by remember { mutableStateOf("") }
    val pollOptions = remember { mutableStateListOf("", "") }
    var pollIsMulti by remember { mutableStateOf(false) }
    var pollIsAnon by remember { mutableStateOf(false) }
    var pollDurationText by remember { mutableStateOf("3 days (default)") }

    val primaryColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)

    // Clean display of Semester badge vs Batch subtitle
    val extractedSemester = remember(semesterLabel, batchLabel) {
        if (semesterLabel.isNotBlank()) semesterLabel
        else if (batchLabel.contains("Semester", ignoreCase = true)) {
            val parts = batchLabel.split("·", "-").map { it.trim() }
            parts.firstOrNull { it.contains("Semester", ignoreCase = true) }.orEmpty()
        } else ""
    }

    val cleanBatchSubtitle = remember(batchLabel, extractedSemester) {
        if (extractedSemester.isNotBlank() && batchLabel.contains(extractedSemester)) {
            batchLabel.replace(extractedSemester, "").trim().trim('·', '-', ' ', '•')
        } else {
            batchLabel
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AssessmentColors.screenBackgroundColor(isDark))
    ) {
        // Pinned Header
        Surface(
            color = AssessmentColors.screenBackgroundColor(isDark),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Close",
                            tint = AssessmentColors.primaryTextColor(isDark),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Post notice",
                            fontSize = 20.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.ExtraBold,
                            color = AssessmentColors.primaryTextColor(isDark),
                            letterSpacing = (-0.3).sp
                        )
                        if (cleanBatchSubtitle.isNotBlank()) {
                            Text(
                                text = cleanBatchSubtitle,
                                fontSize = 12.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Normal,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (extractedSemester.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = primaryColor.copy(alpha = if (isDark) 0.18f else 0.10f)
                        ) {
                            Text(
                                text = extractedSemester,
                                fontSize = 11.5.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = if (isDark) Color(0x1FFFFFFF) else Color(0x0F000000)
                )
            }
        }

        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val navBottom = WindowInsets.navigationBars.getBottom(density)
        val imeBottomDp = with(density) { imeBottom.toDp() }
        val navBottomDp = with(density) { navBottom.toDp() }

        // When keyboard opens, dynamically pad by keyboard height + breathing space
        val composerBottomPadding = if (imeBottom > 0) imeBottomDp + 6.dp else navBottomDp + 6.dp
        val formBottomPadding = if (imeBottom > 0) imeBottomDp + 16.dp else navBottomDp + 16.dp

        // Body with Scroll
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = 10.dp,
                    bottom = 10.dp + if (noticeType != NoticePostingType.GENERAL) formBottomPadding else 0.dp
                )
        ) {
            // Notice Type Selector (Upper Three-Option System)
            Text(
                text = "Notice type",
                fontSize = 12.5.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                modifier = Modifier.padding(bottom = 4.dp)
            )

            NoticePostingType.values().forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { noticeType = type }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = noticeType == type,
                        onClick = { noticeType = type },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = primaryColor,
                            unselectedColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = type.label,
                        fontSize = 14.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = if (noticeType == type) FontWeight.Bold else FontWeight.Medium,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dynamic Content Based on Notice Type
            when (noticeType) {
                NoticePostingType.GENERAL -> {
                    // Teacher course selector if teacher
                    if (isTeacher && courses.isNotEmpty()) {
                        CourseSelectorDropdown(
                            courses = courses,
                            selectedCourse = selectedTeacherCourse,
                            onCourseSelected = { selectedTeacherCourse = it },
                            label = "Target course",
                            isDark = isDark
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Fallback / Manual Title Box (shown only if AI title detection fails or requested)
                    if (showManualTitleBox) {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.5f) else Color(0xFFEEF2FF)
                            ),
                            border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_sparkle),
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Notice Title",
                                        fontSize = 12.5.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = manualTitle,
                                    onValueChange = onManualTitleChange,
                                    placeholder = {
                                        Text(
                                            "Enter notice title...",
                                            fontSize = 14.sp,
                                            fontFamily = PlusJakartaBold
                                        )
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = primaryColor,
                                        unfocusedBorderColor = if (isDark) Color(0x30FFFFFF) else Color(0x20000000),
                                        focusedTextColor = AssessmentColors.primaryTextColor(isDark),
                                        unfocusedTextColor = AssessmentColors.primaryTextColor(isDark)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Recent In Batch Section (replaces bulleted Smart Notice text card)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = "RECENT IN BATCH",
                            fontSize = 11.5.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (recentNotices.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = primaryColor.copy(alpha = if (isDark) 0.2f else 0.1f)
                            ) {
                                Text(
                                    text = "Last ${recentNotices.size}",
                                    fontSize = 10.5.sp,
                                    fontFamily = PlusJakartaBold,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (recentNotices.isNotEmpty()) {
                        recentNotices.take(3).forEach { notice ->
                            RecentNoticePeekCard(
                                notice = notice,
                                isDark = isDark,
                                primaryColor = primaryColor,
                                onClick = { previewNotice = notice }
                            )
                        }
                    } else {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF1E2025) else Color(0xFFF8FAFC)
                            ),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2E323B) else Color(0xFFE5E7EB)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_notice),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "No previous notices published yet",
                                    fontSize = 13.sp,
                                    fontFamily = PlusJakartaBold,
                                    color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }

                NoticePostingType.CANCELLATION -> {
                    Text(
                        text = "Class Cancellation Notice",
                        fontSize = 15.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    CourseSelectorDropdown(
                        courses = courses,
                        selectedCourse = selectedCancellationCourse,
                        onCourseSelected = { selectedCancellationCourse = it },
                        label = "Select cancelled course",
                        isDark = isDark
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DropdownSelector(
                        label = "When is the class cancelled?",
                        options = listOf("Today", "Tomorrow"),
                        selected = cancellationDateOption,
                        onSelect = { cancellationDateOption = it },
                        isDark = isDark
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            selectedCancellationCourse?.let {
                                onSubmitCancellation(it.offeringId, cancellationDateOption)
                            }
                        },
                        enabled = !isSubmitting && selectedCancellationCourse != null,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Post cancellation", fontFamily = PlusJakartaBold, fontSize = 14.5.sp, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                NoticePostingType.POLL -> {
                    Text(
                        text = "Interactive Poll Details",
                        fontSize = 15.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pollQuestion,
                        onValueChange = { pollQuestion = it },
                        label = { Text("Poll Question", fontFamily = PlusJakartaBold) },
                        placeholder = { Text("What would you like to ask the batch?") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = if (isDark) Color(0x30FFFFFF) else Color(0x20000000),
                            focusedTextColor = AssessmentColors.primaryTextColor(isDark),
                            unfocusedTextColor = AssessmentColors.primaryTextColor(isDark)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    pollOptions.forEachIndexed { index, optText ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = optText,
                                onValueChange = { pollOptions[index] = it },
                                label = { Text("Option ${index + 1}", fontFamily = PlusJakartaBold) },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = primaryColor,
                                    unfocusedBorderColor = if (isDark) Color(0x30FFFFFF) else Color(0x20000000),
                                    focusedTextColor = AssessmentColors.primaryTextColor(isDark),
                                    unfocusedTextColor = AssessmentColors.primaryTextColor(isDark)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (pollOptions.size > 2) {
                                IconButton(onClick = { pollOptions.removeAt(index) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Remove Option",
                                        tint = Color(0xFFEF4444)
                                    )
                                }
                            }
                        }
                    }

                    if (pollOptions.size < 10) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { pollOptions.add("") }) {
                            Text("+ Add Option", fontFamily = PlusJakartaBold, color = primaryColor)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Checkboxes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pollIsMulti = !pollIsMulti }
                    ) {
                        Checkbox(
                            checked = pollIsMulti,
                            onCheckedChange = { pollIsMulti = it },
                            colors = CheckboxDefaults.colors(checkedColor = primaryColor)
                        )
                        Text("Allow multiple answers", fontSize = 13.5.sp, fontFamily = PlusJakartaBold, color = AssessmentColors.primaryTextColor(isDark))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pollIsAnon = !pollIsAnon }
                    ) {
                        Checkbox(
                            checked = pollIsAnon,
                            onCheckedChange = { pollIsAnon = it },
                            colors = CheckboxDefaults.colors(checkedColor = primaryColor)
                        )
                        Text("Anonymous voting (hide voter names)", fontSize = 13.5.sp, fontFamily = PlusJakartaBold, color = AssessmentColors.primaryTextColor(isDark))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    DropdownSelector(
                        label = "Poll Duration",
                        options = listOf("1 hour", "6 hours", "12 hours", "24 hours", "2 days", "3 days (default)", "7 days", "No expiration"),
                        selected = pollDurationText,
                        onSelect = { pollDurationText = it },
                        isDark = isDark
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val durationHours: Any = when (pollDurationText) {
                                "1 hour" -> 1
                                "6 hours" -> 6
                                "12 hours" -> 12
                                "24 hours" -> 24
                                "2 days" -> 48
                                "7 days" -> 168
                                "No expiration" -> org.json.JSONObject.NULL
                                else -> 72
                            }
                            onSubmitPoll(
                                pollQuestion.trim(),
                                pollOptions.map { it.trim() }.filter { it.isNotBlank() },
                                pollIsMulti,
                                pollIsAnon,
                                durationHours
                            )
                        },
                        enabled = !isSubmitting && pollQuestion.isNotBlank() && pollOptions.count { it.isNotBlank() } >= 2,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Publish poll", fontFamily = PlusJakartaBold, fontSize = 14.5.sp, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // WhatsApp-Style Composer Bar (Shown in GENERAL Notice mode, docked above keyboard)
        if (noticeType == NoticePostingType.GENERAL) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = composerBottomPadding)
            ) {
                // Attachments preview strip (PDF pill or photo carousel) directly above composer
                if (attachments.isNotEmpty()) {
                    NoticeAttachmentPreviews(
                        attachments = attachments,
                        onRemoveAttachment = onRemoveAttachment,
                        onAddMorePhotos = onAddPhotosClick,
                        isDark = isDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }

                NoticeComposerBar(
                    messageText = messageText,
                    onMessageChange = onMessageChange,
                    voiceState = voiceState,
                    voiceTimerText = voiceTimerText,
                    onAttachDoc = onAddPdfClick,
                    onAttachPhoto = onAddPhotosClick,
                    onVoiceClick = onVoiceClick,
                    onSendClick = {
                        if (messageText.isNotBlank() || attachments.isNotEmpty()) {
                            onSubmitGeneralNotice(
                                manualTitle.trim(),
                                messageText.trim(),
                                if (isTeacher) selectedTeacherCourse?.offeringId else null
                            )
                        }
                    },
                    hasAttachments = attachments.isNotEmpty(),
                    isSubmitting = isSubmitting,
                    isDark = isDark
                )
            }
        }
    }

    // AI Title Progress & Publishing HUD Dialog
    if (showAiProgressDialog) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = onRunInBackground) {
                    Text("Run in background", fontFamily = PlusJakartaBold, color = primaryColor)
                }
            },
            title = {
                Column {
                    Text(
                        text = "✨ CLASSMATE AI ENGINE",
                        fontSize = 11.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Publishing Notice",
                        fontSize = 17.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = AssessmentColors.primaryTextColor(isDark)
                    )
                }
            },
            text = {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = submissionStepText.ifBlank { "Detecting title & formatting notice with ClassMate AI…" },
                        fontSize = 13.sp,
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                    )
                }
            },
            containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Recent Notice Full Details Preview Dialog
    if (previewNotice != null) {
        val n = previewNotice!!
        AlertDialog(
            onDismissRequest = { previewNotice = null },
            title = {
                Column {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDark) Color(0xFF252A36) else Color(0xFFEEF2F6),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = n.category,
                            fontSize = 10.5.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = n.title,
                        fontSize = 16.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                    )
                    Text(
                        text = "${n.authorName} • ${n.timeAgo}",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (n.body.isNotBlank()) {
                        Text(
                            text = n.body,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
                        )
                    } else {
                        Text(
                            text = "(No body text)",
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewNotice = null }) {
                    Text("Close", fontFamily = PlusJakartaBold, color = primaryColor)
                }
            },
            containerColor = if (isDark) Color(0xFF1E2025) else Color.White,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectorDropdown(
    courses: List<NoticeCourseChoice>,
    selectedCourse: NoticeCourseChoice?,
    onCourseSelected: (NoticeCourseChoice) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedCourse?.displayLabel ?: "No course selected",
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontFamily = PlusJakartaBold) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                unfocusedBorderColor = if (isDark) Color(0x30FFFFFF) else Color(0x20000000),
                focusedTextColor = AssessmentColors.primaryTextColor(isDark),
                unfocusedTextColor = AssessmentColors.primaryTextColor(isDark)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (isDark) Color(0xFF141D30) else Color(0xFFFFFFFF)
        ) {
            courses.forEach { course ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = course.displayLabel,
                            fontSize = 13.5.sp,
                            fontFamily = PlusJakartaBold,
                            color = AssessmentColors.primaryTextColor(isDark)
                        )
                    },
                    onClick = {
                        onCourseSelected(course)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontFamily = PlusJakartaBold) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                unfocusedBorderColor = if (isDark) Color(0x30FFFFFF) else Color(0x20000000),
                focusedTextColor = AssessmentColors.primaryTextColor(isDark),
                unfocusedTextColor = AssessmentColors.primaryTextColor(isDark)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (isDark) Color(0xFF141D30) else Color(0xFFFFFFFF)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = opt,
                            fontSize = 13.5.sp,
                            fontFamily = PlusJakartaBold,
                            color = AssessmentColors.primaryTextColor(isDark)
                        )
                    },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun RecentNoticePeekCard(
    notice: RecentNoticeItem,
    isDark: Boolean,
    primaryColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF1E2025) else Color(0xFFF8FAFC)
        ),
        border = BorderStroke(1.dp, if (isDark) Color(0xFF2E323B) else Color(0xFFE5E7EB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Row: Category Chip & Timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xFF252A36) else Color(0xFFEEF2F6)
                ) {
                    Text(
                        text = notice.category,
                        fontSize = 10.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (notice.timeAgo.isNotBlank()) {
                    Text(
                        text = notice.timeAgo,
                        fontSize = 11.sp,
                        fontFamily = PlusJakartaBold,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            }

            // Notice Title
            Text(
                text = notice.title,
                fontSize = 14.5.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                lineHeight = 19.sp
            )

            // Notice Body Snippet (if any)
            if (notice.body.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notice.body,
                    fontSize = 12.5.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.Normal,
                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
            }

            // Bottom Footer Row: Author & Action Arrow
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (notice.authorName.isNotBlank()) {
                    Text(
                        text = notice.authorName,
                        fontSize = 11.5.sp,
                        fontFamily = PlusJakartaBold,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (notice.hasAttachment) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isDark) Color(0xFF312E81) else Color(0xFFE0E7FF),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "📎 File",
                            fontSize = 10.5.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFC7D2FE) else Color(0xFF4338CA),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

