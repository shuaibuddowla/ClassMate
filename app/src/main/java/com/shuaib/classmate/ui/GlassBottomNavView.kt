package com.shuaib.classmate.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Native Material navigation keeps icons sharp and avoids continuous background redraws. */
class GlassBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BottomNavigationView(context, attrs, defStyleAttr) {
    // Retained for existing callers; Material's selected indicator owns the active state.
    fun updateActiveTab(tabIndex: Int, totalTabs: Int) = Unit
}
