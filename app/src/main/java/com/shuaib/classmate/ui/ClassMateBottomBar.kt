package com.shuaib.classmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R

/**
 * ClassMate Tab identifiers and iconography.
 */
enum class ClassMateTab(
    val id: Int,
    val defaultTitle: String,
    val outlineIcon: Int,
    val filledIcon: Int
) {
    TIMETABLE(R.id.nav_timetable, "Timetable", R.drawable.ic_timetable_outline, R.drawable.ic_timetable),
    NOTICES(R.id.nav_notices, "Notices", R.drawable.ic_bell_outline, R.drawable.ic_notice),
    COURSES(R.id.nav_pdf, "Courses", R.drawable.ic_library_outline, R.drawable.ic_library_filled),
    BATCH(R.id.nav_friends, "Batch", R.drawable.ic_batch_friends, R.drawable.ic_batch_friends_filled),
    SETTINGS(R.id.nav_profile, "Settings", R.drawable.ic_nav_settings_outline, R.drawable.ic_nav_settings);

    companion object {
        fun fromId(id: Int): ClassMateTab? = values().firstOrNull { it.id == id }
    }
}

/**
 * Generic badge model supporting either an unread count bubble or a dot.
 */
sealed interface NavBadge {
    object Dot : NavBadge
    data class Count(val count: Int) : NavBadge
}

/**
 * Quick Action definition for long-press shortcuts.
 */
data class TabQuickAction(
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int? = null,
    val onClick: () -> Unit
)

