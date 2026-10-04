package com.shuaib.classmate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs

class ClassMateSwipeTabsHost @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context,attrs) {
    var onSwipe: ((Int) -> Unit)? = null
    var canSwipe: ((Int) -> Boolean)? = null
    private var startX=0f; private var startY=0f; private var horizontal=false; private var blocked=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private fun nativeGesture(view: View, x: Float, y: Float): Boolean {
        if(view.visibility!=VISIBLE || x<0 || y<0 || x>view.width || y>view.height) return false
        if(view is EditText || view is HorizontalScrollView || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) return true
        if(view is ViewGroup) for(i in view.childCount-1 downTo 0) {
            val child=view.getChildAt(i)
            if(nativeGesture(child,x+view.scrollX-child.left,y+view.scrollY-child.top)) return true
        }
        return false
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX=event.x; startY=event.y; horizontal=false
                val accessibility=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                blocked=accessibility?.isTouchExplorationEnabled==true || nativeGesture(this,event.x,event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> blocked=true
            MotionEvent.ACTION_MOVE -> if(!blocked) {
                val dx=event.x-startX; val dy=event.y-startY
                if(abs(dy)>slop*2 && abs(dy)>abs(dx)) blocked=true
                else if(abs(dx)>slop*2 && abs(dx)>abs(dy)*1.5f) {
                    val direction=if(dx<0) 1 else -1
                    horizontal=canSwipe?.invoke(direction)==true
                    if(horizontal) { parent?.requestDisallowInterceptTouchEvent(true); return true }
                }
            }
        }
        return horizontal
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!horizontal) return !blocked || super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val dx=event.x-startX
            if(abs(dx)>36*resources.displayMetrics.density) onSwipe?.invoke(if(dx<0) 1 else -1)
            horizontal=false; parent?.requestDisallowInterceptTouchEvent(false)
        } else if(event.actionMasked==MotionEvent.ACTION_CANCEL) { horizontal=false; parent?.requestDisallowInterceptTouchEvent(false) }
        return true
    }
}
