/*
 * com/shuaib/classmate/ui/GlassBottomNavView.kt
 *
 * A BottomNavigationView subclass that:
 *   - Applies RenderEffect backdrop blur behind the nav bar on Android 12+ (API 31)
 *   - Draws a glass shine highlight across the top edge
 *   - Pulses a soft blue glow under the active tab icon
 *   - Falls back to the existing glass drawable on older API versions
 */
package com.shuaib.classmate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shuaib.classmate.R

class GlassBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BottomNavigationView(context, attrs, defStyleAttr) {

    // Corner radii for the pill shape
    private val topCorner = dpToPx(26f)
    private val bottomCorner = dpToPx(22f)

    // Paints
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Glow animation
    private var glowAlpha = 0f
    private var glowAnimator: ValueAnimator? = null
    private var activeTabCenterX = -1f

    // Path for clipping
    private val clipPath = Path()
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startGlowPulse()
        // Backdrop blur is disabled on the view directly to keep text and icons 100% sharp.
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowAnimator?.cancel()
        BlurHelper.clearBlur(this)
    }

    private fun applyBlurIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurHelper.applyBackdropBlur(this, radiusX = 24f, radiusY = 24f)
        }
    }

    private fun startGlowPulse() {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                glowAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Call this when the selected tab changes to update the glow position. */
    fun updateActiveTab(tabIndex: Int, totalTabs: Int) {
        if (width == 0) return
        val tabWidth = width.toFloat() / totalTabs
        activeTabCenterX = tabWidth * tabIndex + tabWidth / 2f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        // Build the pill clip path
        clipPath.reset()
        val radii = floatArrayOf(
            topCorner, topCorner,   // top-left
            topCorner, topCorner,   // top-right
            bottomCorner, bottomCorner, // bottom-right
            bottomCorner, bottomCorner  // bottom-left
        )
        clipPath.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // 1. Frosted glass base background (dynamically resolved nav capsule color)
        val glassColor = ContextCompat.getColor(context, R.color.cm_bottom_nav_bg)
        glassPaint.color = glassColor
        canvas.drawRoundRect(rect, topCorner, topCorner, glassPaint)

        // 2. Top gradient shimmer (frosted glass effect)
        shimmerPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            Color.argb(22, 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, topCorner, topCorner, shimmerPaint)

        // 3. Pulsing glow spot under active tab (matches the active theme primary color)
        if (activeTabCenterX > 0) {
            val primaryColor = ContextCompat.getColor(context, R.color.cm_primary)
            val r = Color.red(primaryColor)
            val g = Color.green(primaryColor)
            val b = Color.blue(primaryColor)
            
            val glowRadius = dpToPx(36f)
            val alpha = (glowAlpha * 48).toInt().coerceIn(0, 255)
            glowPaint.shader = RadialGradient(
                activeTabCenterX, h * 0.3f,
                glowRadius,
                Color.argb(alpha, r, g, b),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(activeTabCenterX, h * 0.3f, glowRadius, glowPaint)
        }

        // 4. Top edge hairline border (dynamically matches the active theme border color)
        val strokeColor = ContextCompat.getColor(context, R.color.bottom_nav_stroke)
        val strokeR = Color.red(strokeColor)
        val strokeG = Color.green(strokeColor)
        val strokeB = Color.blue(strokeColor)
        
        borderPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            Color.argb(0, strokeR, strokeG, strokeB),
            Color.argb(120, strokeR, strokeG, strokeB),
            Shader.TileMode.CLAMP
        )
        borderPaint.color = Color.argb(100, strokeR, strokeG, strokeB)
        canvas.drawLine(0f, 1f, w, 1f, borderPaint)

        // Let super draw the icons/text on top
        super.onDraw(canvas)
    }

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
