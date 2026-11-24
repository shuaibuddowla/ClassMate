package com.shuaib.classmate.ui.fund

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun formatTaka(amount: Double): String {
    return if (amount == amount.toLong().toDouble()) {
        "৳ " + NumberFormat.getNumberInstance(Locale.US).format(amount.toLong())
    } else {
        "৳ " + String.format(Locale.US, "%,.2f", amount)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassMateBatchFundScreen(
    summary: FundSummary,
    campaigns: List<FundCampaignItem>,
    transactions: List<FundTransactionItem>,
    students: List<FundStudentDue>,
    currentUserId: String,
    onBackClick: () -> Unit,
    onShareReport: () -> Unit,
    onUpdatePurpose: (String) -> Unit,
    onCreateCampaign: (name: String, perStudent: Double) -> Unit,
    onQuickCollectBatch: (studentIds: List<String>, campaignName: String, method: String, amountPerStudent: Double) -> Unit,
    onDirectCollectSingle: (student: FundStudentDue, campaignName: String, perStudent: Double) -> Unit,
    onToggleStudentUnpaid: (student: FundStudentDue, campaignName: String) -> Unit,
    onSaveTransaction: (
        isDeposit: Boolean,
        name: String,
        studentId: String?,
        studentProfileId: String?,
        amount: Double,
        method: String,
        reason: String
    ) -> Unit,
    onDeleteTransaction: (tx: FundTransactionItem) -> Unit,
    onSendReminderNotification: (campaignName: String, unpaidProfileIds: List<String>) -> Unit,
    onRefreshData: () -> Unit,
    undoAction: UndoAction?,
    onDismissUndo: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val canvasBg = if (isDark) Color(0xFF080A0F) else Color(0xFFF5F7FB)
    val surfaceBg = if (isDark) Color(0xFF111520) else Color.White
    val surface2Bg = if (isDark) Color(0xFF181E2D) else Color(0xFFF1F5F9)
    val textPrimary = if (isDark) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.07f) else Color(0xFFE2E8F0)

    val listState = rememberLazyListState()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }

    var hideBalance by remember { mutableStateOf(false) }
    var userSelectedCampaignId by remember { mutableStateOf<String?>(null) }

    val currentCampaignId = userSelectedCampaignId?.takeIf { id -> campaigns.any { it.id == id } }
        ?: campaigns.firstOrNull()?.id
        ?: "general"

    val activeCamp = remember(currentCampaignId, campaigns.toList()) {
        campaigns.firstOrNull { it.id == currentCampaignId }
            ?: campaigns.firstOrNull()
            ?: FundCampaignItem("general", "General Fund", 0, 0, 0.0, 0.0)
    }

    val totalSlides = if (summary.isCr) campaigns.size + 1 else campaigns.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalSlides })

    LaunchedEffect(pagerState.currentPage, campaigns.toList()) {
        if (campaigns.isNotEmpty() && pagerState.currentPage < campaigns.size) {
            userSelectedCampaignId = campaigns[pagerState.currentPage].id
        }
    }

    LaunchedEffect(currentCampaignId, campaigns.toList()) {
        val targetIdx = campaigns.indexOfFirst { it.id == currentCampaignId }
        if (targetIdx >= 0 && targetIdx != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetIdx)
        }
    }

    var feedMode by remember { mutableStateOf("activity") } // "activity" or "dues"
    var activityFilter by remember { mutableStateOf("campaign") } // "campaign", "all", "inflow", "outflow"
    var duesFilter by remember { mutableStateOf("unpaid") } // "unpaid", "paid", "all"
    var searchQuery by remember { mutableStateOf("") }

    var drawerMode by remember { mutableStateOf<FundDrawerMode>(FundDrawerMode.None) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val unpaidStudents = remember(students.toList(), currentCampaignId) {
        students.filter { it.paidMap[currentCampaignId] != true }
    }

    val filteredTxs = remember(transactions.toList(), activityFilter, activeCamp.name, activeCamp.id, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val campName = activeCamp.name.trim().lowercase()
        val campId = activeCamp.id.trim().lowercase()
        transactions.filter { tx ->
            if (activityFilter == "campaign") {
                val txCamp = tx.campaign.trim().lowercase()
                if (txCamp != campName && txCamp != campId) return@filter false
            } else if (activityFilter == "inflow" && tx.type != "inflow") {
                return@filter false
            } else if (activityFilter == "outflow" && tx.type != "outflow") {
                return@filter false
            }
            if (q.isNotEmpty()) {
                val matchTitle = tx.title.lowercase().contains(q)
                val matchStudent = tx.studentName?.lowercase()?.contains(q) == true
                val matchSid = tx.studentId?.lowercase()?.contains(q) == true
                val matchCamp = tx.campaign.lowercase().contains(q)
                if (!matchTitle && !matchStudent && !matchSid && !matchCamp) return@filter false
            }
            true
        }
    }

    val groupedTxs = remember(filteredTxs) {
        filteredTxs.groupBy { it.formattedDate.ifBlank { "Recent Activity" } }
    }

    val duesList = remember(students.toList(), currentCampaignId, duesFilter, searchQuery) {
        val q = searchQuery.trim().lowercase()
        students.filter { s ->
            val paid = s.paidMap[currentCampaignId] == true
            if (duesFilter == "unpaid" && paid) return@filter false
            if (duesFilter == "paid" && !paid) return@filter false
            if (q.isNotEmpty()) {
                val matchName = s.fullName.lowercase().contains(q)
                val matchId = s.studentId.lowercase().contains(q)
                if (!matchName && !matchId) return@filter false
            }
            true
        }
    }

    BackHandler(drawerMode !is FundDrawerMode.None) {
        drawerMode = FundDrawerMode.None
    }

    Scaffold(
        containerColor = canvasBg,
        topBar = {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Batch Fund",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = textPrimary,
                            lineHeight = 22.sp
                        )
                        Text(
                            text = "${summary.batchName.replace("cse", "CSE", ignoreCase = true)} • ${summary.university} Treasury",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = textSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Info / Rules Button
                    Surface(
                        onClick = { drawerMode = FundDrawerMode.Info },
                        shape = CircleShape,
                        color = surfaceBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = "Rules",
                                tint = textSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Share Statement Button
                    Surface(
                        onClick = onShareReport,
                        shape = CircleShape,
                        color = surfaceBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = "Share",
                                tint = textSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. FINTECH WALLET HERO CARD
                item {
                    FintechWalletHeroCard(
                        summary = summary,
                        hideBalance = hideBalance,
                        onTogglePrivacy = { hideBalance = !hideBalance },
                        onFilterInflow = {
                            feedMode = "activity"
                            activityFilter = "inflow"
                        },
                        onFilterOutflow = {
                            feedMode = "activity"
                            activityFilter = "outflow"
                        }
                    )
                }

                // 2. CONTEXTUAL ACTION DOCK (4 columns)
                item {
                    ActionDockSection(
                        isCr = summary.isCr,
                        surfaceBg = surfaceBg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        onQuickCollect = { drawerMode = FundDrawerMode.QuickCollect },
                        onAddFund = { drawerMode = FundDrawerMode.Record(txType = "deposit") },
                        onExpense = { drawerMode = FundDrawerMode.Record(txType = "expense") },
                        onAnalytics = { drawerMode = FundDrawerMode.Analytics },
                        onMyPayments = {
                            feedMode = "activity"
                            searchQuery = currentUserId
                        },
                        onDuesJump = {
                            feedMode = "dues"
                        },
                        onFundRules = { drawerMode = FundDrawerMode.Info }
                    )
                }

                // 3. STUDENT PERSONAL STATUS BANNER (if not CR)
                if (!summary.isCr) {
                    item {
                        val me = students.firstOrNull { it.studentId == currentUserId || it.profileId == currentUserId }
                        val iPaid = me?.paidMap?.get(activeCamp.id) == true
                        StudentPersonalStatusBanner(
                            activeCamp = activeCamp,
                            student = me,
                            iPaid = iPaid
                        )
                    }
                }

                // 4. INSTAGRAM-STYLE COLLECTION CAROUSEL
                item {
                    InstagramCollectionCarousel(
                        campaigns = campaigns,
                        pagerState = pagerState,
                        totalSlides = totalSlides,
                        students = students,
                        isCr = summary.isCr,
                        isDark = isDark,
                        surfaceBg = surfaceBg,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onAddCampaign = { drawerMode = FundDrawerMode.NewCampaign },
                        onQuickCollect = { drawerMode = FundDrawerMode.QuickCollect },
                        onViewDues = { feedMode = "dues" }
                    )
                }

                // 5. UNIFIED WORKSPACE (Activity | Dues)
                item {
                    UnifiedWorkspaceHeader(
                        feedMode = feedMode,
                        unpaidCount = (activeCamp.total - activeCamp.paid).coerceAtLeast(0),
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        filterText = if (feedMode == "activity") {
                            when (activityFilter) {
                                "campaign" -> if (activeCamp.name.length > 12) "${activeCamp.name.take(12)}…" else activeCamp.name
                                "inflow" -> "Inflow"
                                "outflow" -> "Outflow"
                                else -> "All Activity"
                            }
                        } else {
                            when (duesFilter) {
                                "paid" -> "Paid"
                                "all" -> "All"
                                else -> "Unpaid"
                            }
                        },
                        onFilterCycle = {
                            if (feedMode == "activity") {
                                activityFilter = when (activityFilter) {
                                    "campaign" -> "all"
                                    "all" -> "inflow"
                                    "inflow" -> "outflow"
                                    else -> "campaign"
                                }
                            } else {
                                duesFilter = when (duesFilter) {
                                    "unpaid" -> "paid"
                                    "paid" -> "all"
                                    else -> "unpaid"
                                }
                            }
                        },
                        onTabChange = { feedMode = it },
                        surfaceBg = surfaceBg,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                }

                // Dues Quick Tools (Copy Unpaid & Send Reminder)
                if (feedMode == "dues") {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Copy Unpaid Button
                            Surface(
                                onClick = {
                                    if (unpaidStudents.isNotEmpty()) {
                                        val sb = StringBuilder()
                                        sb.append("📋 [${activeCamp.name}] Unpaid Students (${unpaidStudents.size}):\n")
                                        unpaidStudents.forEachIndexed { i, s ->
                                            val idStr = if (s.studentId.isNotBlank()) " (ID: ${s.studentId})" else ""
                                            sb.append("${i + 1}. ${s.fullName}$idStr\n")
                                        }
                                        clipboardManager.setText(AnnotatedString(sb.toString().trimEnd()))
                                        android.widget.Toast.makeText(context, "Copied ${unpaidStudents.size} unpaid students!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "No unpaid students to copy!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = surfaceBg,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_copy),
                                        contentDescription = "Copy",
                                        tint = Color(0xFF6366F1),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Copy Unpaid",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                }
                            }

                            // Send Reminder Button (CR only)
                            if (summary.isCr) {
                                Surface(
                                    onClick = {
                                        drawerMode = FundDrawerMode.Remind(activeCamp.name)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_bell_outline),
                                            contentDescription = "Reminder",
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Send Reminder",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color(0xFFFDE68A) else Color(0xFFB45309)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. FEED ITEMS (Activity Feed or Dues Feed)
                if (feedMode == "activity") {
                    if (filteredTxs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No matching transactions." else "No transactions recorded yet.",
                                    fontSize = 13.sp,
                                    color = textSecondary
                                )
                            }
                        }
                    } else {
                        groupedTxs.forEach { (dateGroup, txList) ->
                            item {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = dateGroup,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textSecondary,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(22.dp),
                                        color = surfaceBg,
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column {
                                            txList.forEachIndexed { idx, tx ->
                                                TransactionItemRow(
                                                    tx = tx,
                                                    textPrimary = textPrimary,
                                                    textSecondary = textSecondary,
                                                    surface2Bg = surface2Bg,
                                                    onClick = { drawerMode = FundDrawerMode.TxDetail(tx) }
                                                )
                                                if (idx < txList.lastIndex) {
                                                    HorizontalDivider(
                                                        thickness = 0.5.dp,
                                                        color = borderColor.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // DUES FEED
                    if (duesList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No students match this query." else "All students clear for this filter!",
                                    fontSize = 13.sp,
                                    color = textSecondary
                                )
                            }
                        }
                    } else {
                        item {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = surfaceBg,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    duesList.forEachIndexed { idx, student ->
                                        val paid = student.paidMap[activeCamp.id] == true
                                        StudentDueItemRow(
                                            student = student,
                                            isPaid = paid,
                                            isCr = summary.isCr,
                                            amountPerStudent = activeCamp.perStudent,
                                            textPrimary = textPrimary,
                                            textSecondary = textSecondary,
                                            surface2Bg = surface2Bg,
                                            onCollectClick = {
                                                onDirectCollectSingle(student, activeCamp.name, activeCamp.perStudent)
                                            },
                                            onToggleUnpaid = {
                                                if (summary.isCr) {
                                                    onToggleStudentUnpaid(student, activeCamp.name)
                                                }
                                            }
                                        )
                                        if (idx < duesList.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = borderColor.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Scroll-to-Top FAB
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp)
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF1E2638) else Color.White,
                    border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFCBD5E1)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_up),
                            contentDescription = "Scroll to top",
                            tint = if (isDark) Color.White else Color(0xFF0F172A),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Floating Toast with Undo
            if (undoAction != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isDark) Color.White else Color(0xFF0F172A),
                        shadowElevation = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = undoAction.message,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF0F172A) else Color.White
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    undoAction.onUndo()
                                    onDismissUndo()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Undo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    // DRAWERS (Bottom Sheets)
    if (drawerMode !is FundDrawerMode.None) {
        ModalBottomSheet(
            onDismissRequest = { drawerMode = FundDrawerMode.None },
            sheetState = sheetState,
            containerColor = surfaceBg,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 6.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1))
                )
            }
        ) {
            when (val mode = drawerMode) {
                is FundDrawerMode.QuickCollect -> {
                    QuickCollectDeskSheet(
                        campaign = activeCamp,
                        students = students,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onConfirm = { ids, method ->
                            onQuickCollectBatch(ids, activeCamp.name, method, activeCamp.perStudent)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                is FundDrawerMode.Record -> {
                    AddFundRecordSheet(
                        initialType = mode.txType,
                        prefillStudent = mode.prefillStudentName,
                        prefillTitle = mode.prefillTitle,
                        campaigns = campaigns,
                        activeCampaign = activeCamp.name,
                        students = students,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onSave = { isDeposit, name, sId, pId, amt, method, reason ->
                            onSaveTransaction(isDeposit, name, sId, pId, amt, method, reason)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                is FundDrawerMode.Analytics -> {
                    TreasuryAnalyticsSheet(
                        summary = summary,
                        campaigns = campaigns,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None }
                    )
                }
                is FundDrawerMode.Info -> {
                    FundPurposeRulesSheet(
                        isCr = summary.isCr,
                        purpose = summary.fundDescription,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onSave = {
                            onUpdatePurpose(it)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                is FundDrawerMode.Remind -> {
                    RemindUnpaidSheet(
                        campaignName = mode.campaignName,
                        unpaidCount = (activeCamp.total - activeCamp.paid).coerceAtLeast(0),
                        perStudent = activeCamp.perStudent,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onCopyText = { txt ->
                            clipboardManager.setText(AnnotatedString(txt))
                            android.widget.Toast.makeText(context, "Reminder copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                            drawerMode = FundDrawerMode.None
                        },
                        onSendPush = {
                            val unpaidProfileIds = students
                                .filter { it.paidMap[activeCamp.id] != true }
                                .mapNotNull { it.profileId }
                            onSendReminderNotification(mode.campaignName, unpaidProfileIds)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                is FundDrawerMode.NewCampaign -> {
                    NewCampaignSheet(
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onCreate = { name, perStudent ->
                            onCreateCampaign(name, perStudent)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                is FundDrawerMode.TxDetail -> {
                    TransactionDetailReceiptSheet(
                        tx = mode.tx,
                        isCr = summary.isCr,
                        isDark = isDark,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClose = { drawerMode = FundDrawerMode.None },
                        onDelete = {
                            onDeleteTransaction(mode.tx)
                            drawerMode = FundDrawerMode.None
                        }
                    )
                }
                else -> {}
            }
        }
    }
}

// 1. FINTECH WALLET HERO CARD
@Composable
private fun FintechWalletHeroCard(
    summary: FundSummary,
    hideBalance: Boolean,
    onTogglePrivacy: () -> Unit,
    onFilterInflow: () -> Unit = {},
    onFilterOutflow: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF151830),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 14.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1C2042),
                            Color(0xFF151830),
                            Color(0xFF0E1120)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top Row: Available Balance + Privacy Toggle + Live Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Available Balance",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC7D2FE).copy(alpha = 0.85f),
                            letterSpacing = 0.3.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = onTogglePrivacy,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(if (hideBalance) R.drawable.ic_fund_eye_off else R.drawable.ic_eye_outline),
                                contentDescription = "Privacy toggle",
                                tint = Color(0xFFC7D2FE).copy(alpha = 0.8f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Live Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.10f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34D399))
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Live",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6EE7B7)
                            )
                        }
                    }
                }

                // Balance Numbers + Entries count
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (hideBalance) "৳ •••••" else formatTaka(summary.currentBalance),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 38.sp
                    )
                    Text(
                        text = "${summary.txCount} entries",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFC7D2FE).copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Cash vs Digital (bKash/Nagad) Mini Pills
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cash Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fund_banknote),
                                contentDescription = null,
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Cash ",
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (hideBalance) "••••" else formatTaka(summary.cashBalance),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // MFS Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fund_smartphone),
                                contentDescription = null,
                                tint = Color(0xFFF472B6),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "MFS ",
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (hideBalance) "••••" else formatTaka(summary.mfsBalance),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Inflow / Outflow Footer
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.10f)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onFilterInflow,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.06f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fund_arrow_down_left),
                                contentDescription = null,
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "In ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF6EE7B7)
                            )
                            Text(
                                text = formatTaka(summary.totalCollected),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6EE7B7)
                            )
                        }
                    }

                    Surface(
                        onClick = onFilterOutflow,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.06f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fund_arrow_up_right),
                                contentDescription = null,
                                tint = Color(0xFFFDA4AF),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Out ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFDA4AF)
                            )
                            Text(
                                text = formatTaka(summary.totalSpent),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDA4AF)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 2. CONTEXTUAL ACTION DOCK
@Composable
private fun ActionDockSection(
    isCr: Boolean,
    surfaceBg: Color,
    borderColor: Color,
    textPrimary: Color,
    onQuickCollect: () -> Unit,
    onAddFund: () -> Unit,
    onExpense: () -> Unit,
    onAnalytics: () -> Unit,
    onMyPayments: () -> Unit,
    onDuesJump: () -> Unit,
    onFundRules: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (isCr) {
            // 1. Quick Collect (Gradient Accent)
            ActionDockItem(
                iconRes = R.drawable.ic_fund_zap,
                label = "Quick Collect",
                textColor = textPrimary,
                isPrimaryGradient = true,
                onClick = onQuickCollect
            )
            // 2. Add Fund (+)
            ActionDockItem(
                iconRes = R.drawable.ic_add,
                label = "Add Fund",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFF10B981),
                textColor = textPrimary,
                onClick = onAddFund
            )
            // 3. Expense (Up-Right)
            ActionDockItem(
                iconRes = R.drawable.ic_fund_arrow_up_right,
                label = "Expense",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFFF43F5E),
                textColor = textPrimary,
                onClick = onExpense
            )
            // 4. Analytics
            ActionDockItem(
                iconRes = R.drawable.ic_fund_chart,
                label = "Analytics",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFF6366F1),
                textColor = textPrimary,
                onClick = onAnalytics
            )
        } else {
            // Student: 1. My Payments
            ActionDockItem(
                iconRes = R.drawable.ic_fund_receipt,
                label = "My Payments",
                textColor = textPrimary,
                isPrimaryGradient = true,
                onClick = onMyPayments
            )
            // 2. Dues List
            ActionDockItem(
                iconRes = R.drawable.ic_batch_friends,
                label = "Dues List",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFF6366F1),
                textColor = textPrimary,
                onClick = onDuesJump
            )
            // 3. Analytics
            ActionDockItem(
                iconRes = R.drawable.ic_fund_chart,
                label = "Analytics",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFF6366F1),
                textColor = textPrimary,
                onClick = onAnalytics
            )
            // 4. Fund Rules
            ActionDockItem(
                iconRes = R.drawable.ic_fund_shield_check,
                label = "Fund Rules",
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                iconTint = Color(0xFF10B981),
                textColor = textPrimary,
                onClick = onFundRules
            )
        }
    }
}

