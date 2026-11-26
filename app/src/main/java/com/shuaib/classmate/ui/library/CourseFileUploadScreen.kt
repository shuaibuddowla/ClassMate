package com.shuaib.classmate.ui.library

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R

data class CourseFileOption(
    val offeringId: String,
    val code: String,
    val title: String
)

data class CourseFileDraft(
    val uri: Uri,
    val originalFileName: String,
    val title: String,
    val sizeBytes: Long,
    val mimeType: String
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.toInt().coerceAtLeast(1)} KB"
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseFileUploadScreen(
    batchLabel: String,
    semesterLabel: String,
    isEditing: Boolean,
    isEditingLink: Boolean,
    courses: List<CourseFileOption>,
    selectedCourseId: String?,
    onSelectCourse: (String) -> Unit,
    uploadMode: String, // "file" or "link"
    onModeChange: (String) -> Unit,
    selectedFiles: List<CourseFileDraft>,
    onPickFiles: () -> Unit,
    onUpdateFileTitle: (Int, String) -> Unit,
    onRemoveFile: (Int) -> Unit,
    linkTitle: String,
    onLinkTitleChange: (String) -> Unit,
    linkUrl: String,
    onLinkUrlChange: (String) -> Unit,
    statusMessage: String?,
    isBusy: Boolean,
    onSubmit: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bg = if (isDark) Color(0xFF090C14) else Color(0xFFF6F8FC)
    val surface = if (isDark) Color(0xFF121826) else Color.White
    val surface2 = if (isDark) Color(0xFF1A2234) else Color(0xFFF1F5F9)
    val border = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)
    val textPrimary = if (isDark) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val accent = Color(0xFF6366F1)

    val isLink = uploadMode == "link"
    val sortedCourses = remember(courses) {
        courses.sortedBy { it.code.trim().uppercase() }
    }
    val selectedCourse = sortedCourses.firstOrNull { it.offeringId == selectedCourseId } ?: sortedCourses.firstOrNull()

    var titleError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = bg,
        topBar = {
            Surface(
                color = surface,
                shadowElevation = 1.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isEditing && isEditingLink -> "Edit Drive Link"
                                    isEditing -> "Edit Course File"
                                    else -> "Course File"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = textPrimary
                            )
                            if (batchLabel.isNotBlank()) {
                                Text(
                                    text = batchLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (semesterLabel.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = accent.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = semesterLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = border)
                }
            }
        },
        bottomBar = {
            Surface(
                color = surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!statusMessage.isNullOrBlank()) {
                        Text(
                            text = statusMessage,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (statusMessage.contains("fail", true) || statusMessage.contains("error", true) || statusMessage.contains("limit", true)) {
                                Color(0xFFF43F5E)
                            } else {
                                accent
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val ctaLabel = when {
                        isEditing -> "Save Changes"
                        isLink -> "Share Drive Link"
                        selectedFiles.size > 1 -> "Upload ${selectedFiles.size} Files"
                        selectedFiles.size == 1 -> "Upload 1 File"
                        else -> "Upload Files"
                    }

                    Button(
                        onClick = {
                            titleError = null
                            urlError = null
                            if (isLink) {
                                if (linkTitle.trim().isBlank()) {
                                    titleError = "Resource title is required"
                                    return@Button
                                }
                                val raw = linkUrl.trim()
                                if (raw.isBlank()) {
                                    urlError = "Google Drive link is required"
                                    return@Button
                                }
                                val withScheme = if (raw.matches(Regex("(?i)^https?://.*"))) raw else "https://$raw"
                                val validHost = runCatching {
                                    val u = java.net.URI(withScheme)
                                    !u.host.isNullOrBlank() && u.host.contains('.')
                                }.getOrDefault(false)
                                if (!validHost) {
                                    urlError = "Enter a valid link URL (e.g. https://drive.google.com/...)"
                                    return@Button
                                }
                            } else if (isEditing) {
                                if (linkTitle.trim().isBlank()) {
                                    titleError = "Display title is required"
                                    return@Button
                                }
                            }
                            onSubmit()
                        },
                        enabled = !isBusy && courses.isNotEmpty(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            disabledContainerColor = accent.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = if (isBusy) "Working…" else ctaLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Course Selection Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = surface,
                    border = BorderStroke(1.dp, border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TARGET COURSE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary,
                                letterSpacing = 0.8.sp
                            )
                            if (selectedCourse != null) {
                                Text(
                                    text = selectedCourse.code,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                            }
                        }

                        if (sortedCourses.isEmpty()) {
                            Text(
                                text = "Loading active courses…",
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sortedCourses, key = { it.offeringId }) { course ->
                                    val sel = course.offeringId == selectedCourse?.offeringId
                                    Surface(
                                        onClick = { onSelectCourse(course.offeringId) },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (sel) accent else surface2,
                                        border = BorderStroke(
                                            1.dp,
                                            if (sel) accent else border
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = course.code,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (sel) Color.White else textPrimary
                                            )
                                            if (course.title.isNotBlank()) {
                                                Text(
                                                    text = course.title,
                                                    fontSize = 10.sp,
                                                    color = if (sel) Color.White.copy(alpha = 0.85f) else textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 150.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Mode Switcher (Upload Document | Google Drive Link)
            if (!isEditing) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = surface,
                        border = BorderStroke(1.dp, border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("file" to "Upload Document", "link" to "Google Drive Link").forEach { (key, label) ->
                                val active = uploadMode == key
                                Surface(
                                    onClick = { onModeChange(key) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (active) accent else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) Color.White else textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Content Section
            if (isLink || isEditing) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = surface,
                        border = BorderStroke(1.dp, border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (isLink) "Resource Title *" else "Display Title *",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textSecondary
                                )
                                OutlinedTextField(
                                    value = linkTitle,
                                    onValueChange = {
                                        titleError = null
                                        onLinkTitleChange(it)
                                    },
                                    placeholder = { Text("e.g. Lecture 05 - Automata Slides", fontSize = 13.sp) },
                                    isError = titleError != null,
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (titleError != null) {
                                    Text(titleError!!, fontSize = 11.sp, color = Color(0xFFF43F5E))
                                }
                            }

                            if (isLink) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Google Drive Link *",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textSecondary
                                        )
                                        Surface(
                                            onClick = {
                                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                val clip = cb?.primaryClip
                                                if (cb?.hasPrimaryClip() == true && cb.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                                                    val txt = clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                                    if (txt.isNotBlank()) {
                                                        urlError = null
                                                        onLinkUrlChange(txt)
                                                    }
                                                }
                                            },
                                            shape = CircleShape,
                                            color = accent.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "Paste",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = accent,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = linkUrl,
                                        onValueChange = {
                                            urlError = null
                                            onLinkUrlChange(it)
                                        },
                                        placeholder = { Text("https://drive.google.com/...", fontSize = 13.sp) },
                                        isError = urlError != null,
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (urlError != null) {
                                        Text(urlError!!, fontSize = 11.sp, color = Color(0xFFF43F5E))
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = accent.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Sharing a Drive Link automatically posts a resource notice to your batch feed so classmates can open it in one tap.",
                                        fontSize = 11.5.sp,
                                        color = textSecondary,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Upload Document Mode: Picker Dropzone + Selected Files
                item {
                    Surface(
                        onClick = onPickFiles,
                        shape = RoundedCornerShape(22.dp),
                        color = surface,
                        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            accent.copy(alpha = 0.08f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .padding(vertical = 26.dp, horizontal = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = "Select Files",
                                    tint = accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = if (selectedFiles.isEmpty()) "Tap to select files" else "Add more files (${selectedFiles.size}/15)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                            Text(
                                text = "PDF, Slides, Docs, or Images • Max 50 MB per file",
                                fontSize = 12.sp,
                                color = textSecondary
                            )
                        }
                    }
                }

                if (selectedFiles.isNotEmpty()) {
                    item {
                        val totalBytes = selectedFiles.sumOf { it.sizeBytes }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SELECTED FILES (${selectedFiles.size})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = formatBytes(totalBytes),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                    }

                    itemsIndexed(selectedFiles) { idx, item ->
                        val ext = item.originalFileName.substringAfterLast('.', "").uppercase().take(4).ifBlank { "FILE" }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = surface,
                            border = BorderStroke(1.dp, border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(accent.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = ext,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = accent
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.originalFileName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatBytes(item.sizeBytes),
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveFile(idx) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = "Remove",
                                            tint = textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = item.title,
                                    onValueChange = { onUpdateFileTitle(idx, it) },
                                    label = { Text("Display Title", fontSize = 11.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
