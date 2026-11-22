package com.shuaib.classmate.ui.assessment

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R

sealed class AssessmentFilter(val key: String, val label: String, val iconRes: Int) {
    object All : AssessmentFilter("all", "All", R.drawable.ic_filter_all)
    object ClassTest : AssessmentFilter("class_test", "CT", R.drawable.ic_filter_ct)
    object Assignment : AssessmentFilter("assignment", "Assignment", R.drawable.ic_filter_assignment)
    object Quiz : AssessmentFilter("quiz", "Quiz", R.drawable.ic_filter_quiz)
    object LabReport : AssessmentFilter("lab_report", "Lab", R.drawable.ic_filter_lab)
    object Presentation : AssessmentFilter("presentation", "Presentation", R.drawable.ic_filter_presentation)

    companion object {
        val categories: List<AssessmentFilter>
            get() = listOf(ClassTest, Assignment, Quiz, LabReport, Presentation)

        val list: List<AssessmentFilter>
            get() = listOf(All, ClassTest, Assignment, Quiz, LabReport, Presentation)
    }
}

@Composable
fun AssessmentFilterRow(
    selectedFilter: AssessmentFilter,
    onFilterSelected: (AssessmentFilter) -> Unit,
    showPastArchive: Boolean,
    onTogglePastArchive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        // 1. All Chip
        item {
            val isSelected = selectedFilter == AssessmentFilter.All
            val activeColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    activeColor.copy(alpha = if (isDark) 0.28f else 0.16f)
                } else {
                    if (isDark) Color(0xFF141D30) else Color(0xFFF1F5F9)
                },
                animationSpec = tween(180),
                label = "chipBg"
            )
            val borderColor = if (isSelected) {
                activeColor.copy(alpha = 0.6f)
            } else {
                if (isDark) Color(0x28FFFFFF) else Color(0x14000000)
            }
            val textColor = if (isSelected) {
                if (isDark) Color(0xFFE0E7FF) else Color(0xFF312E81)
            } else {
                if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)
            }
            val iconTint = if (isSelected) {
                if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)
            } else {
                if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            }

            Surface(
                onClick = { onFilterSelected(AssessmentFilter.All) },
                shape = RoundedCornerShape(50),
                color = containerColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(
                        painter = painterResource(AssessmentFilter.All.iconRes),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "All",
                        fontSize = 12.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor
                    )
                }
            }
        }

        // 2. Category Chips (CT, Assignment, Quiz, Lab, Presentation)
        items(AssessmentFilter.categories, key = { it.key }) { filter ->
            val isSelected = selectedFilter == filter
            val activeColor = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    activeColor.copy(alpha = if (isDark) 0.28f else 0.16f)
                } else {
                    if (isDark) Color(0xFF141D30) else Color(0xFFF1F5F9)
                },
                animationSpec = tween(180),
                label = "chipBg"
            )
            val borderColor = if (isSelected) {
                activeColor.copy(alpha = 0.6f)
            } else {
                if (isDark) Color(0x28FFFFFF) else Color(0x14000000)
            }
            val textColor = if (isSelected) {
                if (isDark) Color(0xFFE0E7FF) else Color(0xFF312E81)
            } else {
                if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)
            }
            val iconTint = if (isSelected) {
                if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)
            } else {
                if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
            }

            Surface(
                onClick = { onFilterSelected(filter) },
                shape = RoundedCornerShape(50),
                color = containerColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(
                        painter = painterResource(filter.iconRes),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = filter.label,
                        fontSize = 12.5.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor
                    )
                }
            }
        }
    }
}