@Composable
private fun ActionDockItem(
    iconRes: Int,
    label: String,
    surfaceBg: Color = Color.Transparent,
    borderColor: Color = Color.Transparent,
    iconTint: Color = Color.White,
    textColor: Color,
    isPrimaryGradient: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = if (isPrimaryGradient) Color.Transparent else surfaceBg,
            border = if (isPrimaryGradient) null else BorderStroke(1.dp, borderColor),
            shadowElevation = if (isPrimaryGradient) 6.dp else 1.dp,
            modifier = Modifier.size(52.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isPrimaryGradient) {
                            Modifier.background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                                )
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = if (isPrimaryGradient) Color.White else iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// 3. STUDENT PERSONAL STATUS BANNER
@Composable
private fun StudentPersonalStatusBanner(
    activeCamp: FundCampaignItem,
    student: FundStudentDue?,
    iPaid: Boolean
) {
    val bgColor = if (iPaid) Color(0xFF10B981).copy(alpha = 0.10f) else Color(0xFFF59E0B).copy(alpha = 0.10f)
    val bdColor = if (iPaid) Color(0xFF10B981).copy(alpha = 0.20f) else Color(0xFFF59E0B).copy(alpha = 0.20f)
    val statusText = if (iPaid) "Cleared" else "Pending"
    val statusColor = if (iPaid) Color(0xFF10B981) else Color(0xFFF59E0B)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        border = BorderStroke(1.dp, bdColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(if (iPaid) R.drawable.ic_check else R.drawable.ic_bell_outline),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (iPaid) "You're paid for ${activeCamp.name}" else "${activeCamp.name} due: ${formatTaka(activeCamp.perStudent)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSystemInDarkTheme()) Color.White else Color(0xFF0F172A)
                    )
                    Text(
                        text = "${student?.fullName ?: "Student"} • ${student?.studentId ?: ""}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}

// 4. INSTAGRAM-STYLE COLLECTION CAROUSEL
@Composable
private fun InstagramCollectionCarousel(
    campaigns: List<FundCampaignItem>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    totalSlides: Int,
    students: List<FundStudentDue>,
    isCr: Boolean,
    isDark: Boolean,
    surfaceBg: Color,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onAddCampaign: () -> Unit,
    onQuickCollect: () -> Unit,
    onViewDues: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            if (campaigns.isEmpty()) {
                if (isCr) {
                    AddNewCollectionSlideCard(
                        isDark = isDark,
                        surfaceBg = surfaceBg,
                        surface2Bg = surface2Bg,
                        borderColor = borderColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClick = onAddCampaign
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = surfaceBg,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No Active Collections",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "There are currently no active collections running for your batch.",
                                fontSize = 12.sp,
                                color = textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else if (page < campaigns.size) {
                val camp = campaigns[page]
                CampaignSlideCard(
                    camp = camp,
                    isCr = isCr,
                    isDark = isDark,
                    surfaceBg = surfaceBg,
                    surface2Bg = surface2Bg,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onAddCampaign = onAddCampaign,
                    onActionClick = {
                        if (isCr) onQuickCollect() else onViewDues()
                    }
                )
            } else {
                // "+ Add New Collection" Slide for CR
                AddNewCollectionSlideCard(
                    isDark = isDark,
                    surfaceBg = surfaceBg,
                    surface2Bg = surface2Bg,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onClick = onAddCampaign
                )
            }
        }

        // Instagram-style Pagination Dots
        if (totalSlides > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until totalSlides) {
                    val isSelected = i == pagerState.currentPage
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 18.dp else 6.dp,
                        animationSpec = tween(durationMillis = 200),
                        label = "dot_width"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            Color(0xFF6366F1)
                        } else {
                            if (isDark) Color.White.copy(alpha = 0.2f) else Color(0xFFCBD5E1)
                        },
                        animationSpec = tween(durationMillis = 200),
                        label = "dot_color"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = width, height = 6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun CampaignSlideCard(
    camp: FundCampaignItem,
    isCr: Boolean,
    isDark: Boolean,
    surfaceBg: Color,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onAddCampaign: () -> Unit,
    onActionClick: () -> Unit
) {
    val unpaidCount = (camp.total - camp.paid).coerceAtLeast(0)
    val target = camp.total * camp.perStudent
    val pct = if (camp.total > 0) ((camp.paid * 100) / camp.total).coerceIn(0, 100) else 0

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = surfaceBg,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Category Pill & Plus button (if CR) + Progress %
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Small Category Tag
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF6366F1).copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "COLLECTION",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6366F1),
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (isCr) {
                        Surface(
                            onClick = onAddCampaign,
                            shape = CircleShape,
                            color = surface2Bg,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6366F1)
                                )
                            }
                        }
                    }
                }

                // Percentage Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6366F1).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "$pct%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF6366F1),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            // Title & Target Amount Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = camp.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formatTaka(camp.collected),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        text = "of ${formatTaka(target)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = textSecondary
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = textSecondary.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${formatTaka(camp.perStudent)}/student",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = textSecondary
                    )
                }
            }

            // Multi-Gradient Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFF34D399))
                            )
                        )
                )
            }

            // Footer: Paid/Unpaid Summary (Clean, no stacked circles) + Action CTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (unpaidCount == 0) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Text(
                        text = "${camp.paid} paid • $unpaidCount unpaid",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary
                    )
                }

                // CTA Button
                Surface(
                    onClick = onActionClick,
                    shape = CircleShape,
                    color = Color(0xFF6366F1).copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isCr) "Quick Collect" else "View dues",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6366F1)
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddNewCollectionSlideCard(
    isDark: Boolean,
    surfaceBg: Color,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = surfaceBg,
        border = BorderStroke(1.2.dp, Color(0xFF6366F1).copy(alpha = if (isDark) 0.35f else 0.25f)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6366F1).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Add New Collection",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Create a custom target & track dues per student",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = CircleShape,
                color = Color(0xFF6366F1)
            ) {
                Text(
                    text = "New Campaign",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// 5. UNIFIED WORKSPACE HEADER (Tabs, Search, Filter)
@Composable
private fun UnifiedWorkspaceHeader(
    feedMode: String,
    unpaidCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filterText: String,
    onFilterCycle: () -> Unit,
    onTabChange: (String) -> Unit,
    surfaceBg: Color,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 2-way Segmented Control: [ Activity ] | [ Dues (count) ]
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surface2Bg,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp)
            ) {
                // Activity Tab
                Surface(
                    onClick = { onTabChange("activity") },
                    shape = RoundedCornerShape(13.dp),
                    color = if (feedMode == "activity") surfaceBg else Color.Transparent,
                    shadowElevation = if (feedMode == "activity") 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Activity",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (feedMode == "activity") textPrimary else textSecondary
                        )
                    }
                }

                // Dues Tab
                Surface(
                    onClick = { onTabChange("dues") },
                    shape = RoundedCornerShape(13.dp),
                    color = if (feedMode == "dues") surfaceBg else Color.Transparent,
                    shadowElevation = if (feedMode == "dues") 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Dues",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (feedMode == "dues") textPrimary else textSecondary
                        )
                        if (unpaidCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFF43F5E).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "$unpaidCount",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFF43F5E),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Search Bar + Filter Cycle Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search Field
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surfaceBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_modern),
                        contentDescription = "Search",
                        tint = textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search name, ID, or campaign...",
                                fontSize = 12.sp,
                                color = textSecondary.copy(alpha = 0.7f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear",
                                tint = textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Filter Cycle Button
            Surface(
                onClick = onFilterCycle,
                shape = RoundedCornerShape(16.dp),
                color = surfaceBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.height(44.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filterText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary
                    )
                }
            }
        }
    }
}

