package com.shuaib.classmate.ui.assessment

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.models.UrgencyLevel

@Composable
fun AssessmentHeroCard(
    assessment: AssessmentItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val type = assessment.assessmentType
    val typeColor = Color(type.colorHex)
    val urgency = assessment.getUrgencyLevel()
    val pillStyle = AssessmentColors.getCountdownPillStyle(urgency, isDark)

    // Pulsing dot for NEXT UP badge
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val heroBgGradient = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF141C2E),
                Color(0xFF18223B),
                Color(0xFF0F172A)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF8FAFC),
                Color(0xFFEEF2FF)
            )
        )
    }

    val heroBorderBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF818CF8).copy(alpha = 0.65f),
                Color(0xFFC7D2FE).copy(alpha = 0.25f),
                Color(0xFF6366F1).copy(alpha = 0.45f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF818CF8).copy(alpha = 0.60f),
                Color(0xFFC7D2FE).copy(alpha = 0.40f),
                Color(0xFF6366F1).copy(alpha = 0.40f)
            )
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 8.dp else 4.dp,
                shape = RoundedCornerShape(22.dp),
                spotColor = if (isDark) Color(0x604F46E5) else Color(0x254F46E5)
            )
            .clip(RoundedCornerShape(22.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        border = BorderStroke(1.2.dp, heroBorderBrush)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBgGradient)
        ) {
            // Ambient glowing radial orb in top right corner for subtle depth
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.TopEnd)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (isDark) Color(0xFF6366F1) else Color(0xFF818CF8)).copy(alpha = if (isDark) 0.18f else 0.14f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Top Row: NEXT UP Badge + Standardized Type Badge (vector icon + shortName)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // NEXT UP pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF6366F1).copy(alpha = if (isDark) 0.25f else 0.14f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (urgency == UrgencyLevel.IMMINENT) Color(0xFFE11D48) else Color(0xFF818CF8).copy(alpha = pulseAlpha)
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NEXT UP",
                            fontSize = 11.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            color = if (isDark) Color(0xFFA5B4FC) else Color(0xFF4338CA)
                        )
                    }

                    // Assessment Type Badge (standardized vector icon + shortName)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = typeColor.copy(alpha = if (isDark) 0.22f else 0.12f),
                        border = BorderStroke(1.dp, typeColor.copy(alpha = if (isDark) 0.5f else 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(type.iconRes),
                                contentDescription = null,
                                tint = typeColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = type.shortName,
                                fontSize = 11.5.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = typeColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(13.dp))

                val courseLabel = when {
                    assessment.courseCode.isNotBlank() && assessment.courseTitle.isNotBlank() ->
                        "${assessment.courseCode} · ${assessment.courseTitle}"
                    assessment.courseCode.isNotBlank() -> assessment.courseCode
                    assessment.courseTitle.isNotBlank() -> assessment.courseTitle
                    else -> "Academic Assessment"
                }

                // Course Code & Name (High contrast crisp typography)
                Text(
                    text = courseLabel,
                    fontSize = 12.5.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFC7D2FE) else Color(0xFF312E81),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Assessment Title (Darkened for maximum readability)
                Text(
                    text = assessment.title.ifBlank { "Assessment" },
                    fontSize = 18.5.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                    lineHeight = 23.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(13.dp))

                // Metadata Line 1: Relative Date & Time (e.g. "Today · 03:15 PM" or "Tomorrow · 02:30 PM")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = null,
                        tint = if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = assessment.getFormattedRelativeDateTime(),
                        fontSize = 12.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                    )
                }

                // Metadata Line 2: Room / Venue (Consistent layout stacked below date/time)
                val formattedRoom = assessment.getFormattedRoom()
                if (formattedRoom.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_location),
                            contentDescription = null,
                            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formattedRoom,
                            fontSize = 12.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                // Bottom Row: Urgency-coded Countdown Pill (Left) + Actionable CTA (Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Urgency-coded Countdown Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = pillStyle.containerColor,
                        border = BorderStroke(1.dp, pillStyle.borderColor)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_clock),
                                contentDescription = null,
                                tint = pillStyle.iconTint,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = assessment.getFormattedCountdown(),
                                fontSize = 11.5.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = pillStyle.textColor
                            )
                        }
                    }

                    // Actionable CTA Button: "View Syllabus →" or "Open Details →"
                    val actionLabel = if (assessment.syllabus.isNotBlank()) "View Syllabus →" else "Open Details →"
                    Surface(
                        onClick = { onClick() },
                        shape = RoundedCornerShape(8.dp),
                        color = (if (isDark) Color(0xFF6366F1) else Color(0xFF4F46E5)).copy(alpha = if (isDark) 0.20f else 0.12f),
                        border = BorderStroke(
                            1.dp,
                            (if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1)).copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = actionLabel,
                            fontSize = 12.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFC7D2FE) else Color(0xFF4338CA),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
