package com.shuaib.classmate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver

/**
 * Real-time hardware-accelerated frosted glass backdrop blur.
 *
 * Captures the underlying content (notices, courses, timetable) directly behind the bottom bar,
 * downsamples it by 8x (which dissolves and eliminates text legibility), and on Android 12+
 * applies a GPU RenderEffect Gaussian blur shader.
 *
 * Uses OnScrollChangedListener instead of continuous pre-draw invalidation to guarantee
 * zero flickering and zero blinking during scroll animations.
 */
class FrostedGlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var behindView: View? = null
    private var downscaledBitmap: Bitmap? = null
    private var downscaledCanvas: Canvas? = null
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint()

    private val downscaleFactor = 8
    private var isCapturing = false
    private val locThis = IntArray(2)
    private val locBehind = IntArray(2)
    private var lastCaptureTimeMs = 0L
    private val captureIntervalMs = 120L

    private val updateRunnable = Runnable {
        if (!isCapturing && isShown && width > 0 && height > 0) {
            invalidate()
        }
    }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        if (!isCapturing && isShown && width > 0 && height > 0) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastCaptureTimeMs >= captureIntervalMs) {
                invalidate()
            } else {
                removeCallbacks(updateRunnable)
                postDelayed(updateRunnable, captureIntervalMs)
            }
        }
    }

    init {
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                setRenderEffect(RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP))
            } catch (_: Throwable) {}
        }
    }

    fun attachBehindView(target: View) {
        if (behindView === target) return
        behindView?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
        behindView = target
        target.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(updateRunnable)
        behindView?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val defaultH = (84 * density).toInt()

        val finalHeight = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> minOf(hSize, defaultH)
            else -> defaultH
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            finalHeight
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val sw = (w / downscaleFactor).coerceAtLeast(1)
            val sh = (h / downscaleFactor).coerceAtLeast(1)
            downscaledBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            downscaledCanvas = Canvas(downscaledBitmap!!)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val target = behindView ?: return
        val bmp = downscaledBitmap ?: return
        val dCanvas = downscaledCanvas ?: return
        if (width <= 0 || height <= 0) return

        val parentView = parent as? View
        val isTranslating = (parentView?.translationY ?: 0f) != 0f

        // Only recapture when stationary at dock position to prevent flickering during scroll transitions
        if (!isTranslating && !isCapturing && target.width > 0 && target.height > 0) {
            isCapturing = true
            try {
                lastCaptureTimeMs = android.os.SystemClock.uptimeMillis()
                getLocationOnScreen(locThis)
                target.getLocationOnScreen(locBehind)

                val offsetX = (locThis[0] - locBehind[0]).toFloat().coerceAtLeast(0f)
                val parentTransY = parentView?.translationY ?: 0f
                val restingTop = locThis[1] - parentTransY
                val offsetY = (restingTop - locBehind[1]).toFloat().coerceIn(0f, (target.height - height).coerceAtLeast(0).toFloat())

                // Clear previous frame
                dCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                // Draw behind view downscaled to dissolve text into blurred color fields
                dCanvas.save()
                val scale = 1f / downscaleFactor
                dCanvas.scale(scale, scale)
                dCanvas.translate(-offsetX, -offsetY)
                target.draw(dCanvas)
                dCanvas.restore()
            } catch (_: Throwable) {
            } finally {
                isCapturing = false
            }
        }

        // Draw blurred color field across this view
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), filterPaint)

        // Frosted diffusion overlay (lets melted RGB colors reflect through)
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        overlayPaint.color = if (isDark) Color.argb(195, 11, 20, 26) else Color.argb(205, 255, 255, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
    }
}
