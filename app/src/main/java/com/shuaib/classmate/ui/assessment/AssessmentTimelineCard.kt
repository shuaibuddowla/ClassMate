package com.shuaib.classmate.ui.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.models.AssessmentItem

@Composable
fun AssessmentTimelineCard(
    assessment: AssessmentItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val type = assessment.assessmentType
    val typeColor = Color(type.colorHex)
    val urgency = assessment.getUrgencyLevel()
    val pillStyle = AssessmentColors.getCountdownPillStyle(urgency, isDark)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFFFFFFF)
        ),
        border = BorderStroke(
            1.dp,
            if (isDark) Color(0x28FFFFFF) else Color(0x14000000)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 2.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Course code + Standardized Type badge (vector icon + shortName)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${assessment.courseCode.ifBlank { "Course" }} · ${assessment.courseTitle}",
                    fontSize = 12.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFA5B4FC) else Color(0xFF4338CA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(50),
                    color = typeColor.copy(alpha = if (isDark) 0.22f else 0.12f),
                    border = BorderStroke(1.dp, typeColor.copy(alpha = if (isDark) 0.5f else 0.35f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            painter = painterResource(type.iconRes),
                            contentDescription = null,
                            tint = typeColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = type.shortName,
                            fontSize = 11.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = typeColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Assessment Title
            Text(
                text = assessment.title,
                fontSize = 16.5.sp,
                fontFamily = PlusJakartaBold,
                fontWeight = FontWeight.Bold,
                color = AssessmentColors.primaryTextColor(isDark),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata Line 1: Relative Date & Time (Exact layout parity with hero card)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_calendar),
                    contentDescription = null,
                    tint = if (isDark) Color(0xFFA5B4FC) else Color(0xFF6366F1),
                    modifier = Modifier.size(13.5.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = assessment.getFormattedRelativeDateTime(),
                    fontSize = 12.sp,
                    fontFamily = PlusJakartaBold,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
                )
            }

            // Metadata Line 2: Room / Venue (Stacked identically)
            val formattedRoom = assessment.getFormattedRoom()
            if (formattedRoom.isNotBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_location),
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.size(13.5.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formattedRoom,
                        fontSize = 12.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Row: Urgency-Coded Countdown Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = pillStyle.containerColor,
                    border = BorderStroke(1.dp, pillStyle.borderColor)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = pillStyle.iconTint,
                            modifier = Modifier.size(11.5.dp)
                        )
                        Spacer(modifier = Modifier.width(4.5.dp))
                        Text(
                            text = assessment.getFormattedCountdown(),
                            fontSize = 11.sp,
                            fontFamily = PlusJakartaBold,
                            fontWeight = FontWeight.Bold,
                            color = pillStyle.textColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
