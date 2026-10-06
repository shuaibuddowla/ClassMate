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
import com.google.android.material.card.MaterialCardView
import android.content.res.ColorStateList
import android.os.Build
import androidx.core.content.ContextCompat

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
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#F8FAFC"),
                    Color.parseColor("#F1F5F9"),
                    Color.parseColor("#E8EDF4")
                )
            )
            addView(content)
        }
        activity.setContentView(scroll)
        activity.window.statusBarColor = Color.parseColor("#F8FAFC")
        activity.window.navigationBarColor = Color.parseColor("#E8EDF4")
        androidx.core.view.WindowCompat.getInsetsController(activity.window, scroll).apply {
            val light = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
    }

    val brand = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_classmate_logo_mark)
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

    init {
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


    fun roleToggle(onRoleSelected: (isFaculty: Boolean) -> Unit): LinearLayout {
        val toggleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_bg_secondary))
                cornerRadius = dp(24).toFloat()
            }
        }
        val studentBtn = TextView(activity).apply {
            text = "Student"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(9), dp(28), dp(9))
            setTextColor(activity.getColor(R.color.cm_primary))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_surface))
                cornerRadius = dp(20).toFloat()
            }
        }
        val facultyBtn = TextView(activity).apply {
            text = "Faculty"
            textSize = 14f
            setTypeface(null, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(9), dp(28), dp(9))
            setTextColor(activity.getColor(R.color.cm_text_muted))
            background = null
        }
        studentBtn.setOnClickListener {
            studentBtn.setTypeface(null, Typeface.BOLD)
            studentBtn.setTextColor(activity.getColor(R.color.cm_primary))
            studentBtn.background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_surface))
                cornerRadius = dp(20).toFloat()
            }
            facultyBtn.setTypeface(null, Typeface.NORMAL)
            facultyBtn.setTextColor(activity.getColor(R.color.cm_text_muted))
            facultyBtn.background = null
            onRoleSelected(false)
        }
        facultyBtn.setOnClickListener {
            facultyBtn.setTypeface(null, Typeface.BOLD)
            facultyBtn.setTextColor(activity.getColor(R.color.cm_primary))
            facultyBtn.background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_surface))
                cornerRadius = dp(20).toFloat()
            }
            studentBtn.setTypeface(null, Typeface.NORMAL)
            studentBtn.setTextColor(activity.getColor(R.color.cm_text_muted))
            studentBtn.background = null
            onRoleSelected(true)
        }
        toggleContainer.addView(studentBtn)
        toggleContainer.addView(facultyBtn)

        val wrapper = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
            addView(toggleContainer, LinearLayout.LayoutParams(-2, -2))
        }
        content.addView(wrapper, LinearLayout.LayoutParams(-1, -2))
        animated.add(wrapper)
        return toggleContainer
    }

    fun googleAction(value: String = "Continue with Google", parent: LinearLayout = content, onClick: () -> Unit): MaterialButton =
        MaterialButton(activity).also {
            it.text = value
            it.isAllCaps = false
            it.textSize = 15f
            it.cornerRadius = dp(18)
            it.setTextColor(activity.getColor(R.color.cm_on_primary))
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            it.setPadding(dp(20), dp(12), dp(20), dp(12))
            it.minHeight = dp(50)
            try {
                it.icon = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_google)
                it.iconTint = null
                it.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                it.iconPadding = dp(10)
            } catch (_: Exception) {}
            val btnParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                gravity = Gravity.CENTER_HORIZONTAL
                marginStart = dp(24)
                marginEnd = dp(24)
            }
            parent.addView(it, btnParams)
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

    class SignInCardViews(
        val card: View,
        val requirementText: TextView,
        val statusText: TextView,
        val actionsContainer: LinearLayout,
        val googleButton: MaterialButton,
        private val onSetLoading: (Boolean) -> Unit = {}
    ) {
        fun setLoading(loading: Boolean) = onSetLoading(loading)
    }

    fun renderSignInWebCard(
        onRoleSelected: (isFaculty: Boolean) -> Unit,
        onGoogleSignIn: () -> Unit,
        onAboutClick: () -> Unit,
        onPrivacyClick: () -> Unit,
        onTermsClick: () -> Unit
    ): SignInCardViews {
        brand.visibility = View.GONE
        content.setPadding(dp(16), dp(16), dp(16), dp(24))
        content.gravity = Gravity.CENTER

        val cardWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Elevated Bright Material Card with smooth 360-degree ambient shadow
        val card = MaterialCardView(activity).apply {
            radius = dp(30).toFloat()
            cardElevation = dp(12).toFloat()
            maxCardElevation = dp(16).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.parseColor("#E2E8F0")
            strokeWidth = dp(1)
            useCompatPadding = true
            preventCornerOverlap = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.argb(65, 15, 23, 42)
                outlineSpotShadowColor = Color.argb(45, 29, 78, 216)
            }
        }

        val cardContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(32), dp(26), dp(24))
        }
        card.addView(cardContent, FrameLayout.LayoutParams(-1, -2))

        // 1. Centered Classic Emblem
        cardContent.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_classmate_logo_mark)
            contentDescription = "ClassMate"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(80), dp(80)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        // 2. Title
        cardContent.addView(TextView(activity).apply {
            text = "Sign in to ClassMate"
            textSize = 24f
            setTextColor(ink)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })

        // 3. Segmented Role Toggle
        val toggleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_bg_secondary))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), activity.getColor(R.color.cm_border_glass))
            }
        }
        val studentBtn = TextView(activity).apply {
            text = "Student"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.cm_primary))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        val facultyBtn = TextView(activity).apply {
            text = "Faculty"
            textSize = 13.5f
            setTypeface(null, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.cm_text_muted))
            background = null
            elevation = 0f
        }
        studentBtn.setOnClickListener {
            studentBtn.setTypeface(null, Typeface.BOLD)
            studentBtn.setTextColor(activity.getColor(R.color.cm_primary))
            studentBtn.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            studentBtn.elevation = dp(2).toFloat()

            facultyBtn.setTypeface(null, Typeface.NORMAL)
            facultyBtn.setTextColor(activity.getColor(R.color.cm_text_muted))
            facultyBtn.background = null
            facultyBtn.elevation = 0f
            onRoleSelected(false)
        }
        facultyBtn.setOnClickListener {
            facultyBtn.setTypeface(null, Typeface.BOLD)
            facultyBtn.setTextColor(activity.getColor(R.color.cm_primary))
            facultyBtn.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            facultyBtn.elevation = dp(2).toFloat()

            studentBtn.setTypeface(null, Typeface.NORMAL)
            studentBtn.setTextColor(activity.getColor(R.color.cm_text_muted))
            studentBtn.background = null
            studentBtn.elevation = 0f
            onRoleSelected(true)
        }
        toggleContainer.addView(studentBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        toggleContainer.addView(facultyBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        cardContent.addView(toggleContainer, LinearLayout.LayoutParams(dp(235), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })

        // 4. Continue with Google Button with animated loading state
        val googleButtonContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(16)
            }
        }

        val googleBtn = MaterialButton(activity).apply {
            text = "Continue with Google"
            isAllCaps = false
            textSize = 15.5f
            cornerRadius = dp(16)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1D4ED8"))
            minHeight = dp(52)
            elevation = dp(4).toFloat()
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            try {
                icon = ContextCompat.getDrawable(activity, R.drawable.ic_google)
                iconTint = null
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = dp(12)
            } catch (_: Exception) {}
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
                onGoogleSignIn()
            }
        }

        val progressBar = com.google.android.material.progressindicator.CircularProgressIndicator(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            isIndeterminate = true
            trackThickness = dp(3)
            setIndicatorColor(Color.WHITE)
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }

        val setLoadingState: (Boolean) -> Unit = { loading ->
            if (loading) {
                googleBtn.isEnabled = false
                googleBtn.text = ""
                googleBtn.icon = null
                progressBar.visibility = View.VISIBLE
                progressBar.alpha = 0f
                progressBar.animate().alpha(1f).setDuration(150).start()
            } else {
                googleBtn.isEnabled = true
                googleBtn.text = "Continue with Google"
                try {
                    googleBtn.icon = ContextCompat.getDrawable(activity, R.drawable.ic_google)
                    googleBtn.iconTint = null
                    googleBtn.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    googleBtn.iconPadding = dp(12)
                } catch (_: Exception) {}
                progressBar.animate().alpha(0f).setDuration(120).withEndAction {
                    progressBar.visibility = View.GONE
                }.start()
            }
        }

        googleButtonContainer.addView(googleBtn, FrameLayout.LayoutParams(-1, -1))
        googleButtonContainer.addView(progressBar)
        cardContent.addView(googleButtonContainer)

        // 5. Requirement Capsule
        val reqCapsule = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.cm_bg_secondary))
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), activity.getColor(R.color.cm_border_glass))
            }
        }
        reqCapsule.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.cm_text_muted))
        }, LinearLayout.LayoutParams(dp(13), dp(13)).apply {
            marginEnd = dp(6)
            gravity = Gravity.CENTER_VERTICAL
        })
        val reqText = TextView(activity).apply {
            text = "Requires @mbstu.ac.bd account"
            textSize = 12f
            setTextColor(activity.getColor(R.color.cm_text_muted))
            gravity = Gravity.CENTER
        }
        reqCapsule.addView(reqText, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        cardContent.addView(reqCapsule, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(24)
        })

        // 6. Divider Line
        val divider = View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.cm_border_glass))
        }
        cardContent.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            bottomMargin = dp(18)
        })

        // 7. Footer Policy Links
        val linksContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun link(name: String, onClick: () -> Unit) = TextView(activity).apply {
            text = name
            textSize = 12f
            setTextColor(activity.getColor(R.color.cm_text_muted))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { onClick() }
        }
        linksContainer.addView(link("About ClassMate", onAboutClick))
        linksContainer.addView(link("Privacy Policy", onPrivacyClick))
        linksContainer.addView(link("Terms of Service", onTermsClick))
        cardContent.addView(linksContainer, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })

        val maxCardW = dp(420)
        cardWrapper.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val screenW = activity.resources.displayMetrics.widthPixels
            if (screenW > maxCardW) width = maxCardW
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        // Status text below the card
        val statusText = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(activity.getColor(R.color.cm_text_muted))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        cardWrapper.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        cardWrapper.addView(actionsContainer, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val screenW = activity.resources.displayMetrics.widthPixels
            if (screenW > maxCardW) width = maxCardW
        })

        content.addView(cardWrapper, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER
        })
        animated.add(card)

        return SignInCardViews(card, reqText, statusText, actionsContainer, googleBtn, setLoadingState)
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
