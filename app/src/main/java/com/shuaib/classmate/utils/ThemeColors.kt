package com.shuaib.classmate.utils

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.shuaib.classmate.R

/**
 * Utility to access semantic colors from the theme reliably.
 */
object ThemeColors {

    fun isDark(context: Context?): Boolean {
        if (context == null) return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun get(context: Context, @ColorRes colorRes: Int): Int =
        ContextCompat.getColor(context, colorRes)

    fun bg(context: Context): Int = get(context, R.color.cm_bg)
    fun bgSecondary(context: Context): Int = get(context, R.color.cm_bg_secondary)
    fun surface(context: Context): Int = get(context, R.color.cm_surface)
    fun surfaceElevated(context: Context): Int = get(context, R.color.cm_surface_elevated)
    fun card(context: Context): Int = get(context, R.color.cm_card)
    fun cardAlt(context: Context): Int = get(context, R.color.cm_card_alt)
    fun primary(context: Context): Int = get(context, R.color.cm_primary)
    fun primaryLight(context: Context): Int = get(context, R.color.cm_primary_light)
    fun primaryDark(context: Context): Int = get(context, R.color.cm_primary_dark)
    fun primaryContainer(context: Context): Int = get(context, R.color.cm_primary_container)
    fun primarySoft(context: Context): Int = get(context, R.color.cm_primary_soft)
    fun onPrimary(context: Context): Int = get(context, R.color.cm_on_primary)
    fun textPrimary(context: Context): Int = get(context, R.color.cm_text_primary)
    fun textSecondary(context: Context): Int = get(context, R.color.cm_text_secondary)
    fun textMuted(context: Context): Int = get(context, R.color.cm_text_muted)
    fun textDisabled(context: Context): Int = get(context, R.color.cm_text_disabled)
    fun textInverse(context: Context): Int = get(context, R.color.cm_text_inverse)
    fun border(context: Context): Int = get(context, R.color.cm_border)
    fun borderStrong(context: Context): Int = get(context, R.color.cm_border_strong)
    fun divider(context: Context): Int = get(context, R.color.cm_divider)
    fun success(context: Context): Int = get(context, R.color.cm_success)
    fun warning(context: Context): Int = get(context, R.color.cm_warning)
    fun error(context: Context): Int = get(context, R.color.cm_error)
    fun info(context: Context): Int = get(context, R.color.cm_info)
}