// TRANSACTION ITEM ROW (Activity Feed)
@Composable
private fun TransactionItemRow(
    tx: FundTransactionItem,
    textPrimary: Color,
    textSecondary: Color,
    surface2Bg: Color,
    onClick: () -> Unit
) {
    val isExp = tx.type == "outflow"
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isExp) Color(0xFFF43F5E).copy(alpha = 0.12f) else Color(0xFF10B981).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isExp) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fund_arrow_up_right),
                            contentDescription = null,
                            tint = Color(0xFFF43F5E),
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = (tx.studentName?.ifBlank { null } ?: tx.title).take(1).uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${tx.campaign} • ${tx.createdByName ?: "CR"}",
                            fontSize = 11.sp,
                            color = textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        // Method Badge
                        MethodBadgePill(method = tx.method, surface2Bg = surface2Bg)
                    }
                }
            }

            // Amount Column
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (isExp) "-" else "+"}${formatTaka(tx.amount)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isExp) Color(0xFFF43F5E) else Color(0xFF10B981)
                )
                if (tx.isOfflinePending) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "🕒 Pending Sync",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

// METHOD BADGE PILL
@Composable
private fun MethodBadgePill(method: String, surface2Bg: Color) {
    val (bgColor, textColor) = when (method) {
        "bKash" -> Color(0xFFEC4899).copy(alpha = 0.15f) to Color(0xFFEC4899)
        "Nagad" -> Color(0xFFF97316).copy(alpha = 0.15f) to Color(0xFFF97316)
        else -> surface2Bg to Color(0xFF94A3B8)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = method,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

// STUDENT DUE ITEM ROW (Dues Feed)
@Composable
private fun StudentDueItemRow(
    student: FundStudentDue,
    isPaid: Boolean,
    isCr: Boolean,
    amountPerStudent: Double,
    textPrimary: Color,
    textSecondary: Color,
    surface2Bg: Color,
    onCollectClick: () -> Unit,
    onToggleUnpaid: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isPaid) Color(0xFF10B981).copy(alpha = 0.12f) else surface2Bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = student.fullName.take(1).uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaid) Color(0xFF10B981) else textSecondary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = student.fullName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = student.studentId.ifBlank { "Student" },
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
        }

        if (isPaid) {
            if (isCr) {
                Surface(
                    onClick = onToggleUnpaid,
                    shape = CircleShape,
                    color = Color(0xFF10B981).copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Paid",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF10B981).copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Paid",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }
        } else {
            if (isCr) {
                Button(
                    onClick = onCollectClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "Collect ${formatTaka(amountPerStudent)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFF43F5E).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Unpaid",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF43F5E),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// QUICK COLLECT DESK SHEET
@Composable
private fun QuickCollectDeskSheet(
    campaign: FundCampaignItem,
    students: List<FundStudentDue>,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onConfirm: (studentIds: List<String>, method: String) -> Unit
) {
    var method by remember { mutableStateOf("Cash") }
    var filterText by remember { mutableStateOf("") }
    val unpaidList = remember(students, campaign.id) {
        students.filter { it.paidMap[campaign.id] != true }
    }
    val selectedIds = remember { mutableStateListOf<String>() }

    val filtered = remember(unpaidList, filterText) {
        val q = filterText.trim().lowercase()
        unpaidList.filter { s ->
            if (q.isEmpty()) true
            else s.fullName.lowercase().contains(q) || s.studentId.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Quick Collect • ${campaign.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                Text(
                    text = "Type last 2 digits of ID or tap names (${formatTaka(campaign.perStudent)} each)",
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        // Method Selector (Cash, bKash, Nagad)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surface2Bg,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Cash", "bKash", "Nagad").forEach { m ->
                    val sel = method == m
                    val activeColor = when (m) {
                        "bKash" -> Color(0xFFDB2777)
                        "Nagad" -> Color(0xFFEA580C)
                        else -> Color(0xFF059669)
                    }
                    Surface(
                        onClick = { method = m },
                        shape = RoundedCornerShape(13.dp),
                        color = if (sel) activeColor else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = m,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (sel) Color.White else textSecondary
                            )
                        }
                    }
                }
            }
        }

        // Fast Filter Input + Select All
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = surface2Bg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_modern),
                        contentDescription = null,
                        tint = textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (filterText.isEmpty()) {
                            Text(
                                text = "Fast filter: e.g. 14, 06, Sudip...",
                                fontSize = 12.sp,
                                color = textSecondary.copy(alpha = 0.7f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (unpaidList.isNotEmpty()) {
                Surface(
                    onClick = {
                        if (selectedIds.size == unpaidList.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(unpaidList.map { it.studentId })
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = surface2Bg,
                    modifier = Modifier.height(40.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedIds.size == unpaidList.size) "Clear" else "Select All",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6366F1)
                        )
                    }
                }
            }
        }

        // List of Unpaid Students
        if (unpaidList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "All students in this list have paid!",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF10B981)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered) { s ->
                    val isSel = selectedIds.contains(s.studentId)
                    Surface(
                        onClick = {
                            if (isSel) selectedIds.remove(s.studentId)
                            else selectedIds.add(s.studentId)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSel) Color(0xFF6366F1).copy(alpha = 0.12f) else surface2Bg,
                        border = BorderStroke(1.dp, if (isSel) Color(0xFF6366F1) else borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDark) Color(0xFF181E2D) else Color(0xFFE2E8F0)
                                ) {
                                    Text(
                                        text = s.studentId.takeLast(2),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = s.fullName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = s.studentId,
                                        fontSize = 10.sp,
                                        color = textSecondary
                                    )
                                }
                            }

                            // Checkbox circle
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) Color(0xFF6366F1) else Color.Transparent)
                                    .border(1.5.dp, if (isSel) Color(0xFF6366F1) else borderColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSel) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Confirm Button
            val totalCollect = selectedIds.size * campaign.perStudent
            Button(
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        onConfirm(selectedIds.toList(), method)
                    }
                },
                enabled = selectedIds.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6366F1),
                    disabledContainerColor = surface2Bg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (selectedIds.isNotEmpty()) "Confirm ${selectedIds.size} via $method (+${formatTaka(totalCollect)})" else "Tap classmates to collect",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedIds.isNotEmpty()) Color.White else textSecondary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// RECORD / ADD FUND SHEET
