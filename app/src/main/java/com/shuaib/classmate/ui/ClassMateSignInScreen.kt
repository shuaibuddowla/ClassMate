package com.shuaib.classmate.ui

import android.widget.LinearLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shuaib.classmate.R

private val InkPrimary = Color(0xFF0F172A)
private val InkSecondary = Color(0xFF475569)
private val InkMuted = Color(0xFF64748B)
private val BrandBlue = Color(0xFF2563EB)
private val BrandBlueDark = Color(0xFF1D4ED8)
private val CardBorder = Color(0xFFE2E8F0)
private val ToggleTrackBg = Color(0xFFEEF3FA)
private val RequirementBg = Color(0xFFF4F7FC)
private val ErrorBg = Color(0xFFFEF2F2)
private val ErrorBorder = Color(0xFFFECACA)
private val ErrorText = Color(0xFFDC2626)

@Composable
fun ClassMateSignInScreen(
    isFaculty: Boolean,
    isLoading: Boolean,
    statusMessage: String,
    actionsContainer: LinearLayout,
    onRoleSelected: (Boolean) -> Unit,
    onGoogleSignIn: () -> Unit,
    onAboutClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onTermsClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val infiniteTransition = rememberInfiniteTransition(label = "hero_breathe")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F8FE))
    ) {
        // Layer 1: Ambient gradient background
        Image(
            painter = painterResource(id = R.drawable.bg_signin_ambient),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: Bottom campus illustration at true 412:160 aspect ratio (no stretching)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_campus_illustration),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(412f / 160f)
                    .alpha(0.55f)
            )
            // Soft top fade so the illustration blends smoothly into the ambient backdrop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF3F7FE),
                                Color(0x00F3F7FE)
                            )
                        )
                    )
            )
        }

        // Layer 3: Scrollable, vertically balanced foreground content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.8f, fill = true))

            // Hero Logo + Accent Rays + Brand Header
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(420)) + expandVertically(tween(420))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(width = 200.dp, height = 112.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.bg_hero_accent_rays),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Image(
                            painter = painterResource(id = R.drawable.ic_classmate_logo),
                            contentDescription = "ClassMate",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(88.dp)
                                .scale(logoScale)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "ClassMate",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "Your university academic companion",
                        fontSize = 13.5.sp,
                        color = InkMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Main Sign-In Card
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                border = BorderStroke(1.dp, CardBorder),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = Color(0x1F0F172A),
                        spotColor = Color(0x1E1D4ED8)
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = "Sign in to continue",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = InkPrimary,
                        letterSpacing = (-0.2).sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Compact Centered Role Toggle (intentionally narrower than the Google CTA)
                    RoleSegmentedToggle(
                        isFaculty = isFaculty,
                        enabled = !isLoading,
                        onSelect = { faculty ->
                            if (faculty != isFaculty) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRoleSelected(faculty)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Full-Width Primary Google Sign-In CTA
                    GoogleContinueButton(
                        isLoading = isLoading,
                        onClick = {
                            if (!isLoading) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onGoogleSignIn()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Contextual Account Requirement Pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = RequirementBg,
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_lock),
                                contentDescription = null,
                                tint = InkMuted,
                                modifier = Modifier.size(13.5.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            AnimatedContent(
                                targetState = isFaculty,
                                transitionSpec = {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                },
                                label = "requirement_text"
                            ) { faculty ->
                                Text(
                                    text = if (faculty) {
                                        "Requires verified faculty Google account"
                                    } else {
                                        "Requires @mbstu.ac.bd Google account"
                                    },
                                    fontSize = 12.5.sp,
                                    color = InkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = CardBorder
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Footer Policy Links
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FooterLink(text = "About", onClick = onAboutClick)
                        FooterDot()
                        FooterLink(text = "Privacy Policy", onClick = onPrivacyClick)
                        FooterDot()
                        FooterLink(text = "Terms of Service", onClick = onTermsClick)
                    }
                }
            }

            // Status Message (Errors or Info)
            val trimmedStatus = statusMessage.trim()
            val showStatus = trimmedStatus.isNotEmpty() &&
                !trimmedStatus.contains("Students: use university email", ignoreCase = true) &&
                !trimmedStatus.contains("Teachers: use your approved", ignoreCase = true)
            val isError = trimmedStatus.contains("fail", ignoreCase = true) ||
                trimmedStatus.contains("error", ignoreCase = true) ||
                trimmedStatus.contains("missing", ignoreCase = true) ||
                trimmedStatus.contains("requires", ignoreCase = true)

            AnimatedVisibility(
                visible = showStatus,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isError) ErrorBg else Color(0xE6FFFFFF),
                    border = BorderStroke(1.dp, if (isError) ErrorBorder else CardBorder),
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = trimmedStatus,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = if (isError) ErrorText else InkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Recovery / Secondary Actions Container (populated dynamically by ClassMateAuthActivity if needed)
            AndroidView(
                factory = { actionsContainer },
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.weight(1.1f, fill = true))
        }
    }
}

@Composable
private fun RoleSegmentedToggle(
    isFaculty: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .width(216.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(ToggleTrackBg)
            .border(1.dp, CardBorder, RoundedCornerShape(21.dp))
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (isFaculty) segmentWidth else 0.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "role_indicator_offset"
        )

        // Sliding active white pill
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x180F172A))
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            RoleSegmentLabel(
                label = "Student",
                selected = !isFaculty,
                enabled = enabled,
                onClick = { onSelect(false) },
                modifier = Modifier.weight(1f)
            )
            RoleSegmentLabel(
                label = "Faculty",
                selected = isFaculty,
                enabled = enabled,
                onClick = { onSelect(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RoleSegmentLabel(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Text(
            text = label,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) BrandBlueDark else InkMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun GoogleContinueButton(
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !isLoading) 0.975f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "google_btn_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = Color(0x331D4ED8),
                spotColor = Color(0x401D4ED8)
            )
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(BrandBlue, BrandBlueDark)
                )
            )
            .clickable(
                enabled = !isLoading,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.6.dp,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_google),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Continue with Google",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FooterLink(
    text: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = InkMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

@Composable
private fun FooterDot() {
    Text(
        text = "•",
        fontSize = 10.sp,
        color = Color(0xFF94A3B8),
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}
