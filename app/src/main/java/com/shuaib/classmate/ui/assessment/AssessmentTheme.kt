package com.shuaib.classmate.ui.assessment

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shuaib.classmate.R
import com.shuaib.classmate.models.UrgencyLevel

val PlusJakartaBold = FontFamily(
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold)
)

data class CountdownPillStyle(
    val containerColor: Color,
    val textColor: Color,
    val borderColor: Color,
    val iconTint: Color
)

object AssessmentColors {
    // Primary brand accents
    val BrandBlue = Color(0xFF2563EB)
    val BrandIndigo = Color(0xFF6366F1)
    val BrandPurple = Color(0xFF8B5CF6)
    val BrandPink = Color(0xFFEC4899)
    val BrandAmber = Color(0xFFF59E0B)
    val BrandEmerald = Color(0xFF10B981)

    // Gradient for the Hero Elevated Card
    val HeroGradientBorder = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8B5CF6),
            Color(0xFF6366F1),
            Color(0xFF3B82F6)
        )
    )

    fun getCountdownPillStyle(urgency: UrgencyLevel, dark: Boolean): CountdownPillStyle {
        return when (urgency) {
            UrgencyLevel.IMMINENT -> CountdownPillStyle( // < 12h: High Alert Coral / Rose
                containerColor = if (dark) Color(0xFF3B1219) else Color(0xFFFFF1F2),
                textColor = if (dark) Color(0xFFFECDD3) else Color(0xFFE11D48),
                borderColor = if (dark) Color(0xFF9F1239).copy(alpha = 0.6f) else Color(0xFFFDA4AF),
                iconTint = if (dark) Color(0xFFFB7185) else Color(0xFFE11D48)
            )
            UrgencyLevel.SOON -> CountdownPillStyle( // 12-24h: Warm Amber
                containerColor = if (dark) Color(0xFF332005) else Color(0xFFFFFBEB),
                textColor = if (dark) Color(0xFFFDE68A) else Color(0xFFD97706),
                borderColor = if (dark) Color(0xFFB45309).copy(alpha = 0.6f) else Color(0xFFFCD34D),
                iconTint = if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
            )
            UrgencyLevel.UPCOMING -> CountdownPillStyle( // 24-72h: Focused Indigo / Violet
                containerColor = if (dark) Color(0xFF1E1B4B) else Color(0xFFEEF2FF),
                textColor = if (dark) Color(0xFFC7D2FE) else Color(0xFF4F46E5),
                borderColor = if (dark) Color(0xFF4338CA).copy(alpha = 0.5f) else Color(0xFFC7D2FE),
                iconTint = if (dark) Color(0xFF818CF8) else Color(0xFF4F46E5)
            )
            UrgencyLevel.LATER -> CountdownPillStyle( // > 3 days: Calm Slate / Neutral
                containerColor = if (dark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                textColor = if (dark) Color(0xFF94A3B8) else Color(0xFF475569),
                borderColor = if (dark) Color(0xFF334155).copy(alpha = 0.45f) else Color(0xFFE2E8F0),
                iconTint = if (dark) Color(0xFF64748B) else Color(0xFF64748B)
            )
            UrgencyLevel.PAST -> CountdownPillStyle( // Past / Completed: Muted Gray
                containerColor = if (dark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                textColor = if (dark) Color(0xFF64748B) else Color(0xFF94A3B8),
                borderColor = if (dark) Color(0xFF334155).copy(alpha = 0.3f) else Color(0xFFE2E8F0),
                iconTint = if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)
            )
        }
    }

    @Composable
    fun screenBackgroundColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFF0A0F1D) else Color(0xFFF8FAFC)
    }

    @Composable
    fun surfaceColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFF131A2A) else Color(0xFFFFFFFF)
    }

    @Composable
    fun elevatedSurfaceColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFF141D30) else Color(0xFFF8FAFC)
    }

    @Composable
    fun cardBorderColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0x28FFFFFF) else Color(0x14000000)
    }

    @Composable
    fun primaryTextColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    }

    @Composable
    fun secondaryTextColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    }

    @Composable
    fun tertiaryTextColor(dark: Boolean = isSystemInDarkTheme()): Color {
        return if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)
    }
}
