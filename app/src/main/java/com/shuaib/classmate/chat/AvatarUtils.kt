package com.shuaib.classmate.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import kotlin.math.absoluteValue

object AvatarUtils {
    private val colors = listOf("#3B82F6", "#FBBF24", "#34D399", "#FF4D6D", "#FFB347", "#00CED1")

    fun colorFor(userId: String): Int {
        val index = userId.hashCode().absoluteValue % colors.size
        return Color.parseColor(colors[index])
    }

    fun circle(userId: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorFor(userId))
        }
    }

    fun bind(
        imageView: ImageView,
        letterView: TextView?,
        userId: String,
        name: String,
        avatarUrl: String?,
        placeholder: Int = R.drawable.ic_default_avatar
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            imageView.visibility = View.VISIBLE
            letterView?.visibility = View.GONE
            Glide.with(imageView)
                .load(avatarUrl)
                .placeholder(placeholder)
                .circleCrop()
                .into(imageView)
        } else {
            if (letterView != null) {
                imageView.visibility = View.GONE
                letterView.visibility = View.VISIBLE
                val cleanName = name.trim().ifBlank { "ClassMate" }
                val letter = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "C"
                letterView.text = letter
                letterView.background = circle(userId)
            } else {
                imageView.visibility = View.VISIBLE
                letterView?.visibility = View.GONE
                val cleanName = name.trim().ifBlank { "ClassMate" }
                val encodedName = android.net.Uri.encode(cleanName)
                val fallbackUrl = "https://api.dicebear.com/7.x/initials/png?seed=$encodedName&radius=50"
                Glide.with(imageView)
                    .load(fallbackUrl)
                    .placeholder(placeholder)
                    .circleCrop()
                    .into(imageView)
            }
        }
    }
}