/**
 * ClassMate Floating Pill Navigation Bar (Jetpack Compose).
 *
 * Features:
 * - Single continuous animated pill indicator physically sliding with spring physics
 * - Indicator hugs both icon AND label as one unified shape
 * - Tabler outline-to-filled morphing with smooth crossfade
 * - Press micro-interaction (scale down to 0.92 on press, spring back)
 * - Light haptic tick on selection
 * - Generic badges (dot or count)
 * - Frosted glass elevation with soft drop shadow and top specular highlight
 * - Auto-hide on scroll with spring exit/entry
 * - Long-press quick actions sheet
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ClassMateFloatingNavigationBar(
    selectedTab: ClassMateTab,
    onTabSelected: (ClassMateTab) -> Unit,
    modifier: Modifier = Modifier,
    visibleTabs: List<ClassMateTab> = ClassMateTab.values().toList(),
    batchTabTitle: String = "Batch",
    badges: Map<ClassMateTab, NavBadge> = emptyMap(),
    isVisible: Boolean = true,
    onQuickActionRequest: ((ClassMateTab) -> List<TabQuickAction>)? = null,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Accent and surface palette
    val activeColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
    val inactiveColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val glassBg = if (isDark) Color(0xD90A1324) else Color(0xE6FFFFFF)
    val glassBorder = if (isDark) Color(0x333B82F6) else Color(0x2B1D4ED8)
    val indicatorColor = if (isDark) Color(0x2E3B82F6) else Color(0x1F1D4ED8)

    // Quick actions sheet state
    var quickActionTab by remember { mutableStateOf<ClassMateTab?>(null) }
    val quickActions = remember(quickActionTab) {
        quickActionTab?.let { onQuickActionRequest?.invoke(it) } ?: emptyList()
    }

    // Coordinates of each tab for continuous pill tracking
    val tabOffsets = remember { mutableStateMapOf<ClassMateTab, Dp>() }
    val tabWidths = remember { mutableStateMapOf<ClassMateTab, Dp>() }

    // Animated sliding indicator
    val targetOffset = tabOffsets[selectedTab] ?: 0.dp
    val targetWidth = tabWidths[selectedTab] ?: 0.dp

    val animatedIndicatorOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "continuousIndicatorOffset"
    )

    val animatedIndicatorWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "continuousIndicatorWidth"
    )

    // Animated auto-hide on scroll
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            initialOffsetY = { it * 2 }
        ),
        exit = slideOutVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            targetOffsetY = { it * 2 }
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(28.dp),
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.6f else 0.16f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(glassBg)
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = if (isDark) listOf(
                            Color.White.copy(alpha = 0.22f),
                            glassBorder
                        ) else listOf(
                            Color.White.copy(alpha = 0.85f),
                            glassBorder
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .height(64.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // 1. Physical Continuous Sliding Indicator Pill hugging BOTH icon AND label
            if (animatedIndicatorWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .offset(x = animatedIndicatorOffset)
                        .width(animatedIndicatorWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(indicatorColor)
                )
            }

            // 2. Tab Items Row - Equal Horizontal Distribution
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleTabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    // Press-scale micro-interaction: scales to ~0.92 on press, springs back on release
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.92f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "pressScale_${tab.name}"
                    )

                    val labelText = if (tab == ClassMateTab.BATCH) batchTabTitle else tab.defaultTitle

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scale(scale)
                            .onGloballyPositioned { coordinates ->
                                val xInParent = with(density) { coordinates.positionInParent().x.toDp() }
                                val widthInParent = with(density) { coordinates.size.width.toDp() }
                                tabOffsets[tab] = xInParent
                                tabWidths[tab] = widthInParent
                            }
                            .clip(RoundedCornerShape(20.dp))
                            .combinedClickable(
                                interactionSource = interactionSource,
                                indication = null, // Custom sliding indicator handles visual feedback
                                onClick = {
                                    if (!isSelected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onTabSelected(tab)
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    quickActionTab = tab
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            // Icon container with Badge
                            Box(contentAlignment = Alignment.TopEnd) {
                                // Outline to Filled Morph with smooth 180ms ease
                                Crossfade(
                                    targetState = isSelected,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "iconMorph_${tab.name}"
                                ) { selected ->
                                    val iconRes = if (selected) tab.filledIcon else tab.outlineIcon
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = labelText,
                                        tint = if (selected) activeColor else inactiveColor,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }

                                // Generic Badges (Dot or Count Bubble)
                                val badge = badges[tab]
                                if (badge != null && !isSelected) {
                                    when (badge) {
                                        is NavBadge.Dot -> {
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = 5.dp, y = (-2).dp)
                                                    .size(7.dp)
                                                    .background(Color(0xFFEF4444), CircleShape)
                                                    .border(1.dp, glassBg, CircleShape)
                                            )
                                        }
                                        is NavBadge.Count -> {
                                            if (badge.count > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .offset(x = 9.dp, y = (-4).dp)
                                                        .height(16.dp)
                                                        .widthIn(min = 16.dp)
                                                        .background(Color(0xFFEF4444), RoundedCornerShape(8.dp))
                                                        .border(1.5.dp, glassBg, RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (badge.count > 99) "99+" else badge.count.toString(),
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        lineHeight = 9.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            // Label: bold + accent when active, regular + muted when inactive
                            Text(
                                text = labelText,
                                color = if (isSelected) activeColor else inactiveColor,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = (-0.1).sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Stretch Addition 2: Long-Press Quick Actions Bottom Sheet
    if (quickActionTab != null && quickActions.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { quickActionTab = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = if (isDark) Color(0xFF0F172A) else Color.White,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "${quickActionTab?.let { if (it == ClassMateTab.BATCH) batchTabTitle else it.defaultTitle }} Shortcuts",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else Color(0xFF0F172A),
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                quickActions.forEach { action ->
                    Surface(
                        onClick = {
                            quickActionTab = null
                            action.onClick()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (action.iconRes != null) {
                                Icon(
                                    painter = painterResource(id = action.iconRes),
                                    contentDescription = null,
                                    tint = activeColor,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .padding(end = 12.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = action.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color.White else Color(0xFF0F172A)
                                )
                                if (action.subtitle != null) {
                                    Text(
                                        text = action.subtitle,
                                        fontSize = 11.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
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
