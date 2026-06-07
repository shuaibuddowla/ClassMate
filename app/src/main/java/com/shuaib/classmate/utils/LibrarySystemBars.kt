package com.shuaib.classmate.utils

import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat

object LibrarySystemBars {
    fun apply(window: Window) {
        val context = window.decorView.context
        val isDark = ThemeColors.isDark(context)
        window.statusBarColor = ThemeColors.bg(context)
        window.navigationBarColor = ThemeColors.bg(context)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