@Composable
private fun AddFundRecordSheet(
    initialType: String,
    prefillStudent: String?,
    prefillTitle: String?,
    campaigns: List<FundCampaignItem>,
    activeCampaign: String,
    students: List<FundStudentDue>,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onSave: (
        isDeposit: Boolean,
        name: String,
        studentId: String?,
        studentProfileId: String?,
        amount: Double,
        method: String,
        reason: String
    ) -> Unit
) {
    var txType by remember { mutableStateOf(initialType) }
    val isDep = txType == "deposit"
    var nameInput by remember { mutableStateOf(prefillStudent ?: "") }
    var amountInput by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    var reasonInput by remember { mutableStateOf(prefillTitle ?: activeCampaign) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isDep) "Add Fund" else "Record Expense",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        // Toggle Type (Deposit vs Expense)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surface2Bg,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = { txType = "deposit" },
                    shape = RoundedCornerShape(13.dp),
                    color = if (isDep) Color(0xFF059669) else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Deposit (+)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDep) Color.White else textSecondary
                        )
                    }
                }

                Surface(
                    onClick = { txType = "expense" },
                    shape = RoundedCornerShape(13.dp),
                    color = if (!isDep) Color(0xFFE11D48) else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Expense (-)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isDep) Color.White else textSecondary
                        )
                    }
                }
            }
        }

        // Name or Description Input
        Column {
            Text(
                text = if (isDep) "Student Name or ID" else "Expense Purpose",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                placeholder = { Text(if (isDep) "Search student or type name..." else "e.g. Tour Bus Rent, Banner Print", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Amount Input + Quick Chips + Method
        Column {
            Text(
                text = "Amount (৳) & Method",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OutlinedTextField(
                value = amountInput,
                onValueChange = { amountInput = it },
                placeholder = { Text("0", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick Amount Chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40, 500, 1000).forEach { amt ->
                        Surface(
                            onClick = { amountInput = amt.toString() },
                            shape = CircleShape,
                            color = surface2Bg
                        ) {
                            Text(
                                text = "৳ $amt",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Payment Method Selector
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Cash", "bKash", "Nagad").forEach { m ->
                        val sel = method == m
                        Surface(
                            onClick = { method = m },
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) Color(0xFF6366F1) else surface2Bg
                        ) {
                            Text(
                                text = m,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (sel) Color.White else textSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Campaign / Tag
        Column {
            Text(
                text = "Campaign / Tag",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OutlinedTextField(
                value = reasonInput,
                onValueChange = { reasonInput = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(campaigns) { c ->
                    val sel = reasonInput.equals(c.name, ignoreCase = true)
                    Surface(
                        onClick = { reasonInput = c.name },
                        shape = CircleShape,
                        color = if (sel) Color(0xFF6366F1) else surface2Bg
                    ) {
                        Text(
                            text = c.name,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color.White else textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Save Button
        Button(
            onClick = {
                val amt = amountInput.toDoubleOrNull() ?: 0.0
                if (nameInput.isNotBlank() && amt > 0) {
                    val matchedStudent = students.firstOrNull {
                        it.fullName.equals(nameInput.trim(), ignoreCase = true) ||
                        it.studentId.equals(nameInput.trim(), ignoreCase = true)
                    }
                    onSave(
                        isDep,
                        matchedStudent?.fullName ?: nameInput.trim(),
                        matchedStudent?.studentId,
                        matchedStudent?.profileId,
                        amt,
                        method,
                        reasonInput.trim().ifBlank { "Batch Fund" }
                    )
                }
            },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Save Transaction", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// TREASURY ANALYTICS SHEET
@Composable
private fun TreasuryAnalyticsSheet(
    summary: FundSummary,
    campaigns: List<FundCampaignItem>,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Treasury Analytics", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Text("Cash vs MFS & campaign collection", fontSize = 11.sp, color = textSecondary)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        // Cash vs MFS Split Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF10B981).copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.15f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("CASH IN HAND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    Spacer(Modifier.height(4.dp))
                    Text(formatTaka(summary.cashBalance), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFEC4899).copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color(0xFFEC4899).copy(alpha = 0.15f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BKASH / NAGAD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEC4899))
                    Spacer(Modifier.height(4.dp))
                    Text(formatTaka(summary.mfsBalance), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                }
            }
        }

        // Custom Canvas Bar Chart for Campaigns
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surface2Bg,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("CAMPAIGN COLLECTIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                Spacer(Modifier.height(14.dp))

                val maxVal = remember(campaigns) { (campaigns.maxOfOrNull { it.collected } ?: 1.0).coerceAtLeast(1.0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    campaigns.forEach { c ->
                        val ratio = (c.collected / maxVal).toFloat().coerceIn(0.08f, 1f)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(60.dp)
                        ) {
                            Text(
                                text = formatTaka(c.collected),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .fillMaxHeight(ratio)
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                    .background(Color(c.colorHex))
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = c.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// FUND PURPOSE & RULES SHEET
@Composable
private fun FundPurposeRulesSheet(
    isCr: Boolean,
    purpose: String,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onSave: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(purpose) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Batch Fund Purpose & Rules", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        if (isCr) {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                minLines = 4,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(textValue.trim()) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Purpose", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surface2Bg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = purpose,
                    fontSize = 12.sp,
                    color = textPrimary,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// REMIND UNPAID SHEET
@Composable
private fun RemindUnpaidSheet(
    campaignName: String,
    unpaidCount: Int,
    perStudent: Double,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onCopyText: (String) -> Unit,
    onSendPush: () -> Unit
) {
    val defaultMsg = "আসসালামু আলাইকুম,\n$campaignName ফান্ডের (${formatTaka(perStudent)}) পেমেন্ট এখনো $unpaidCount জনের বাকি আছে। অনুগ্রহ করে দ্রুত ক্লিয়ার করার অনুরোধ রইল।\n— Batch CR"
    var msg by remember { mutableStateOf(defaultMsg) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reminder Generator", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        OutlinedTextField(
            value = msg,
            onValueChange = { msg = it },
            minLines = 4,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onCopyText(msg) },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(painter = painterResource(R.drawable.ic_copy), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy for Batch Messenger/WhatsApp Group", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        OutlinedButton(
            onClick = onSendPush,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Icon(painter = painterResource(R.drawable.ic_bell_outline), contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send In-App Push Notification ($unpaidCount students)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// NEW CAMPAIGN SHEET
@Composable
private fun NewCampaignSheet(
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onCreate: (name: String, perStudent: Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("500") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("New Collection Campaign", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Campaign Name") },
            placeholder = { Text("e.g. Batch Jersey, Picnic, Farewell") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount per student (৳)") },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val per = amount.toDoubleOrNull() ?: 0.0
                if (name.isNotBlank() && per > 0) {
                    onCreate(name.trim(), per)
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Create Collection", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// TRANSACTION DETAILS / RECEIPT SHEET
@Composable
private fun TransactionDetailReceiptSheet(
    tx: FundTransactionItem,
    isCr: Boolean,
    isDark: Boolean,
    surface2Bg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val isExp = tx.type == "outflow"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Payment Receipt", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = "Close", tint = textSecondary)
            }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = surface2Bg,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReceiptRow("Type", if (isExp) "Expense (-)" else "Deposit (+)", if (isExp) Color(0xFFF43F5E) else Color(0xFF10B981))
                ReceiptRow("Title / Name", tx.title, textPrimary)
                if (!tx.studentId.isNullOrBlank()) {
                    ReceiptRow("Student ID", tx.studentId, textPrimary)
                }
                ReceiptRow("Campaign", tx.campaign, textPrimary)
                ReceiptRow("Method", tx.method, textPrimary)
                ReceiptRow("Amount", "${if (isExp) "-" else "+"}${formatTaka(tx.amount)}", if (isExp) Color(0xFFF43F5E) else Color(0xFF10B981))
                ReceiptRow("Date", tx.formattedDate.ifBlank { tx.dateStr ?: "Recently" }, textSecondary)
                if (!tx.createdByName.isNullOrBlank()) {
                    ReceiptRow("Recorded by", tx.createdByName, textSecondary)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val receipt = """
                        🧾 Batch Fund Receipt
                        Title: ${tx.title}
                        Amount: ${formatTaka(tx.amount)}
                        Campaign: ${tx.campaign}
                        Method: ${tx.method}
                        Date: ${tx.formattedDate.ifBlank { tx.dateStr }}
                    """.trimIndent()
                    clipboardManager.setText(AnnotatedString(receipt))
                    android.widget.Toast.makeText(context, "Receipt copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                Text("Copy Receipt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (isCr || tx.canEdit) {
                Button(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E)),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Void / Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
