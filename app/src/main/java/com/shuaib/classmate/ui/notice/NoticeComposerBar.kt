package com.shuaib.classmate.ui.notice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.ui.assessment.PlusJakartaBold

/**
 * Premium dual-frequency harmonic audio waveform visualizer for real-time voice recording.
 */
@Composable
fun PremiumVoiceRecordingWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 9,
    accentColor: Color = Color(0xFFEF4444)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audioWaveform")

    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Harmonic soundwave bars
    val phaseOffsets = listOf(0.0f, 0.7f, 1.4f, 2.1f, 2.8f, 2.2f, 1.5f, 0.8f, 0.2f)
    val minHeights = listOf(6f, 8f, 10f, 12f, 14f, 12f, 10f, 8f, 6f)
    val maxHeights = listOf(14f, 20f, 25f, 28f, 26f, 24f, 20f, 16f, 12f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val phase = phaseOffsets.getOrElse(i) { i * 0.6f }
            val minH = minHeights.getOrElse(i) { 8f }
            val maxH = maxHeights.getOrElse(i) { 24f }

            val sin1 = kotlin.math.sin(animProgress + phase)
            val sin2 = kotlin.math.sin(animProgress * 1.6f + phase * 1.2f)
            val normalized = ((sin1 * 0.65f + sin2 * 0.35f + 1f) / 2f).coerceIn(0f, 1f)
            val currentH = (minH + (maxH - minH) * normalized).dp

            Box(
                modifier = Modifier
                    .width(3.2.dp)
                    .height(currentH)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFFB7185),
                                accentColor,
                                Color(0xFFE11D48)
                            )
                        )
                    )
            )
        }
    }
}

/**
 * Pulsing glowing core with breathing ambient halo ring for active recording.
 */
@Composable
fun GlowingRecordingDot(
    modifier: Modifier = Modifier,
    dotColor: Color = Color(0xFFEF4444)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotGlow")

    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )

    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ambient outer breathing aura
        Box(
            modifier = Modifier
                .size(9.dp)
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                    alpha = haloAlpha
                }
                .clip(CircleShape)
                .background(dotColor)
        )
        // Inner solid glowing core
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
fun NoticeComposerBar(
    messageText: String,
    onMessageChange: (String) -> Unit,
    voiceState: VoiceRecordingState,
    voiceTimerText: String,
    onAttachDoc: () -> Unit,
    onAttachPhoto: () -> Unit,
    onVoiceClick: () -> Unit,
    onSendClick: () -> Unit,
    isSubmitting: Boolean,
    hasAttachments: Boolean = false,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val primaryColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)
    val accentRed = Color(0xFFEF4444)
    val hasContent = messageText.isNotBlank() || hasAttachments

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
    ) {
        // WhatsApp-Style Row: [Elongated Input Bubble] + [Circular Action FAB]
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Main Input Bubble
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF),
                border = BorderStroke(
                    width = if (voiceState == VoiceRecordingState.RECORDING || hasContent) 1.25.dp else 1.dp,
                    color = when {
                        voiceState == VoiceRecordingState.RECORDING -> accentRed.copy(alpha = 0.7f)
                        hasContent -> primaryColor.copy(alpha = if (isDark) 0.55f else 0.4f)
                        isDark -> Color(0x2EFFFFFF)
                        else -> Color(0x1A0F172A)
                    }
                ),
                shadowElevation = if (isDark) 4.dp else 2.dp,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Left / Middle Content: Text Field OR Voice State
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        when (voiceState) {
                            VoiceRecordingState.IDLE -> {
                                BasicTextField(
                                    value = messageText,
                                    onValueChange = onMessageChange,
                                    textStyle = TextStyle(
                                        fontSize = 15.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.Normal,
                                        color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                                        lineHeight = 21.sp
                                    ),
                                    cursorBrush = SolidColor(primaryColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 24.dp, max = 130.dp),
                                    decorationBox = { innerTextField ->
                                        if (messageText.isEmpty()) {
                                            Text(
                                                text = if (hasAttachments) "Add a caption (optional)…" else "Notice message · English, Banglish…",
                                                fontSize = 14.5.sp,
                                                fontFamily = PlusJakartaBold,
                                                fontWeight = FontWeight.Normal,
                                                color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }

                            VoiceRecordingState.RECORDING -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    GlowingRecordingDot(dotColor = accentRed)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = voiceTimerText,
                                        fontSize = 14.5.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.Bold,
                                        color = accentRed
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    // Smooth premium dynamic soundwave animation
                                    PremiumVoiceRecordingWaveform(
                                        barCount = 9,
                                        accentColor = accentRed
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "Recording…",
                                        fontSize = 12.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }

                            VoiceRecordingState.TRANSCRIBING -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = primaryColor,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "✨ Transcribing with Gemini…",
                                        fontSize = 13.5.sp,
                                        fontFamily = PlusJakartaBold,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    )
                                }
                            }
                        }
                    }

                    // Inside Bubble Action Icons (📎 Document & 📷 Photos, plus 🎙️ if text present)
                    if (voiceState == VoiceRecordingState.IDLE) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            IconButton(
                                onClick = onAttachDoc,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_attach),
                                    contentDescription = "Attach PDF",
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = onAttachPhoto,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_gallery_photo),
                                    contentDescription = "Attach Photos",
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // If user already typed text or attached media, keep mic icon 1-tap accessible
                            if (hasContent) {
                                IconButton(
                                    onClick = onVoiceClick,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_mic),
                                        contentDescription = "Voice Dictation",
                                        tint = primaryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Standalone WhatsApp-Style Action FAB (48dp circle with ambient aura when recording)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp)
            ) {
                // Breathing ambient glow ring when recording
                if (voiceState == VoiceRecordingState.RECORDING) {
                    val haloTransition = rememberInfiniteTransition(label = "fabHalo")
                    val haloScale by haloTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.34f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "fabScale"
                    )
                    val haloAlpha by haloTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 0.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "fabAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                scaleX = haloScale
                                scaleY = haloScale
                                alpha = haloAlpha
                            }
                            .clip(CircleShape)
                            .background(accentRed)
                    )
                }

                Surface(
                    onClick = {
                        when {
                            voiceState == VoiceRecordingState.RECORDING -> onVoiceClick()
                            hasContent -> if (!isSubmitting) onSendClick()
                            else -> onVoiceClick()
                        }
                    },
                    shape = CircleShape,
                    color = when {
                        voiceState == VoiceRecordingState.RECORDING -> accentRed
                        else -> primaryColor
                    },
                    shadowElevation = if (isDark) 4.dp else 2.dp,
                    enabled = !isSubmitting,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            isSubmitting -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                            voiceState == VoiceRecordingState.RECORDING -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_voice_stop),
                                    contentDescription = "Stop Recording",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            hasContent -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_send),
                                    contentDescription = "Publish Notice",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_mic),
                                    contentDescription = "Record Voice",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
