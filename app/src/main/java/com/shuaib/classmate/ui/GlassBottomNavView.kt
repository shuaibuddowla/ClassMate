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
    init {
        // Selection is communicated by icon/text color, without an animated fill.
        isItemActiveIndicatorEnabled = false
        itemRippleColor = null
    }
    override fun getMaxItemCount(): Int = 6
    // Retained for existing callers; the checked color state owns selection.
    fun updateActiveTab(tabIndex: Int, totalTabs: Int) = Unit
}
