package com.shuaib.classmate.ui.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.models.AssessmentItem
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentDetailBottomSheet(
    assessment: AssessmentItem?,
    onDismiss: () -> Unit,
    canManage: Boolean,
    onEdit: (AssessmentItem) -> Unit,
    onDelete: (AssessmentItem) -> Unit,
    courseFiles: List<JSONObject> = emptyList(),
    onOpenFile: (JSONObject) -> Unit = {},
    onOpenCourseFolder: ((courseOfferingId: String, courseTitle: String) -> Unit)? = null,
    onBrowseUpcoming: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    if (assessment == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val type = assessment.assessmentType
    val typeColor = Color(type.colorHex)
    val urgency = assessment.getUrgencyLevel()
    val pillStyle = AssessmentColors.getCountdownPillStyle(urgency, isDark)

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
            // Header: Type Badge + Countdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = typeColor.copy(alpha = if (isDark) 0.22f else 0.14f),
                    border = BorderStroke(1.dp, typeColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            painter = painterResource(type.iconRes),
                            contentDescription = null,
                            tint = typeColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = type.shortName,
                            fontSize = 12.5.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = typeColor
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = pillStyle.containerColor,
                    border = BorderStroke(1.dp, pillStyle.borderColor)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = pillStyle.iconTint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = assessment.getFormattedCountdown(),
                            fontSize = 12.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = pillStyle.textColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Course Info
            Text(
                text = "${assessment.courseCode.ifBlank { "Course" }} · ${assessment.courseTitle}",
                fontSize = 13.5.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Assessment Title
            Text(
                text = assessment.title,
                fontSize = 21.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark),
                lineHeight = 28.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Info Grid: Deadline & Room/Location
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFF8FAFC)
                ),
                border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x14000000))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Date & Time (Schedule)
                    val isAssignment = assessment.assessmentType == com.shuaib.classmate.models.AssessmentType.ASSIGNMENT
                    val scheduleLabel = if (isAssignment) "Deadline & Time" else "Date & Time"

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF3B82F6).copy(alpha = if (isDark) 0.22f else 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_calendar),
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = scheduleLabel,
                                fontSize = 11.5.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                fontFamily = PlusJakartaBold
                            )
                            Text(
                                text = assessment.getFormattedRelativeDateTime(),
                                fontSize = 14.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = AssessmentColors.primaryTextColor(isDark)
                            )
                        }
                    }

                    val formattedRoom = assessment.getFormattedRoom()
                    if (formattedRoom.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = if (isDark) Color(0x18FFFFFF) else Color(0x10000000),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Venue
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF10B981).copy(alpha = if (isDark) 0.22f else 0.12f),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_location),
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Venue",
                                    fontSize = 11.5.sp,
                                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    fontFamily = PlusJakartaBold
                                )
                                Text(
                                    text = formattedRoom,
                                    fontSize = 14.sp,
                                    fontFamily = PlusJakartaBold,
                                    fontWeight = FontWeight.Bold,
                                    color = AssessmentColors.primaryTextColor(isDark)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Syllabus & Guidelines Section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_file_syllabus),
                    contentDescription = null,
                    tint = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Syllabus & Guidelines",
                    fontSize = 15.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.Bold,
                    color = AssessmentColors.primaryTextColor(isDark)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isDark) Color(0xFF161F30) else Color(0xFFF1F5F9),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = assessment.syllabus.ifBlank { "No specific syllabus or guidelines entered by the CR yet." },
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }

            // Course Resources & Files Button
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                onClick = {
                    onOpenCourseFolder?.invoke(assessment.courseId, assessment.courseTitle)
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                color = if (isDark) Color(0xFF131A2A) else Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x14000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF3B82F6).copy(alpha = if (isDark) 0.22f else 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_folder_outline),
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Course Resources & Files",
                                fontSize = 13.5.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = AssessmentColors.primaryTextColor(isDark)
                            )
                            Text(
                                text = if (courseFiles.isNotEmpty()) {
                                    "${courseFiles.size} material${if (courseFiles.size > 1) "s" else ""} • View in Courses tab"
                                } else {
                                    "Open course files & slides"
                                },
                                fontSize = 11.5.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_library_see_all_arrow),
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Admin / CR Management Actions
            if (canManage) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(
                    color = if (isDark) Color(0x22FFFFFF) else Color(0x14000000),
                    thickness = 1.dp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onEdit(assessment)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x22000000))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Edit",
                            fontFamily = PlusJakartaBold,
                            maxLines = 1,
                            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)
                        )
                    }

                    Button(
                        onClick = { showDeleteConfirmation = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                            contentColor = Color(0xFFEF4444)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Delete",
                            fontFamily = PlusJakartaBold,
                            maxLines = 1,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }

            onBrowseUpcoming?.let { browseAction ->
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        browseAction()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, Color(0xFF6366F1).copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isDark) Color(0x1A6366F1) else Color(0x0F6366F1),
                        contentColor = Color(0xFF6366F1)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_upcoming_outline),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF6366F1)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Browse Upcoming Assessments",
                        fontFamily = PlusJakartaBold,
                        fontSize = 13.5.sp,
                        color = Color(0xFF6366F1)
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF),
            titleContentColor = AssessmentColors.primaryTextColor(isDark),
            textContentColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Delete Assessment?",
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Are you sure you want to delete '${assessment.title}'? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDismiss()
                        onDelete(assessment)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Delete", fontFamily = PlusJakartaBold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", fontFamily = PlusJakartaBold)
                }
            }
        )
    }
}
