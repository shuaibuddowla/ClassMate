package com.shuaib.classmate.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Crash-resilient LinearLayoutManager that catches transient InconsistencyDetectedException
 * thrown by RecyclerView when items/pages are appended while the user is actively flinging/scrolling.
 */
open class SafeLinearLayoutManager : LinearLayoutManager {

    constructor(context: Context) : super(context)
    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.w("SafeLinearLayoutManager", "Suppressed layout inconsistency: ${e.message}")
        } catch (e: Exception) {
            Log.w("SafeLinearLayoutManager", "Suppressed layout error: ${e.message}")
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollVerticallyBy(dy, recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            Log.w("SafeLinearLayoutManager", "Suppressed scroll inconsistency: ${e.message}")
            0
        } catch (e: Exception) {
            Log.w("SafeLinearLayoutManager", "Suppressed scroll error: ${e.message}")
            0
        }
    }
}
