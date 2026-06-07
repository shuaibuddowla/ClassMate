/*
 * com/shuaib/classmate/ui/GlassCardView.kt
 *
 * Enhanced glassmorphism card with:
 *   - RenderEffect backdrop blur on Android 12+ (API 31)
 *   - XML-configurable glass tint, corner radius, and highlight alpha
 *   - Optional left-edge accent shimmer line
 *   - Dynamic top-shine highlight gradient
 *   - Soft drop glow below the card
 */
package com.shuaib.classmate.ui

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import com.shuaib.classmate.R

class GlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // --- Configurable properties (via XML attrs or setters) ---
    private var glassTint: Int = Color.TRANSPARENT
    private var cornerRadius: Float
    private var highlightAlpha: Int = 30
    private var baseAlpha: Int = 200
    private var showAccentLine: Boolean = false
    private var accentColor: Int = Color.parseColor("#3B82F6")

    // --- Paints ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(35, 255, 255, 255)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private val glowRect = RectF()

    init {
        // Read XML attributes
        context.theme.obtainStyledAttributes(attrs, R.styleable.GlassCardView, 0, 0).apply {
            try {
                glassTint = getColor(R.styleable.GlassCardView_glassTint, Color.TRANSPARENT)
                cornerRadius = getDimension(
                    R.styleable.GlassCardView_glassCornerRadius,
                    dpToPx(20f)
                )
                highlightAlpha = getInt(R.styleable.GlassCardView_glassHighlightAlpha, 30)
                baseAlpha = getInt(R.styleable.GlassCardView_glassBaseAlpha, 200)
                showAccentLine = getBoolean(R.styleable.GlassCardView_glassShowAccentLine, false)
                accentColor = getColor(
                    R.styleable.GlassCardView_glassAccentColor,
                    Color.parseColor("#3B82F6")
                )
            } finally {
                recycle()
            }
        }

        setWillNotDraw(false)
        // Use HARDWARE layer on API 31+ to support RenderEffect; SOFTWARE otherwise
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        } else {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Apply backdrop blur effect on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurHelper.applyCardBlur(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        BlurHelper.clearBlur(this)
    }

    /** Programmatically set the glass tint color and redraw. */
    fun setGlassTint(color: Int) {
        glassTint = color
        invalidate()
    }

    /** Programmatically set the accent line color and redraw. */
    fun setAccentColor(color: Int) {
        accentColor = color
        accentPaint.color = accentColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        // 1. Soft glow shadow beneath (drawn slightly outside the card boundary)
        val glowInset = dpToPx(3f)
        glowRect.set(glowInset, dpToPx(4f), w - glowInset, h + dpToPx(6f))
        glowPaint.shader = RadialGradient(
            w / 2f, h + dpToPx(4f),
            w * 0.55f,
            Color.argb(18, 59, 130, 246),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(glowRect, glowPaint)

        // 2. Dark glass base background
        bgPaint.color = Color.argb(baseAlpha, 13, 21, 38)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        // 3. Optional glass tint overlay
        if (glassTint != Color.TRANSPARENT) {
            tintPaint.color = glassTint
            tintPaint.alpha = 28
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, tintPaint)
        }

        // 4. Top shine highlight — two-thirds of card height for a frosted glass look
        val highlightH = h * 0.62f
        val highlightRect = RectF(0f, 0f, w, highlightH)
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, highlightH,
            Color.argb(highlightAlpha, 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(highlightRect, cornerRadius, cornerRadius, highlightPaint)

        // 5. Hairline white border
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // 6. Optional left-edge accent shimmer line
        if (showAccentLine) {
            accentPaint.color = accentColor
            accentPaint.alpha = 180
            val lineX = dpToPx(1.5f)
            val lineTop = cornerRadius * 0.6f
            val lineBot = h - cornerRadius * 0.6f
            accentPaint.shader = LinearGradient(
                lineX, lineTop, lineX, lineBot,
                Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                accentColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(lineX, lineTop, lineX, lineBot, accentPaint)
        }
    }

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
