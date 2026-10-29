package com.shuaib.classmate.utils

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R

/**
 * Reusable helper to bind modern empty states and error states across ClassMate screens.
 */
object EmptyStateHelper {

    fun show(
        container: View,
        @DrawableRes iconRes: Int = R.drawable.ic_sparkle,
        title: CharSequence,
        subtitle: CharSequence? = null,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        container.visibility = View.VISIBLE

        val ivIcon = container.findViewById<ImageView>(R.id.ivEmptyIcon)
        val tvTitle = container.findViewById<TextView>(R.id.tvEmptyTitle)
        val tvSubtitle = container.findViewById<TextView>(R.id.tvEmptySubtitle)
        val btnAction = container.findViewById<MaterialButton>(R.id.btnEmptyAction)

        ivIcon?.setImageResource(iconRes)
        tvTitle?.text = title

        if (tvSubtitle != null) {
            if (subtitle.isNullOrBlank()) {
                tvSubtitle.visibility = View.GONE
            } else {
                tvSubtitle.visibility = View.VISIBLE
                tvSubtitle.text = subtitle
            }
        }

        if (btnAction != null) {
            if (actionText.isNullOrBlank() || onAction == null) {
                btnAction.visibility = View.GONE
            } else {
                btnAction.visibility = View.VISIBLE
                btnAction.text = actionText
                btnAction.setOnClickListener {
                    HapticHelper.lightPop(it)
                    onAction()
                }
            }
        }
    }

    fun showError(
        container: View,
        title: CharSequence = "Unable to load data",
        errorMessage: CharSequence? = null,
        onRetry: (() -> Unit)? = null
    ) {
        show(
            container = container,
            iconRes = R.drawable.ic_refresh,
            title = title,
            subtitle = errorMessage ?: "Please check your network connection and try again.",
            actionText = if (onRetry != null) "Try Again" else null,
            onAction = onRetry
        )
    }

    fun hide(container: View) {
        container.visibility = View.GONE
    }
}
