package com.shuaib.classmate.activities

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shuaib.classmate.R
import com.google.android.material.button.MaterialButton

/** The welcome flow shares one visual language, independent of the academic screens. */
internal class ClassMateWelcomeUi(private val activity: Activity) {
    private val ink = activity.getColor(R.color.cm_text_primary)
    private val muted = activity.getColor(R.color.cm_text_secondary)
    private val accent = activity.getColor(R.color.cm_primary)
    private val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(32))
    }
    private val animated = mutableListOf<View>()

    init {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(activity.getColor(R.color.cm_background))
            addView(content)
        }
        activity.setContentView(scroll)
        activity.window.statusBarColor = activity.getColor(R.color.cm_background)
        activity.window.navigationBarColor = activity.getColor(R.color.cm_background)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, scroll).apply {
            val light = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val brand = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_classmate_logo)
                contentDescription = "ClassMate logo"
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(activity).apply {
                text = "ClassMate"
                textSize = 18f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            })
        }
        content.addView(brand, LinearLayout.LayoutParams(-1, dp(42)))
        animated.add(brand)
    }

    fun text(value: String, size: Float = 15f, color: Int = muted,
             parent: LinearLayout = content): TextView = TextView(activity).also {
        it.text = value
        it.textSize = size
        it.setTextColor(color)
        it.setLineSpacing(dp(3).toFloat(), 1f)
        it.setPadding(0, dp(8), 0, dp(8))
        parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        animated.add(it)
    }

    fun title(value: String) = text(value, 36f, ink).apply {
        setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        setLineSpacing(0f, .98f)
    }

    fun panel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(activity.getColor(R.color.cm_surface))
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), activity.getColor(R.color.cm_border_glass))
        }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16); bottomMargin = dp(12)
        })
        animated.add(this)
    }

    fun action(value: String, primary: Boolean = true, parent: LinearLayout = content,
               onClick: () -> Unit): MaterialButton = MaterialButton(activity).also {
        it.text = value
        it.isAllCaps = false
        it.textSize = 15f
        it.cornerRadius = dp(18)
        it.setTextColor(if (primary) activity.getColor(R.color.cm_on_primary) else ink)
        it.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (primary) accent else activity.getColor(R.color.cm_primary_soft))
        it.setPadding(dp(16), dp(12), dp(16), dp(12))
        it.minHeight = dp(56)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        it.setOnClickListener { onClick() }
        animated.add(it)
    }

    fun field(parent: LinearLayout, label: String, value: String) {
        text(label.uppercase(), 10f, accent, parent).letterSpacing = .14f
        text(value, if (label.contains("mail", true)) 15f else 18f, ink, parent)
            .setTypeface(null, Typeface.BOLD)
    }

    fun fieldPair(parent: LinearLayout, firstLabel: String, firstValue: String,
                  secondLabel: String, secondValue: String) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        listOf(firstLabel to firstValue, secondLabel to secondValue).forEachIndexed { index, pair ->
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                if (index == 0) setPadding(0, 0, dp(10), 0)
            }
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            field(column, pair.first, pair.second)
        }
    }

    fun signInHero() {
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_primary_soft)); cornerRadius = dp(28).toFloat()
            }
        }
        hero.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_classmate_logo); contentDescription = "ClassMate"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
        text("Your campus. One place.", 22f, ink, hero).setTypeface(null, Typeface.BOLD)
        text("Classes, resources and updates, together.", 14f, muted, hero)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(12) })
        animated.add(hero)
    }

    fun orbit(complete: Boolean = false) {
        val height = if (complete) 128 else 148
        val hero = FrameLayout(activity)
        hero.addView(OrbitView(activity, complete), FrameLayout.LayoutParams(-1, -1))
        hero.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_classmate_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = if (complete) "ClassMate profile verified" else "ClassMate app logo"
            elevation = dp(12).toFloat()
        }, FrameLayout.LayoutParams(dp(if (complete) 92 else 100),
            dp(if (complete) 92 else 100), Gravity.CENTER))
        if (complete) hero.addView(TextView(activity).apply {
            text = "✓"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(10, 35, 37))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setStroke(dp(3), Color.rgb(20, 39, 61))
            }
            contentDescription = "Verified"
            elevation = dp(16).toFloat()
        }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER).apply {
            leftMargin = dp(103)
            topMargin = dp(100)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(height)).apply {
            topMargin = dp(6)
        })
        animated.add(hero)
    }

    fun animateEntrance(reveal: Boolean = false) {
        val accessibility = activity.getSystemService(AccessibilityManager::class.java)
        if (!ValueAnimator.areAnimatorsEnabled() || accessibility?.isTouchExplorationEnabled == true) return
        animated.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(if (reveal) 22 else 16).toFloat()
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay((index * if (reveal) 105L else 65L).coerceAtMost(1100L))
                .setDuration(if (reveal) 580 else 450).start()
        }
    }

    private class OrbitView(context: android.content.Context, private val complete: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animator: ValueAnimator? = null
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            if (ValueAnimator.areAnimatorsEnabled() && accessibility?.isTouchExplorationEnabled != true) {
                animator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 16000; repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        override fun onDetachedFromWindow() {
            animator?.cancel(); animator = null
            super.onDetachedFromWindow()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width / 2f; val y = height / 2f
            val r = 55 * resources.displayMetrics.density
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
            paint.color = context.getColor(R.color.cm_border_glass)
            canvas.drawCircle(x, y, r, paint)
            canvas.drawCircle(x, y, r * 1.28f, paint)
            canvas.save(); canvas.rotate(phase, x, y)
            paint.color = context.getColor(R.color.cm_primary); paint.style = Paint.Style.FILL
            canvas.drawCircle(x + r, y, r * .07f, paint)
            paint.color = context.getColor(R.color.cm_primary_light)
            canvas.drawCircle(x - r * 1.28f, y, r * .06f, paint)
            canvas.restore()
            paint.color = if (complete) context.getColor(R.color.cm_primary) else context.getColor(R.color.cm_border)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2
            canvas.drawCircle(x, y, r * .78f, paint)
        }
    }
}
