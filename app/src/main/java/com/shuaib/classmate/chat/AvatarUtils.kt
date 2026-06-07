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
            imageView.setImageDrawable(null)
            imageView.background = circle(userId.ifBlank { name })
            imageView.visibility = View.VISIBLE
            letterView?.apply {
                visibility = View.VISIBLE
                background = circle(userId.ifBlank { name })
                text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
        }
    }
}
