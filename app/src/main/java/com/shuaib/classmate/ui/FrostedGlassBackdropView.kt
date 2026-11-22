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
 * Captures the underlying content (notices, courses, timetable, upcoming) directly behind the bottom bar,
 * downsamples it by 8x (which dissolves and eliminates text legibility), and on Android 12+
 * applies a GPU RenderEffect Gaussian blur shader.
 *
 * Employs responsive 30fps throttled capture during active touches/scrolls and instant settling capture
 * when scrolling stops, providing a rich frosted glass reflection with zero frame drops.
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
    private val captureIntervalMs = 33L // ~30fps smooth updates during active scroll

    private val updateRunnable = Runnable {
        if (!isCapturing && isShown && width > 0 && height > 0) {
            invalidate()
        }
    }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        notifyScroll()
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l > 0 && b - t > 0) {
            post { invalidateBackdrop() }
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
        behindView?.removeOnLayoutChangeListener(layoutChangeListener)
        unregisterScrollListener()
        behindView = target
        behindView?.addOnLayoutChangeListener(layoutChangeListener)
        registerScrollListener()
        post { invalidateBackdrop() }
    }

    private fun registerScrollListener() {
        try {
            viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            viewTreeObserver.addOnScrollChangedListener(scrollListener)
        } catch (_: Throwable) {}
        behindView?.let { target ->
            try {
                target.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                target.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            } catch (_: Throwable) {}
        }
    }

    private fun unregisterScrollListener() {
        try {
            viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        } catch (_: Throwable) {}
        behindView?.let { target ->
            try {
                target.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            } catch (_: Throwable) {}
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        behindView?.addOnLayoutChangeListener(layoutChangeListener)
        registerScrollListener()
        post { invalidateBackdrop() }
        postDelayed({ invalidateBackdrop() }, 200L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        behindView?.removeOnLayoutChangeListener(layoutChangeListener)
        removeCallbacks(updateRunnable)
        unregisterScrollListener()
    }

    /**
     * Called during scroll / swipe events across any tab to update the frosted backdrop in real time.
     * Throttled to ~30fps to maintain 120Hz display smoothness without frame drops.
     */
    fun notifyScroll() {
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

    /**
     * Force immediate recapture when content settles or when tabs switch.
     */
    fun invalidateBackdrop() {
        if (!isCapturing && isShown && width > 0 && height > 0) {
            lastCaptureTimeMs = 0L
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val defaultH = (84 * density).toInt()

        val finalHeight = when (hMode) {
            MeasureSpec.EXACTLY -> if (hSize > 0) hSize else defaultH
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
            invalidateBackdrop()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val target = behindView
        val bmp = downscaledBitmap
        val dCanvas = downscaledCanvas
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (width <= 0 || height <= 0) return

        val parentView = parent as? View
        val isTranslating = (parentView?.translationY ?: 0f) != 0f

        // Only recapture when stationary at dock position to prevent flickering during show/hide transitions
        if (target != null && bmp != null && dCanvas != null && !isTranslating && !isCapturing && target.width > 0 && target.height > 0) {
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
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), filterPaint)
        }

        // Frosted diffusion overlay:
        // ~65% white in light mode / ~68% dark slate in dark mode
        // allows the blurred colors of cards, dates, tags & images to vividly reflect through
        overlayPaint.color = if (isDark) Color.argb(175, 11, 20, 26) else Color.argb(170, 255, 255, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
    }
}
