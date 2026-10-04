package com.shuaib.classmate.activities

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.shuaib.classmate.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Native calendar surface: decorative artwork never receives touches or accessibility focus. */
internal class ClassMateCalendarMonthView(
    context: Context, month: YearMonth, selected: LocalDate?, count: Int,
    closed: (LocalDate)->Boolean, scopes: (LocalDate)->Set<String>, description: (LocalDate)->String,
    onToday: ()->Unit, onPrevious: ()->Unit, onNext: ()->Unit, onJump: ()->Unit,
    onSelect: (LocalDate)->Unit,
) : MaterialCardView(context) {
    private var emphasizedDate: View? = null
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun color(id: Int)=context.getColor(id)
    private fun text(value: String,size: Float,bold: Boolean=false,tint: Int=R.color.cm_text_primary)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color(tint)); if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun fill(tint: Int,radius: Int)=GradientDrawable().apply { setColor(color(tint)); cornerRadius=dp(radius).toFloat() }
    private fun tint(type: ClassMateCalendarPresentation.Indicator)=when(type) {
        ClassMateCalendarPresentation.Indicator.CLOSED -> R.color.cm_calendar_closed
        ClassMateCalendarPresentation.Indicator.CLASS_HOLIDAY -> R.color.cm_calendar_classes
        ClassMateCalendarPresentation.Indicator.EVENT -> R.color.cm_primary
    }
    private fun nav(icon: Int,label: String,action: ()->Unit)=AppCompatImageButton(context).apply {
        setImageResource(icon); imageTintList=ColorStateList.valueOf(color(R.color.cm_text_primary))
        setPadding(dp(13),dp(13),dp(13),dp(13)); contentDescription=label
        background=RippleDrawable(ColorStateList.valueOf(color(R.color.cm_primary_container)),fill(R.color.cm_calendar_surface,24),null)
        elevation=dp(1).toFloat(); setOnClickListener { action() }
    }
    init {
        radius=dp(28).toFloat(); cardElevation=dp(2).toFloat(); strokeWidth=dp(1)
        strokeColor=color(R.color.cm_calendar_border); setCardBackgroundColor(color(R.color.cm_calendar_shell))
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val hero=FrameLayout(context)
        hero.addView(Scenery(context),FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(14)) }
        val title=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; isFocusable=true; contentDescription="Choose month or year"; setOnClickListener { onJump() } }
        title.addView(text(month.year.toString(),11f,true,R.color.cm_calendar_muted).apply { letterSpacing=0.24f })
        title.addView(text(month.format(DateTimeFormatter.ofPattern("MMMM")),36f,true).apply {
            maxLines=1; setPadding(0,dp(4),0,dp(3))
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,26,36,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(-1,dp((48*resources.configuration.fontScale.coerceAtLeast(1f)).toInt())))
        title.addView(text("$count ${if(count==1) "event" else "events"} this month",13f,false,R.color.cm_calendar_muted))
        header.addView(title)
        val controls=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(14),0,0) }
        controls.addView(MaterialButton(context).apply {
            text="Today"; isAllCaps=false; textSize=13f; cornerRadius=dp(24); icon=context.getDrawable(R.drawable.ic_notice_date_small)
            iconSize=dp(16); iconPadding=dp(8); setTextColor(color(R.color.cm_primary)); iconTint=ColorStateList.valueOf(color(R.color.cm_primary))
            backgroundTintList=ColorStateList.valueOf(color(R.color.cm_calendar_surface)); strokeWidth=0; insetTop=0; insetBottom=0
            elevation=dp(1).toFloat(); contentDescription="Go to today"; setOnClickListener { onToday() }
        },LinearLayout.LayoutParams(-2,dp(48)))
        controls.addView(View(context),LinearLayout.LayoutParams(0,1,1f))
        controls.addView(nav(R.drawable.ic_chevron_left,"Previous month",onPrevious),LinearLayout.LayoutParams(dp(48),dp(48)))
        controls.addView(nav(R.drawable.ic_chevron_right,"Next month",onNext),LinearLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(8) })
        header.addView(controls); hero.addView(header,FrameLayout.LayoutParams(-1,-2)); body.addView(hero)

        val grid=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; background=fill(R.color.cm_calendar_surface,22); setPadding(dp(4),dp(8),dp(4),dp(10)) }
        val days=LinearLayout(context).apply { background=fill(R.color.cm_calendar_weekday,14) }
        listOf("SUN","MON","TUE","WED","THU","FRI","SAT").forEachIndexed { index,name ->
            days.addView(text(name,9f,true,if(index in 4..5) R.color.cm_calendar_closed else R.color.cm_calendar_muted).apply {
                gravity=Gravity.CENTER; letterSpacing=0.04f; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(0,dp(30),1f))
        }
        grid.addView(days,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        val today=LocalDate.now()
        ClassMateCalendarPresentation.cells(month).chunked(7).forEach { dates ->
            val row=LinearLayout(context)
            dates.forEach { date ->
                val touch=FrameLayout(context)
                if(date!=null) {
                    val state=ClassMateCalendarPresentation.indicators(closed(date),scopes(date))
                    val active=date==selected || (selected==null && date==today)
                    val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; isDuplicateParentStateEnabled=true }
                    val foreground=when { active -> android.R.color.white; closed(date) -> R.color.cm_calendar_closed; ClassMateCalendarPresentation.Indicator.CLASS_HOLIDAY in state -> R.color.cm_calendar_classes; else -> R.color.cm_text_primary }
                    content.addView(text(date.dayOfMonth.toString(),16f,true,foreground).apply { gravity=Gravity.CENTER })
                    val dots=LinearLayout(context).apply { gravity=Gravity.CENTER }
                    state.forEach { indicator ->
                        dots.addView(View(context).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(color(if(active) android.R.color.white else tint(indicator))) } },LinearLayout.LayoutParams(dp(3),dp(3)).apply { leftMargin=dp(1); rightMargin=dp(1) })
                    }
                    content.addView(dots,LinearLayout.LayoutParams(-1,dp(4)).apply { topMargin=dp(3) })
                    val backgroundColor=when { active -> R.color.cm_calendar_selected; closed(date) -> R.color.cm_calendar_closed_bg; date==today -> R.color.cm_calendar_today_bg; else -> R.color.cm_calendar_cell }
                    content.background=RippleDrawable(ColorStateList.valueOf(color(R.color.cm_primary_container)),fill(backgroundColor,12),null)
                    if(active) { content.elevation=dp(3).toFloat(); emphasizedDate=content }
                    touch.addView(content,FrameLayout.LayoutParams(-1,-1).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
                    touch.isFocusable=true; touch.isSelected=date==selected; touch.contentDescription=description(date)+(if(date==today) ", today" else "")
                    content.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    touch.setOnClickListener { com.shuaib.classmate.ui.ClassMateHaptics.selection(it); onSelect(date) }
                } else touch.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                row.addView(touch,LinearLayout.LayoutParams(0,dp((48*resources.configuration.fontScale.coerceAtLeast(1f)).toInt()),1f))
            }
            grid.addView(row)
        }
        grid.addView(View(context).apply { setBackgroundColor(color(R.color.cm_calendar_border)) },LinearLayout.LayoutParams(-1,dp(1)).apply { setMargins(dp(8),dp(10),dp(8),dp(10)) })
        val legend=LinearLayout(context).apply { gravity=Gravity.CENTER }
        listOf(Triple("Closed",R.color.cm_calendar_closed,R.color.cm_calendar_closed_bg),Triple("Class holiday",R.color.cm_calendar_classes,R.color.cm_calendar_classes_bg),Triple("Event",R.color.cm_primary,R.color.cm_calendar_today_bg)).forEach { (label,tint,bg) ->
            val pill=LinearLayout(context).apply { gravity=Gravity.CENTER; setPadding(dp(5),dp(7),dp(5),dp(7)); background=fill(bg,20) }
            pill.addView(View(context).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(color(tint)) } },LinearLayout.LayoutParams(dp(5),dp(5)).apply { rightMargin=dp(4) })
            pill.addView(text(label,10f,false,R.color.cm_calendar_muted))
            legend.addView(pill,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(3); rightMargin=dp(3) })
        }
        grid.addView(legend)
        body.addView(grid,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(10),0,dp(10),dp(12)) })
        addView(body)
    }

    fun enter(direction: Int=0) {
        if(!ValueAnimator.areAnimatorsEnabled() || (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) return
        val target=if(direction==0) emphasizedDate ?: this else this
        target.alpha=0.7f; target.translationX=dp(10)*direction.toFloat()
        target.scaleX=if(direction==0) 0.94f else 1f; target.scaleY=target.scaleX
        target.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private class Scenery(context: Context): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w=width.toFloat(); val h=height.toFloat()
            fun tint(id:Int,alpha:Int) { paint.color=context.getColor(id); paint.alpha=alpha; paint.style=Paint.Style.FILL }
            // Artwork occupies the right side; the opaque title backing fades into the hills.
            tint(R.color.cm_calendar_sun,95); canvas.drawCircle(w*0.84f,h*0.3f,h*0.2f,paint)
            fun hill(start:Float,peak:Float,base:Float,color:Int,alpha:Int) {
                tint(color,alpha)
                val path=Path().apply { moveTo(w*start,h); cubicTo(w*0.70f,h*peak,w*0.83f,h*base,w,h*0.53f); lineTo(w,h); close() }
                canvas.drawPath(path,paint)
            }
            hill(0.45f,0.16f,0.64f,R.color.cm_calendar_hill,48)
            hill(0.58f,0.42f,0.73f,R.color.cm_primary,24)
            hill(0.72f,0.80f,0.6f,R.color.cm_calendar_hill,65)
            paint.color=context.getColor(R.color.cm_calendar_hill); paint.alpha=110; paint.strokeWidth=width*0.003f; paint.style=Paint.Style.STROKE
            val branch=Path().apply { moveTo(w*0.93f,h*0.66f); quadTo(w*0.9f,h*0.4f,w*0.89f,h*0.2f) }; canvas.drawPath(branch,paint)
            paint.style=Paint.Style.FILL; tint(R.color.cm_calendar_sun,145)
            for(i in 0..2) {
                val x=w*(0.89f+i*0.012f); val y=h*(0.27f+i*0.11f)
                canvas.save(); canvas.rotate(if(i%2==0) -28f else 28f,x,y); canvas.drawOval(x-w*0.012f,y-h*0.035f,x+w*0.012f,y+h*0.035f,paint); canvas.restore()
            }
        }
    }
}
