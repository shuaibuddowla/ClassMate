package com.shuaib.classmate.ui.assessment

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.repositories.AssessmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingAssessmentsScreen(
    repository: AssessmentRepository,
    currentBatchId: String = "",
    canManage: Boolean,
    availableCourses: List<CourseChoice>,
    routineLookup: (courseOfferingId: String, dayOfWeek: Int) -> Pair<String, String>?,
    onPostNotice: (title: String, body: String, courseId: String?, onNoticeCreated: ((String) -> Unit)?) -> Unit,
    onBroadcastPush: (title: String, message: String) -> Unit,
    courseFilesLookup: (courseId: String) -> List<JSONObject>,
    onOpenFile: (JSONObject) -> Unit,
    onOpenCourseFolder: ((courseOfferingId: String, courseTitle: String) -> Unit)? = null,
    onOpenHamburger: () -> Unit = {},
    onScrollChange: (Boolean) -> Unit = {},
    onIsAtTopChanged: (Boolean) -> Unit = {},
    triggerOpenPostSheet: Boolean = false,
    onPostSheetOpened: () -> Unit = {},
    targetAssessmentId: String? = null,
    onClearTargetAssessment: () -> Unit = {},
    onOpenPostAssessmentDialog: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val allAssessments by repository.assessmentsFlow.collectAsState()
    val scope = rememberCoroutineScope()

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
        }
    }

    val listState = rememberLazyListState()
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 15
        }
    }

    LaunchedEffect(isAtTop) {
        onIsAtTopChanged(isAtTop)
    }

    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (currentIndex, currentOffset) ->
                if (currentIndex != previousIndex || currentOffset != previousOffset) {
                    onScrollChange(true)
                }
                previousIndex = currentIndex
                previousOffset = currentOffset
            }
    }

    var selectedFilter by remember { mutableStateOf<AssessmentFilter>(AssessmentFilter.All) }
    var isHistoryOpen by remember { mutableStateOf(false) }

    // Intercept system back button when in History view, returning to Upcoming
    BackHandler(enabled = isHistoryOpen) {
        isHistoryOpen = false
        selectedFilter = AssessmentFilter.All
    }

    var selectedDetailAssessment by remember { mutableStateOf<AssessmentItem?>(null) }
    var editingAssessment by remember { mutableStateOf<AssessmentItem?>(null) }
    var showPostSheet by remember { mutableStateOf(false) }

    // External trigger to open post assessment (e.g. from Speed Dial FAB)
    LaunchedEffect(triggerOpenPostSheet) {
        if (triggerOpenPostSheet) {
            showPostSheet = true
            onPostSheetOpened()
        }
    }

    // Direct deep-link handling if targetAssessmentId is provided
    LaunchedEffect(targetAssessmentId, allAssessments) {
        if (!targetAssessmentId.isNullOrBlank()) {
            val matched = allAssessments.firstOrNull { it.id.equals(targetAssessmentId, ignoreCase = true) }
            if (matched != null) {
                selectedDetailAssessment = matched
                onClearTargetAssessment()
            }
        }
    }

    // Split upcoming vs past
    val upcomingItems = remember(allAssessments) {
        allAssessments.filterNot { it.isPast() }
    }
    val pastItems = remember(allAssessments) {
        allAssessments.filter { it.isPast() }.reversed()
    }

    // Next up hero assessment (the closest upcoming one)
    val heroAssessment = remember(upcomingItems) {
        upcomingItems.firstOrNull()
    }

    // Timeline items (excluding hero if showing upcoming 'All', or filtered)
    val displayList = remember(allAssessments, selectedFilter, isHistoryOpen) {
        val baseList = if (isHistoryOpen) pastItems else upcomingItems
        val filtered = when (selectedFilter) {
            is AssessmentFilter.All -> baseList
            else -> baseList.filter { it.type == selectedFilter.key }
        }
        // If viewing upcoming and "All", hero card already displays the first item
        if (!isHistoryOpen && selectedFilter == AssessmentFilter.All && filtered.isNotEmpty()) {
            filtered.drop(1)
        } else {
            filtered
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AssessmentColors.screenBackgroundColor(isDark))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Pinned App Brand Header with solid background and scroll-aware hairline
            val showHeaderDivider by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8
                }
            }

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
                            .padding(start = 12.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isHistoryOpen) {
                                // Back Navigation Button (38dp x 38dp) to return to Upcoming
                                IconButton(
                                    onClick = {
                                        isHistoryOpen = false
                                        selectedFilter = AssessmentFilter.All
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "Back to Assessments",
                                        tint = AssessmentColors.primaryTextColor(isDark),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                // Hamburger Menu Button (38dp x 38dp)
                                IconButton(
                                    onClick = onOpenHamburger,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_hamburger_menu),
                                        contentDescription = "Menu",
                                        tint = AssessmentColors.primaryTextColor(isDark),
                                        modifier = Modifier.size(23.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = if (isHistoryOpen) "Assessment History" else "Assessments",
                                fontSize = 24.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.ExtraBold,
                                color = AssessmentColors.primaryTextColor(isDark),
                                letterSpacing = (-0.4).sp,
                                maxLines = 1
                            )
                        }

                        if (!isHistoryOpen) {
                            // Dedicated History Button in top right corner (only visible on Upcoming page)
                            Surface(
                                onClick = {
                                    isHistoryOpen = true
                                    selectedFilter = AssessmentFilter.All
                                },
                                shape = RoundedCornerShape(50),
                                color = if (isDark) Color(0xFF141D30).copy(alpha = 0.9f) else Color(0xFFF1F5F9).copy(alpha = 0.95f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isDark) Color(0x30FFFFFF) else Color(0x1F000000)
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_clock),
                                        contentDescription = null,
                                        tint = if (isDark) Color(0xFFA5B4FC) else Color(0xFF64748B),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "History",
                                        fontSize = 12.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF475569)
                                    )
                                }
                            }
                        } else {
                            // Subtle Record Count badge on History page
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isDark) Color(0xFF141D30).copy(alpha = 0.85f) else Color(0xFFF1F5F9).copy(alpha = 0.85f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isDark) Color(0x28FFFFFF) else Color(0x15000000)
                                )
                            ) {
                                Text(
                                    text = "${pastItems.size} Records",
                                    fontSize = 11.5.sp,
                                    fontFamily = PlusJakartaBold,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (showHeaderDivider) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = if (isDark) Color(0x28FFFFFF) else Color(0x14000000)
                        )
                    }
                }
            }

            // Body: Dedicated empty state when no upcoming items exist, or LazyColumn
            if (!isHistoryOpen && upcomingItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isDark) Color(0x28FFFFFF) else Color(0x14000000)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 4.dp else 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF10B981).copy(alpha = if (isDark) 0.18f else 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "All Caught Up!",
                                fontSize = 18.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = AssessmentColors.primaryTextColor(isDark)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "No pending tests, quizzes, or assignments right now. You're completely up to date!",
                                fontSize = 13.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )

                            if (pastItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(20.dp))
                                Surface(
                                    onClick = {
                                        isHistoryOpen = true
                                        selectedFilter = AssessmentFilter.All
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = if (isDark) Color(0xFF1E293B) else Color(0xFFEEF2FF),
                                    border = BorderStroke(1.dp, if (isDark) Color(0x30FFFFFF) else Color(0xFFC7D2FE))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_clock),
                                            contentDescription = null,
                                            tint = if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "View Assessment History (${pastItems.size})",
                                            fontSize = 12.sp,
                                            fontFamily = PlusJakartaBold,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF4338CA)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
                ) {
                    // Hero Section: Elevated Card (shown in Upcoming when 'All' is selected and hero item exists)
                    if (!isHistoryOpen && selectedFilter == AssessmentFilter.All && heroAssessment != null) {
                        item {
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                AssessmentHeroCard(
                                    assessment = heroAssessment,
                                    onClick = { selectedDetailAssessment = heroAssessment },
                                    isDark = isDark
                                )
                            }
                        }
                    }

                    // Filter Chips Row
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        AssessmentFilterRow(
                            selectedFilter = selectedFilter,
                            onFilterSelected = { selectedFilter = it },
                            showPastArchive = isHistoryOpen,
                            onTogglePastArchive = { isHistoryOpen = it },
                            isDark = isDark
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Section Subheader (Aligned flush with cards at 16.dp)
                    item {
                        val headerText = when {
                            isHistoryOpen -> "Past Records (${displayList.size})"
                            selectedFilter != AssessmentFilter.All -> "${selectedFilter.label} (${displayList.size})"
                            else -> "Coming Up (${displayList.size})"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = headerText,
                                fontSize = 14.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = AssessmentColors.primaryTextColor(isDark)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Timeline Items or Contextual Empty State
                    if (displayList.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp, horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isDark) Color(0xFF141D30) else Color(0xFFE2E8F0),
                                    border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x10000000)),
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_assignment),
                                            contentDescription = null,
                                            tint = if (isDark) Color(0xFFA5B4FC) else Color(0xFF64748B),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                val filterName = when (selectedFilter) {
                                    AssessmentFilter.All -> "assessments"
                                    AssessmentFilter.ClassTest -> "class tests"
                                    AssessmentFilter.Assignment -> "assignments"
                                    AssessmentFilter.Quiz -> "quizzes"
                                    AssessmentFilter.LabReport -> "lab reports"
                                    AssessmentFilter.Presentation -> "presentations"
                                }
                                val emptyTitle = if (isHistoryOpen) {
                                    if (selectedFilter == AssessmentFilter.All) "No past records found" else "No past $filterName found"
                                } else {
                                    "No upcoming $filterName"
                                }
                                val emptySubtitle = if (isHistoryOpen) {
                                    if (selectedFilter == AssessmentFilter.All) "Past exams and completed deadlines will be archived here." else "Past $filterName will be archived here."
                                } else {
                                    "No upcoming $filterName scheduled. Check back later."
                                }
                                Text(
                                    text = emptyTitle,
                                    fontSize = 14.5.sp,
                                    fontFamily = PlusJakartaBold,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AssessmentColors.primaryTextColor(isDark)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = emptySubtitle,
                                    fontSize = 12.5.sp,
                                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(displayList, key = { it.id }) { item ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                                AssessmentTimelineCard(
                                    assessment = item,
                                    onClick = { selectedDetailAssessment = item },
                                    isDark = isDark
                                )
                            }
                        }
                    }
                }
            }
        }

        // Detail Bottom Sheet
        if (selectedDetailAssessment != null) {
            val relevantFiles = remember(selectedDetailAssessment) {
                selectedDetailAssessment?.courseId?.let { courseFilesLookup(it) } ?: emptyList()
            }

            AssessmentDetailBottomSheet(
                assessment = selectedDetailAssessment,
                onDismiss = { selectedDetailAssessment = null },
                canManage = canManage,
                onEdit = { toEdit ->
                    editingAssessment = toEdit
                    selectedDetailAssessment = null
                    showPostSheet = true
                },
                onDelete = { toDelete ->
                    selectedDetailAssessment = null
                    scope.launch {
                        repository.deleteAssessment(toDelete.id, currentBatchId).onFailure { err ->
                            android.widget.Toast.makeText(
                                view.context,
                                com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(err),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                courseFiles = relevantFiles,
                onOpenFile = onOpenFile,
                onOpenCourseFolder = onOpenCourseFolder,
                isDark = isDark
            )
        }

        // Post / Edit Assessment Bottom Sheet
        if (showPostSheet) {
            PostAssessmentBottomSheet(
                initialAssessment = editingAssessment,
                currentBatchId = currentBatchId,
                availableCourses = availableCourses,
                routineLookup = routineLookup,
                onDismiss = {
                    showPostSheet = false
                    editingAssessment = null
                },
                onSubmit = { item, alsoPostNotice ->
                    // 1. Save to repository with batch isolation (instant UI update)
                    repository.saveAssessment(item, currentBatchId).getOrThrow()

                    // 2. Post notice in background if checked and update noticeId on assessment
                    //    (publish_batch_notice -> post_notice already triggers the Supabase FCM push)
                    if (alsoPostNotice) {
                        scope.launch(Dispatchers.IO) {
                            val validAssessmentId = item.id.ifBlank { java.util.UUID.randomUUID().toString() }
                            val typeName = item.assessmentType.displayName
                            val courseName = item.courseTitle.ifBlank { item.courseCode }
                            val noticeTitle = "$typeName • $courseName"
                            val article = if (typeName.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "An" else "A"
                            val noticeBody = "$article $typeName has been announced. Click below to view details.\n\n[assessment_id:$validAssessmentId]"
                            onPostNotice(noticeTitle, noticeBody, item.courseId) { noticeId ->
                                scope.launch(Dispatchers.IO) {
                                    repository.updateAssessmentNoticeId(validAssessmentId, noticeId, currentBatchId)
                                }
                            }
                        }
                    } else if (editingAssessment == null) {
                        // 3. Only send standalone push alert for new assessments when not already posting a notice
                        scope.launch(Dispatchers.IO) {
                            onBroadcastPush(
                                "📝 New ${item.assessmentType.displayName}: ${item.courseCode}",
                                "${item.title} — Due ${item.getFormattedDate()} ${item.deadlineTime}"
                            )
                        }
                    }
                },
                isDark = isDark
            )
        }
    }
}
