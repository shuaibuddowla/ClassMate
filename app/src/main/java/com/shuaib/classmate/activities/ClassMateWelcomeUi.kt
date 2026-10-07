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
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shuaib.classmate.R
import com.google.android.material.button.MaterialButton
import android.content.res.ColorStateList
import android.os.Build
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

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

    class SignInCardViews(
        val card: View,
        val requirementText: TextView,
        val statusText: TextView,
        val actionsContainer: LinearLayout,
        val googleButton: View,
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
        // Transparent modern status & navigation bars
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.parseColor("#EBF2FE")
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Layer 1: Ambient background
        val bgView = ImageView(activity).apply {
            setImageResource(R.drawable.bg_signin_ambient)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(bgView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Layer 2: Subtle bottom campus illustration
        val campusView = ImageView(activity).apply {
            setImageResource(R.drawable.bg_campus_illustration)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0.40f
        }
        root.addView(campusView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(105)).apply {
            gravity = Gravity.BOTTOM
        })

        // Layer 3: Scrollable content
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)

        // Metrics & responsiveness
        val dm = activity.resources.displayMetrics
        val screenWdp = dm.widthPixels / density
        val screenHdp = dm.heightPixels / density
        val isCompact = screenHdp < 720

        val topPadding = if (isCompact) dp(24) else dp(38)
        val heroBottomMargin = if (isCompact) dp(12) else dp(18)
        val cardTopPadding = if (isCompact) dp(20) else dp(26)
        val cardBottomPadding = if (isCompact) dp(18) else dp(22)
        val titleBottomMargin = if (isCompact) dp(14) else dp(20)
        val toggleBottomMargin = if (isCompact) dp(12) else dp(16)
        val googleBottomMargin = if (isCompact) dp(10) else dp(14)
        val reqBottomMargin = if (isCompact) dp(12) else dp(16)
        val dividerBottomMargin = if (isCompact) dp(12) else dp(16)

        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(20))
        }
        val scrollParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        scroll.addView(scrollContent, scrollParams)

        // Top flexible spacer for true vertical centering
        val topSpacer = View(activity)
        scrollContent.addView(topSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f))

        // 1. Hero App Logo with radiant accent strokes
        val heroContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(116)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = heroBottomMargin
            }
        }
        val raysView = ImageView(activity).apply {
            setImageResource(R.drawable.bg_hero_accent_rays)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heroContainer.addView(raysView, FrameLayout.LayoutParams(dp(200), dp(116), Gravity.CENTER))

        val heroLogo = ImageView(activity).apply {
            setImageResource(R.drawable.ic_classmate_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ClassMate"
            elevation = dp(8).toFloat()
        }
        heroContainer.addView(heroLogo, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        scrollContent.addView(heroContainer)
        animated.add(heroContainer)

        // 2. Main Sign-in Card
        val maxCardW = dp(420)
        val card = MaterialCardView(activity).apply {
            radius = dp(30).toFloat()
            cardElevation = dp(6).toFloat()
            maxCardElevation = dp(8).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.parseColor("#E2E8F0")
            strokeWidth = dp(1)
            useCompatPadding = false
            preventCornerOverlap = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.argb(40, 15, 23, 42)
                outlineSpotShadowColor = Color.argb(30, 29, 78, 216)
            }
        }
        val sideMargin = if (dm.widthPixels <= maxCardW) {
            (dm.widthPixels * 0.045f).toInt().coerceIn(dp(15), dp(20))
        } else 0

        val cardParams = LinearLayout.LayoutParams(
            if (dm.widthPixels > maxCardW) maxCardW else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            leftMargin = sideMargin
            rightMargin = sideMargin
        }
        scrollContent.addView(card, cardParams)
        animated.add(card)

        val cardHorizPadding = when {
            screenWdp < 375 -> dp(16)
            screenWdp < 412 -> dp(18)
            else -> dp(22)
        }
        val cardContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(cardHorizPadding, cardTopPadding, cardHorizPadding, cardBottomPadding)
        }
        card.addView(cardContent, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Heading: Sign in to ClassMate
        val titleTextSize = if (screenWdp < 375) 21f else 23.5f
        cardContent.addView(TextView(activity).apply {
            text = "Sign in to ClassMate"
            textSize = titleTextSize
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            maxLines = 2
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = titleBottomMargin
        })

        // Wide Segmented Role Switcher
        val toggleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF3FA"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        val toggleBtnSize = if (screenWdp < 375) 13.5f else 14f
        val studentBtn = TextView(activity).apply {
            text = "Student"
            textSize = toggleBtnSize
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1D4ED8"))
            setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        val facultyBtn = TextView(activity).apply {
            text = "Faculty"
            textSize = toggleBtnSize
            setTypeface(null, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setSingleLine(true)
            background = null
            elevation = 0f
        }
        studentBtn.setOnClickListener {
            studentBtn.setTypeface(null, Typeface.BOLD)
            studentBtn.setTextColor(Color.parseColor("#1D4ED8"))
            studentBtn.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            studentBtn.elevation = dp(2).toFloat()

            facultyBtn.setTypeface(null, Typeface.NORMAL)
            facultyBtn.setTextColor(Color.parseColor("#64748B"))
            facultyBtn.background = null
            facultyBtn.elevation = 0f
            onRoleSelected(false)
        }
        facultyBtn.setOnClickListener {
            facultyBtn.setTypeface(null, Typeface.BOLD)
            facultyBtn.setTextColor(Color.parseColor("#1D4ED8"))
            facultyBtn.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            facultyBtn.elevation = dp(2).toFloat()

            studentBtn.setTypeface(null, Typeface.NORMAL)
            studentBtn.setTextColor(Color.parseColor("#64748B"))
            studentBtn.background = null
            studentBtn.elevation = 0f
            onRoleSelected(true)
        }
        toggleContainer.addView(studentBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        toggleContainer.addView(facultyBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        cardContent.addView(toggleContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = toggleBottomMargin
        })

        // Large Full-Width Google Sign-In Button
        val googleBtnContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = googleBottomMargin
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#1D4ED8"))
            ).apply {
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(3).toFloat()
        }

        val googleInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // White circular container preserving official multi-color Google logo
        val googleIconCircle = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(11)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        val googleIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_google)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        googleIconCircle.addView(googleIcon, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
        googleInner.addView(googleIconCircle)

        val googleText = TextView(activity).apply {
            text = "Continue with Google"
            textSize = if (screenWdp < 375) 15f else 15.5f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        googleInner.addView(googleText)
        googleBtnContainer.addView(googleInner)

        val progressBar = com.google.android.material.progressindicator.CircularProgressIndicator(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            isIndeterminate = true
            trackThickness = dp(3)
            setIndicatorColor(Color.WHITE)
            visibility = View.GONE
            elevation = dp(6).toFloat()
        }
        googleBtnContainer.addView(progressBar)

        googleBtnContainer.setOnClickListener {
            googleBtnContainer.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
                googleBtnContainer.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
            onGoogleSignIn()
        }
        cardContent.addView(googleBtnContainer)

        // Account Requirement Capsule
        val reqCapsule = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5FA"))
                cornerRadius = dp(21).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        reqCapsule.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
            marginEnd = dp(7)
            gravity = Gravity.CENTER_VERTICAL
        })
        val reqText = TextView(activity).apply {
            text = "Requires @mbstu.ac.bd account"
            textSize = if (screenWdp < 375) 12.2f else 12.8f
            setTextColor(Color.parseColor("#536583"))
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        reqCapsule.addView(reqText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        cardContent.addView(reqCapsule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
            bottomMargin = reqBottomMargin
        })

        // Subtle Divider Line
        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#E2E8F0"))
        }
        cardContent.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dividerBottomMargin
        })

        // Footer Policy Links
        val linksContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val footerTextSize = if (screenWdp < 375) 11.2f else 12f
        fun link(name: String, onClick: () -> Unit) = TextView(activity).apply {
            text = name
            textSize = footerTextSize
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setSingleLine(true)
            maxLines = 1
            setOnClickListener { onClick() }
        }
        fun dot() = TextView(activity).apply {
            text = "•"
            textSize = 10f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(2), dp(4), dp(2), dp(4))
            setSingleLine(true)
            maxLines = 1
        }
        linksContainer.addView(link("About", onAboutClick))
        linksContainer.addView(dot())
        linksContainer.addView(link("Privacy Policy", onPrivacyClick))
        linksContainer.addView(dot())
        linksContainer.addView(link("Terms of Service", onTermsClick))
        cardContent.addView(linksContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })

        // Supporting text below the card
        val supportingText = TextView(activity).apply {
            text = "Students: use university email.\nTeachers: use your approved Google account."
            textSize = if (screenWdp < 375) 12f else 12.8f
            setTextColor(Color.parseColor("#536583"))
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), 0, dp(16), 0)
        }
        scrollContent.addView(supportingText, LinearLayout.LayoutParams(
            if (dm.widthPixels > maxCardW) maxCardW else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            leftMargin = sideMargin
            rightMargin = sideMargin
            topMargin = dp(16)
            bottomMargin = dp(4)
        })
        animated.add(supportingText)

        // Status text for errors/notifications
        val statusText = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(activity.getColor(R.color.cm_text_muted))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(4), dp(16), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        statusText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString()?.trim() ?: ""
                if (t.isEmpty() || t.contains("Students: use university email", ignoreCase = true) || t.contains("Teachers: use your approved", ignoreCase = true)) {
                    if (t.contains("Students: use university email", ignoreCase = true) || t.contains("Teachers: use your approved", ignoreCase = true)) {
                        statusText.post { statusText.text = "" }
                    }
                    statusText.visibility = View.GONE
                } else {
                    statusText.visibility = View.VISIBLE
                    if (t.contains("fail", ignoreCase = true) || t.contains("error", ignoreCase = true) || t.contains("missing", ignoreCase = true)) {
                        statusText.setTextColor(Color.parseColor("#DC2626"))
                    } else {
                        statusText.setTextColor(activity.getColor(R.color.cm_text_muted))
                    }
                }
            }
        })
        scrollContent.addView(statusText, LinearLayout.LayoutParams(
            if (dm.widthPixels > maxCardW) maxCardW else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            leftMargin = sideMargin
            rightMargin = sideMargin
        })

        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(actionsContainer, LinearLayout.LayoutParams(
            if (dm.widthPixels > maxCardW) maxCardW else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            leftMargin = sideMargin
            rightMargin = sideMargin
        })

        // Bottom flexible spacer with optical balance above campus decoration
        val bottomSpacer = View(activity)
        scrollContent.addView(bottomSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.15f))

        val setLoadingState: (Boolean) -> Unit = { loading ->
            if (loading) {
                googleBtnContainer.isEnabled = false
                googleInner.animate().alpha(0f).setDuration(120).start()
                progressBar.visibility = View.VISIBLE
                progressBar.alpha = 0f
                progressBar.animate().alpha(1f).setDuration(150).start()
            } else {
                googleBtnContainer.isEnabled = true
                googleInner.animate().alpha(1f).setDuration(150).start()
                progressBar.animate().alpha(0f).setDuration(120).withEndAction {
                    progressBar.visibility = View.GONE
                }.start()
            }
        }

        return SignInCardViews(card, reqText, statusText, actionsContainer, googleBtnContainer, setLoadingState)
    }
}
