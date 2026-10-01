package com.shuaib.classmate.utils

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.shuaib.classmate.R

object SubjectVisuals {
    data class Visual(
        @DrawableRes val iconRes: Int,
        val startColor: Int,
        val endColor: Int
    )

    private val fallback = Visual(
        R.drawable.ic_subject_other,
        Color.parseColor("#526B96"),
        Color.parseColor("#637DA6")
    )

    fun applyTo(container: View, icon: ImageView, subjectName: String, title: String = "", radiusDp: Float = 12f) {
        val visual = forSubject(subjectName, title)
        val density = container.resources.displayMetrics.density

        container.background = GradientDrawable().apply {
            setColor(ColorUtils.blendARGB(visual.startColor, Color.WHITE, 0.88f))
            cornerRadius = radiusDp * density
        }
        icon.setImageResource(visual.iconRes)
        icon.imageTintList = ColorStateList.valueOf(visual.startColor)
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
            combined.contains("circuit") -> Visual(R.drawable.ic_subject_circuits, Color.parseColor("#4D6A91"), Color.parseColor("#607B9E"))
            combined.contains("program") -> Visual(R.drawable.ic_subject_programming, Color.parseColor("#4C698F"), Color.parseColor("#5F799B"))
            combined.contains("digital") -> Visual(R.drawable.ic_subject_digital, Color.parseColor("#536B94"), Color.parseColor("#657BA0"))
            combined.contains("physics") -> Visual(R.drawable.ic_subject_physics, Color.parseColor("#4D708C"), Color.parseColor("#61839A"))
            combined.contains("stat") -> Visual(R.drawable.ic_subject_statistics, Color.parseColor("#5B6C8D"), Color.parseColor("#6F7D9B"))
            combined.contains("calc") || combined.contains("math") -> Visual(R.drawable.ic_subject_calculus, Color.parseColor("#586A90"), Color.parseColor("#6C7B9D"))
            combined.contains("draw") -> Visual(R.drawable.ic_subject_drawing, Color.parseColor("#527185"), Color.parseColor("#668897"))
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
