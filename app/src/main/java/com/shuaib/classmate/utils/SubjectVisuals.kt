package com.shuaib.classmate.utils

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.shuaib.classmate.R

object SubjectVisuals {
    data class Visual(
        @DrawableRes val iconRes: Int,
        val startColor: Int,
        val endColor: Int
    )

    private val fallback = Visual(
        R.drawable.ic_subject_other,
        Color.parseColor("#3B82F6"),
        Color.parseColor("#FBBF24")
    )

    fun applyTo(container: View, icon: ImageView, subjectName: String, title: String = "", radiusDp: Float = 12f) {
        val visual = forSubject(subjectName, title)
        val density = container.resources.displayMetrics.density

        container.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(visual.startColor, visual.endColor)
        ).apply {
            cornerRadius = radiusDp * density
        }
        icon.setImageResource(visual.iconRes)
    }

    fun applyToSubjectText(textView: TextView, subjectName: String, title: String = "") {
        val visual = forSubject(subjectName, title)
        textView.setTextColor(visual.startColor)
        
        // If subject is generic, try to show a better one derived from title
        val displaySubject = if (subjectName == "Other Document" || subjectName.isBlank()) {
            guessSubjectName(title)
        } else {
            subjectName
        }
        textView.text = displaySubject
    }

    fun forSubject(subjectName: String, title: String = ""): Visual {
        val combined = (subjectName + " " + title).lowercase()
        
        return when {
            combined.contains("circuit") -> Visual(R.drawable.ic_subject_circuits, Color.parseColor("#20C7A7"), Color.parseColor("#3277FF"))
            combined.contains("program") -> Visual(R.drawable.ic_subject_programming, Color.parseColor("#3B82F6"), Color.parseColor("#22C55E"))
            combined.contains("digital") -> Visual(R.drawable.ic_subject_digital, Color.parseColor("#6366F1"), Color.parseColor("#06B6D4"))
            combined.contains("physics") -> Visual(R.drawable.ic_subject_physics, Color.parseColor("#0EA5E9"), Color.parseColor("#A855F7"))
            combined.contains("stat") -> Visual(R.drawable.ic_subject_statistics, Color.parseColor("#F97316"), Color.parseColor("#EC4899") )
            combined.contains("calc") || combined.contains("math") -> Visual(R.drawable.ic_subject_calculus, Color.parseColor("#A855F7"), Color.parseColor("#3B82F6"))
            combined.contains("draw") -> Visual(R.drawable.ic_subject_drawing, Color.parseColor("#F59E0B"), Color.parseColor("#10B981"))
            else -> fallback
        }
    }

    private fun guessSubjectName(title: String): String {
        val lower = title.lowercase()
        return when {
            lower.contains("circuit") -> "Circuits"
            lower.contains("program") -> "Programming"
            lower.contains("digital") -> "Digital Electronics"
            lower.contains("physics") -> "Physics"
            lower.contains("stat") -> "Statistics"
            lower.contains("calc") || lower.contains("math") -> "Calculus"
            lower.contains("draw") -> "Engineering Drawing"
            else -> "Resource"
        }
    }
}
