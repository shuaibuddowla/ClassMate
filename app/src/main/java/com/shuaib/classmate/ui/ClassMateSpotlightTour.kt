package com.shuaib.classmate.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R

/**
 * Interactive spotlight / coachmark tour for ClassMate Android.
 * Highlights key UI elements one by one with a cutout and animated guide tooltip.
 */
object ClassMateSpotlightTour {

    data class TourStep(
        val targetViewId: Int?,
        val fallbackFinder: ((Activity) -> View?)? = null,
        val badgeText: String,
        val title: String,
        val description: String,
        val isCircle: Boolean = true
    )

    private const val PREFS_NAME = "classmate_spotlight_tour"
    private const val PREF_KEY_LAST_SEEN_VERSION = "tour_last_seen_version"

    fun shouldShow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeenVersion = prefs.getInt(PREF_KEY_LAST_SEEN_VERSION, 0)
        return BuildConfig.VERSION_CODE > lastSeenVersion
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_LAST_SEEN_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun resetSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_LAST_SEEN_VERSION)
            .apply()
    }

    fun start(activity: Activity, force: Boolean = false) {
        if (!force && !shouldShow(activity)) return
        if (activity.isFinishing || activity.isDestroyed) return

        val steps = mutableListOf<TourStep>()

        // Step 1: Hamburger Menu Button (Drawer for Campus Hub: Teachers Directory & Transit)
        steps.add(
            TourStep(
                targetViewId = R.id.btnHamburgerMenu,
                fallbackFinder = { act -> act.findViewById(R.id.layoutBrandHeaderRow) },
                badgeText = "Campus Drawer",
                title = "Teachers Directory & Bus Transit",
                description = "Tap the menu to open the new drawer: search faculty in the Teachers Directory (phone, email & assigned courses), track live MBSTU Bus Transit with GPS radar, view Batchmates, and check the Batch Fund.",
                isCircle = true
            )
        )

        // Step 2: Timetable Day Selector / Schedule View
        steps.add(
            TourStep(
                targetViewId = R.id.daySelector,
                fallbackFinder = { act ->
                    act.findViewById(R.id.btnAddPeriod)
                        ?: act.findViewById(R.id.layoutScheduleToggle)
                        ?: act.findViewById(R.id.rvPeriods)
                        ?: act.findViewById(R.id.layoutBrandHeaderRow)
                },
                badgeText = "Class Routine",
                title = "Weekday Routine & Countdowns",
                description = "Easily switch weekdays to view your class schedules, classroom venues, and live countdowns to your upcoming lectures.",
                isCircle = false
            )
        )

        // Step 3: Bottom Navigation Bar
        steps.add(
            TourStep(
                targetViewId = R.id.classmate_home_compose_nav,
                fallbackFinder = { act -> act.findViewById(R.id.classmate_home_nav) },
                badgeText = "Streamlined 4 Tabs",
                title = "Notices, Polls, Courses & Student ID",
                description = "Navigate seamlessly: Timetable, official department Notices with interactive live polls, semester Course library, and Settings with your digital NFC Student ID.",
                isCircle = false
            )
        )

        // Delay slightly so layout pass is fully settled
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                // If already showing a spotlight tour, remove it first
                val decor = activity.window.decorView as? ViewGroup
                decor?.findViewWithTag<View>("spotlight_overlay_tag")?.let {
                    decor.removeView(it)
                }
                SpotlightOverlay(activity, steps) {
                    markSeen(activity)
                }.show()
            }
        }, 350)
    }

    private class SpotlightOverlay(
        private val activity: Activity,
        private val steps: List<TourStep>,
        private val onFinished: () -> Unit
    ) : FrameLayout(activity) {

        private val density = resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        private var currentStepIndex = 0
        private var targetRect = RectF()
        private var pulseScale = 1f

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#7C3AED") // ClassMate Royal Purple
            strokeWidth = 3f * density
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#667C3AED")
            strokeWidth = 6f * density
        }

        private var pulseAnimator: ValueAnimator? = null
        private val cardView = MaterialCardView(context)
        private val tvBadge = TextView(context)
        private val tvTitle = TextView(context)
        private val tvDesc = TextView(context)
        private val btnSkip = TextView(context)
        private val btnNext = MaterialButton(context)

        init {
            tag = "spotlight_overlay_tag"
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_HARDWARE, null)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true

            setupCard()
            setupAnimator()
        }

        private fun setupCard() {
            cardView.apply {
                radius = 18f * density
                cardElevation = 14f * density
                setCardBackgroundColor(Color.parseColor("#1E293B")) // Slate 800
                strokeWidth = (1.2f * density).toInt()
                strokeColor = Color.parseColor("#475569")

                val cardLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                }

                // Header Row (Badge + Close)
                val headerRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                tvBadge.apply {
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_badge_pill)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                headerRow.addView(tvBadge)

                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                headerRow.addView(spacer)

                val btnClose = TextView(context).apply {
                    text = "✕"
                    textSize = 15f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        dismiss()
                    }
                }
                headerRow.addView(btnClose)

                cardLayout.addView(headerRow)

                // Title
                tvTitle.apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(10), 0, dp(4))
                }
                cardLayout.addView(tvTitle)

                // Description
                tvDesc.apply {
                    textSize = 13f
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setLineSpacing(3f * density, 1f)
                    setPadding(0, 0, 0, dp(14))
                }
                cardLayout.addView(tvDesc)

                // Bottom Button Bar
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                btnSkip.apply {
                    text = "Skip Tour"
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(6), dp(6), dp(14), dp(6))
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        dismiss()
                    }
                }
                btnRow.addView(btnSkip)

                val btnSpacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                btnRow.addView(btnSpacer)

                btnNext.apply {
                    text = "Next"
                    textSize = 13f
                    isAllCaps = false
                    cornerRadius = dp(12)
                    setBackgroundColor(Color.parseColor("#2563EB"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (currentStepIndex < steps.size - 1) {
                            currentStepIndex++
                            bindStep()
                        } else {
                            dismiss()
                        }
                    }
                }
                btnRow.addView(btnNext)

                cardLayout.addView(btnRow)
                addView(cardLayout)
            }

            val cardLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
            addView(cardView, cardLp)
        }

        private fun setupAnimator() {
            pulseAnimator = ValueAnimator.ofFloat(1f, 1.25f).apply {
                duration = 1000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    pulseScale = anim.animatedValue as Float
                    invalidate()
                }
            }
        }

        fun show() {
            val root = activity.window.decorView as? ViewGroup ?: return
            alpha = 0f
            root.addView(this)
            animate().alpha(1f).setDuration(250).start()
            pulseAnimator?.start()
            post {
                bindStep()
            }
        }

        private fun resolveTarget(step: TourStep): View? {
            val primary = if (step.targetViewId != null) activity.findViewById<View>(step.targetViewId) else null
            if (primary != null && primary.isShown && primary.width > 0 && primary.height > 0) {
                return primary
            }
            val fallback = step.fallbackFinder?.invoke(activity)
            if (fallback != null && fallback.isShown && fallback.width > 0 && fallback.height > 0) {
                return fallback
            }
            return primary?.takeIf { it.isShown } ?: fallback
        }

        private fun bindStep() {
            if (currentStepIndex !in steps.indices) {
                dismiss()
                return
            }

            val step = steps[currentStepIndex]
            tvBadge.text = "${currentStepIndex + 1} of ${steps.size} · ${step.badgeText}"
            tvTitle.text = step.title
            tvDesc.text = step.description
            btnNext.text = if (currentStepIndex == steps.size - 1) "Got it!" else "Next"

            val targetView = resolveTarget(step)
            val loc = IntArray(2)
            if (targetView != null && targetView.isShown && targetView.width > 0 && targetView.height > 0) {
                targetView.getLocationInWindow(loc)
                val pad = dp(if (step.isCircle) 8 else 10)
                targetRect.set(
                    (loc[0] - pad).toFloat(),
                    (loc[1] - pad).toFloat(),
                    (loc[0] + targetView.width + pad).toFloat(),
                    (loc[1] + targetView.height + pad).toFloat()
                )
            } else {
                // Centered fallback if view not on current screen
                val cx = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / 2f
                val cy = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 0.35f
                targetRect.set(cx - dp(60), cy - dp(40), cx + dp(60), cy + dp(40))
            }

            // Position Tooltip Card intelligently
            cardView.post {
                val screenHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val cardHeight = cardView.height.takeIf { it > 0 } ?: dp(180)
                val targetCenterY = targetRect.centerY()

                val targetCardY = if (targetCenterY < screenHeight * 0.55f) {
                    // Place BELOW target
                    (targetRect.bottom + dp(18)).coerceAtMost((screenHeight - cardHeight - dp(30)).toFloat())
                } else {
                    // Place ABOVE target
                    (targetRect.top - cardHeight - dp(18)).coerceAtLeast(dp(40).toFloat())
                }

                cardView.animate()
                    .y(targetCardY)
                    .setDuration(300)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
            val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()

            // Save layer to cleanly punch out spotlight hole
            val saveCount = canvas.saveLayer(0f, 0f, w, h, null)

            // 1. Draw darkened backdrop (85% deep slate)
            canvas.drawColor(Color.parseColor("#E0090D16"))

            if (!targetRect.isEmpty) {
                val step = steps.getOrNull(currentStepIndex)
                val isCircle = step?.isCircle ?: true

                // 2. Cut out spotlight hole
                if (isCircle) {
                    val cx = targetRect.centerX()
                    val cy = targetRect.centerY()
                    val r = (targetRect.width().coerceAtLeast(targetRect.height())) / 2f

                    // Clear hole
                    canvas.drawCircle(cx, cy, r, clearPaint)

                    // Animated pulsing ring
                    val pulseRadius = r + (dp(5) * (pulseScale - 1f) * 4f)
                    canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
                    canvas.drawCircle(cx, cy, r, strokePaint)
                } else {
                    val rx = 14f * density
                    val ry = 14f * density

                    // Clear hole
                    canvas.drawRoundRect(targetRect, rx, ry, clearPaint)

                    // Animated pulsing ring
                    val inset = -dp(3) * (pulseScale - 1f) * 3f
                    val pulseRect = RectF(targetRect).apply { inset(inset, inset) }
                    canvas.drawRoundRect(pulseRect, rx + dp(2), ry + dp(2), pulsePaint)
                    canvas.drawRoundRect(targetRect, rx, ry, strokePaint)
                }
            }

            canvas.restoreToCount(saveCount)
        }

        fun dismiss() {
            pulseAnimator?.cancel()
            animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    (parent as? ViewGroup)?.removeView(this)
                    onFinished()
                }
                .start()
        }
    }
}
