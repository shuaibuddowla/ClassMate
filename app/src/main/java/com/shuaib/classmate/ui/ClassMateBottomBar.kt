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
    val haptic = LocalHapticFeedback.current

    // ClassMate brand accent palette: Vibrant primary blue for active tabs
    val activeColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
    val inactiveColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B)
    // Real-time backdrop blur is provided by FrostedGlassBackdropView directly underneath
    val barBg = Color.Transparent
    val topBorderColor = if (isDark) Color(0x338696A0) else Color(0x1F000000)
    val badgeDotColor = Color(0xFFE11D48)

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
                    // Minimal, clean top border without any glowing gradient or lighting up in dark mode
                    drawLine(
                        color = if (isDark) Color(0x18FFFFFF) else Color(0x140F172A),
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
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleTabs.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val labelText = if (tab == ClassMateTab.BATCH) batchTabTitle else tab.defaultTitle
                        val interactionSource = remember(tab) { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()

                        // Micro-interaction tactile bounce
                        val animatedScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.86f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "tabScale"
                        )

                        // Extra fast capsular outliner animation (40ms snappy response)
                        val capsuleAlpha by animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = tween(durationMillis = 40, easing = FastOutSlowInEasing),
                            label = "capsuleAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .scale(animatedScale)
                                .combinedClickable(
                                    indication = null,
                                    interactionSource = interactionSource,
                                    onClick = {
                                        if (!isSelected) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onTabSelected(tab)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (onTabLongClick != null) {
                                            onTabLongClick.invoke(tab)
                                        } else {
                                            quickActionTab = tab
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val capsuleShape = RoundedCornerShape(percent = 50)

                            val capsuleBgColor = if (isSelected) {
                                if (isDark) Color(0xFF1B3354) else Color(0xFFE0EDFE)
                            } else {
                                if (isDark) Color(0xFF131D24) else Color(0xFFFFFFFF)
                            }

                            val capsuleBorderColor = if (isSelected) {
                                if (isDark) Color(0x9960A5FA) else Color(0x661D4ED8)
                            } else {
                                if (isDark) Color(0x2BFFFFFF) else Color(0x1F0F172A)
                            }

                            // Clean Water Drop Elevated Capsule with zero glitter
                            Box(
                                modifier = Modifier
                                    .shadow(
                                        elevation = if (isSelected) 2.5.dp else 1.5.dp,
                                        shape = capsuleShape,
                                        clip = false,
                                        ambientColor = if (isDark) Color(0x66000000) else Color(0x1F0F172A),
                                        spotColor = if (isSelected) {
                                            if (isDark) Color(0x403B82F6) else Color(0x331D4ED8)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .width(56.dp)
                                    .height(36.dp)
                                    .clip(capsuleShape)
                                    .background(capsuleBgColor)
                                    .border(
                                        width = 1.dp,
                                        color = capsuleBorderColor,
                                        shape = capsuleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                    if (tab == ClassMateTab.SETTINGS) {
                                        // Profile Tab: Dynamic Instagram Avatar with Active Ring
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            if (isSelected) {
                                                // Active Outer Ring
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
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
                                                    .size(23.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7))
                                                    .border(
                                                        width = if (isSelected) 1.dp else 0.5.dp,
                                                        color = if (isSelected) barBg else (if (isDark) Color(0x33FFFFFF) else Color(0x1F000000)),
                                                        shape = CircleShape
                                                    ),
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
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            textAlign = TextAlign.Center
                                                        )
                                                    } else {
                                                        Icon(
                                                            painter = painterResource(id = if (isSelected) tab.filledIcon else tab.outlineIcon),
                                                            contentDescription = labelText,
                                                            tint = if (isSelected) activeColor else inactiveColor,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Regular Tab: Clean 24dp Icon (Outline vs Filled)
                                        val iconRes = if (isSelected) tab.filledIcon else tab.outlineIcon
                                        Icon(
                                            painter = painterResource(id = iconRes),
                                            contentDescription = labelText,
                                            tint = if (isSelected) activeColor else inactiveColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                            // Instagram-Style Notification Dot directly below without displacing vertical center
                            val badge = badges[tab]
                            if (badge != null && !isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                        .size(4.5.dp)
                                        .background(badgeDotColor, CircleShape)
                                )
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
