package com.shuaib.classmate.ui

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
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
    UPCOMING(R.id.nav_upcoming, "Assessments", R.drawable.ic_upcoming_outline, R.drawable.ic_upcoming),
    COURSES(R.id.nav_pdf, "Courses", R.drawable.ic_academic_cap_outline, R.drawable.ic_academic_cap_filled),
    TRANSIT(R.id.nav_transit, "Transit", R.drawable.ic_bus_outline, R.drawable.ic_bus_filled),
    BATCH(R.id.nav_friends, "Batch", R.drawable.ic_batch_friends, R.drawable.ic_batch_friends_filled),
    SETTINGS(R.id.nav_profile, "Profile", R.drawable.ic_profile_outline, R.drawable.ic_profile_modern);

    companion object {
        fun fromId(id: Int): ClassMateTab? = values().firstOrNull { it.id == id }
    }
}

/**
 * Generic badge model supporting either an unread count bubble or a dot.
 */
@androidx.compose.runtime.Immutable
sealed interface NavBadge {
    @androidx.compose.runtime.Immutable
    object Dot : NavBadge
    @androidx.compose.runtime.Immutable
    data class Count(val count: Int) : NavBadge
}

/**
 * Quick Action definition for long-press shortcuts.
 */
@androidx.compose.runtime.Immutable
data class TabQuickAction(
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int? = null,
    val onClick: () -> Unit
)

/**
 * ClassMate Docked Icon-Only Bottom Navigation Bar (Instagram-Style).
 *
 * Characteristics:
 * - Docked edge-to-edge flush with the screen bottom and system navigation bar
 * - Minimal hairline top divider line
 * - Icon-only presentation (zero text labels for a content-centric, clean aesthetic)
 * - Micro-interaction tactile bounce physics on tab touch
 * - Dynamic Profile Photo avatar on Profile tab with active ring indicator
 * - Sub-icon vibrant red dot indicator for unread updates (Notices/Activity)
 * - Auto-hide on scroll with smooth vertical transition
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
    userAvatarUrl: String? = null,
    userName: String = "",
    onTabLongClick: ((ClassMateTab) -> Unit)? = null,
    onQuickActionRequest: ((ClassMateTab) -> List<TabQuickAction>)? = null,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val haptic = LocalHapticFeedback.current

    // Instagram solid monochrome palette
    val activeColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF18181B)
    val inactiveColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    // Instagram solid 100% opaque bottom bar
    val barBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    // Instagram 1px hairline divider shadow line
    val topBorderColor = if (isDark) Color(0xFF262626) else Color(0xFFDBDBDB)
    val badgeDotColor = Color(0xFFFF3040)

    // Quick actions sheet state
    var quickActionTab by remember { mutableStateOf<ClassMateTab?>(null) }
    val quickActions = remember(quickActionTab) {
        quickActionTab?.let { onQuickActionRequest?.invoke(it) } ?: emptyList()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Instagram exact 1px top hairline divider
                    drawLine(
                        color = topBorderColor,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                },
            color = barBg,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBg)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleTabs.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val labelText = if (tab == ClassMateTab.BATCH) batchTabTitle else tab.defaultTitle
                        val interactionSource = remember(tab) { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()

                        // Instagram micro-interaction tactile spring bounce
                        val animatedScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.85f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "igTabScale"
                        )

                        // Instagram faded dock animation for selected tab
                        val dockAlpha by animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            label = "dockAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .combinedClickable(
                                    indication = null,
                                    interactionSource = interactionSource,
                                    onClick = {
                                        com.shuaib.classmate.utils.HapticHelper.selectionTick(view)
                                        onTabSelected(tab)
                                    },
                                    onLongClick = {
                                        view.isHapticFeedbackEnabled = true
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        if (onTabLongClick != null) {
                                            onTabLongClick.invoke(tab)
                                        } else {
                                            quickActionTab = tab
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // Faded dock capsule behind the active tab
                            if (dockAlpha > 0f) {
                                val dockColor = if (isDark) Color(0x22FFFFFF) else Color(0x0F000000)
                                Box(
                                    modifier = Modifier
                                        .width(54.dp)
                                        .height(38.dp)
                                        .scale(animatedScale)
                                        .background(
                                            color = dockColor.copy(alpha = dockColor.alpha * dockAlpha),
                                            shape = RoundedCornerShape(19.dp)
                                        )
                                )
                            }
                            if (tab == ClassMateTab.SETTINGS) {
                                // Profile Tab: Dynamic Instagram Avatar with Active Ring
                                val ringSize = 33.dp
                                val avatarSize = 27.dp
                                Box(
                                    modifier = Modifier
                                        .size(ringSize)
                                        .scale(animatedScale),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        // Active Outer Ring
                                        Box(
                                            modifier = Modifier
                                                .size(ringSize)
                                                .border(
                                                    width = 1.5.dp,
                                                    color = activeColor,
                                                    shape = CircleShape
                                                )
                                        )
                                    }

                                    // Circular Avatar Content
                                    Box(
                                        modifier = Modifier
                                            .size(avatarSize)
                                            .clip(CircleShape)
                                            .background(if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!userAvatarUrl.isNullOrBlank()) {
                                            AndroidView(
                                                factory = { ctx ->
                                                    ImageView(ctx).apply {
                                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                                    }
                                                },
                                                update = { imageView ->
                                                    Glide.with(imageView.context)
                                                        .load(userAvatarUrl)
                                                        .circleCrop()
                                                        .placeholder(R.drawable.bg_circle_avatar_placeholder)
                                                        .error(R.drawable.bg_circle_avatar_placeholder)
                                                        .into(imageView)
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            val initial = userName.trim().take(1).uppercase()
                                            if (initial.isNotBlank()) {
                                                Text(
                                                    text = initial,
                                                    color = if (isSelected) activeColor else inactiveColor,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(id = if (isSelected) tab.filledIcon else tab.outlineIcon),
                                                    contentDescription = labelText,
                                                    tint = if (isSelected) activeColor else inactiveColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Regular Tab: Clean 27dp Icon with Top-Right Instagram Notification Dot
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .scale(animatedScale),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val iconRes = if (isSelected) tab.filledIcon else tab.outlineIcon
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = labelText,
                                        tint = if (isSelected) activeColor else inactiveColor,
                                        modifier = Modifier.size(27.dp)
                                    )

                                    // Instagram Notification Red Dot at top-right
                                    val badge = badges[tab]
                                    if (badge != null && !isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 2.dp, y = (-2).dp)
                                                .size(7.dp)
                                                .border(
                                                    width = 1.5.dp,
                                                    color = barBg,
                                                    shape = CircleShape
                                                )
                                                .background(badgeDotColor, CircleShape)
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

    // Long-Press Quick Actions Bottom Sheet
    if (quickActionTab != null && quickActions.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { quickActionTab = null },
            sheetState = sheetState,
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
