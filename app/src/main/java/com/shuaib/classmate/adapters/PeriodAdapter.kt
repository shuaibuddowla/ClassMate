// com/shuaib/classmate/adapters/PeriodAdapter.kt
package com.shuaib.classmate.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemPeriodBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.SubjectVisuals
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.animateSpringScale
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import android.view.animation.DecelerateInterpolator

class PeriodAdapter(
    private var periods: List<Period>,
    private var isPausedByCalendarException: Boolean = false,
    private var isViewingToday: Boolean = false,
    private val onPeriodClick: (Period) -> Unit = {},
    private val onPeriodLongClick: (Period) -> Unit = {}
) : RecyclerView.Adapter<PeriodAdapter.PeriodViewHolder>() {

    inner class PeriodViewHolder(val binding: ItemPeriodBinding) :
        RecyclerView.ViewHolder(binding.root)

    private var lastAnimatedPosition = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeriodViewHolder {
        val binding = ItemPeriodBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PeriodViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val period = periods[position]
        val b = holder.binding
        val context = holder.itemView.context

        b.root.animate().cancel()
        b.root.scaleX = 1f
        b.root.scaleY = 1f

        val visual = SubjectVisuals.forSubject(period.subject)
        val accent = visual.startColor
        val isLabSession = period.subject.trim().endsWith("lab", ignoreCase = true) || period.subject.trim().endsWith("labs", ignoreCase = true)

        b.tvSubject.text = period.subject
        b.tvTeacher.text = period.teacher
        b.tvStartTime.text = formatTo12Hour(period.startTime)
        b.tvEndTime.text = formatTo12Hour(period.endTime)
        b.tvDuration.text = durationLabel(period)
        b.tvTypeBadge.typeface = Typeface.DEFAULT_BOLD

        b.ivSubjectIcon.setImageResource(visual.iconRes)
        b.ivSubjectIcon.imageTintList = ColorStateList.valueOf(accent)
        b.layoutSubjectIcon.background = rounded(context, 12f, ColorUtils.setAlphaComponent(accent, 34))
        b.layoutStartClock.background = rounded(context, 50f, ColorUtils.setAlphaComponent(accent, 28))
        b.vStatusIndicator.backgroundTintList = ColorStateList.valueOf(accent)
        b.vEndDot.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 120))
        b.tvStartTime.setTextColor(accent)

        b.tvSubject.paintFlags = b.tvSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        b.cardRoot.alpha = if (isPausedByCalendarException) 0.58f else 1f
        b.root.isEnabled = !isPausedByCalendarException
        b.root.isClickable = !isPausedByCalendarException
        b.tvSubstituteMsg.isVisible = false
        b.layoutDuration.isVisible = true
        b.vCancelledDivider.isVisible = false

        val isLive = isViewingToday && !isPausedByCalendarException && !period.isCancelled && checkIsLive(period)
        if (isLive) {
            b.cardRoot.setBackgroundResource(R.drawable.bg_card_live)
            if (b.cardRoot.animation == null) {
                startLivePulse(b.cardRoot)
            }
            bindStatusColor(context, b, ThemeColors.success(context))
            b.tvTypeBadge.text = "LIVE NOW"
            b.tvTypeBadge.setBackgroundResource(R.drawable.bg_library_badge_green)
            b.tvTypeBadge.setTextColor(ThemeColors.success(context))
        } else {
            b.cardRoot.clearAnimation()
            when {
                isPausedByCalendarException -> {
                    bindStatusColor(context, b, ThemeColors.textMuted(context))
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_paused)
                    b.tvSubstituteMsg.isVisible = true
                    b.tvSubstituteMsg.text = "Class reminders are paused today"
                    b.tvTypeBadge.text = "PAUSED"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_paused)
                    b.tvTypeBadge.setTextColor(ThemeColors.textMuted(context))
                }

                period.isCancelled -> {
                    bindStatusColor(context, b, ThemeColors.error(context))
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_notice_cancel)
                    b.vEndDot.backgroundTintList = ColorStateList.valueOf(ThemeColors.error(context))
                    b.layoutStartClock.background = rounded(context, 50f, ColorUtils.setAlphaComponent(ThemeColors.error(context), 24))
                    b.tvTypeBadge.text = "CANCELLED"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_red)
                    b.tvTypeBadge.setTextColor(ThemeColors.error(context))
                    b.layoutDuration.isVisible = false
                    b.vCancelledDivider.isVisible = true
                }

                period.isSubstitute -> {
                    bindStatusColor(context, b, ThemeColors.warning(context))
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_notice_sub)
                    b.tvSubstituteMsg.isVisible = true
                    b.tvSubstituteMsg.text = "Substitute: ${period.substituteTeacher}"
                    b.tvTypeBadge.text = "SUBSTITUTE"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_purple)
                    b.tvTypeBadge.setTextColor(ThemeColors.warning(context))
                }

                else -> {
                    bindStatusColor(context, b, accent)
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_primary)
                    if (isLabSession) {
                        b.tvTypeBadge.text = "LAB"
                        b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_green)
                        b.tvTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.cm_file_lab_text))
                    } else {
                        b.tvTypeBadge.text = "CLASS"
                        b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_blue)
                        b.tvTypeBadge.setTextColor(ThemeColors.primary(context))
                    }
                }
            }
        }

        b.root.setOnClickListener {
            if (!isPausedByCalendarException) {
                it.animateSpringScale(0.97f)
                onPeriodClick(period)
            }
        }

        b.root.setOnLongClickListener {
            onPeriodLongClick(period)
            true
        }

        if (position > lastAnimatedPosition) {
            val density = context.resources.displayMetrics.density
            val delay = if (position > 4) 0L else (position * 45L)
            b.root.alpha = 0f
            b.root.translationY = 32f * density
            b.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .setDuration(350)
                .start()
            lastAnimatedPosition = position
        } else {
            b.root.alpha = 1f
            b.root.translationY = 0f
        }
    }

    private fun bindStatusColor(context: Context, b: ItemPeriodBinding, color: Int) {
        b.vStatusIndicator.backgroundTintList = ColorStateList.valueOf(color)
        b.vEndDot.backgroundTintList = ColorStateList.valueOf(color)
        b.tvStartTime.setTextColor(color)
        b.layoutStartClock.background = rounded(context, 50f, ColorUtils.setAlphaComponent(color, 28))
    }

    private fun formatTo12Hour(time24: String): String {
        return try {
            val time = LocalTime.parse(time24)
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
            time.format(formatter)
        } catch (e: Exception) {
            time24
        }
    }

    private fun durationLabel(period: Period): String {
        return try {
            val start = LocalTime.parse(period.startTime)
            val end = LocalTime.parse(period.endTime)
            val duration = Duration.between(start, end)
            val minutes = duration.toMinutes().toInt()
            val hours = minutes / 60
            val remainder = minutes % 60
            when {
                hours > 0 && remainder > 0 -> "${hours} h ${remainder} min"
                hours > 0 -> "${hours} h"
                else -> "${minutes} min"
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun checkIsLive(period: Period): Boolean {
        return try {
            val now = LocalTime.now()
            val start = LocalTime.parse(period.startTime)
            val end = LocalTime.parse(period.endTime)
            !now.isBefore(start) && now.isBefore(end)
        } catch (e: Exception) {
            false
        }
    }

    private fun rounded(context: Context, radiusDp: Float, color: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
        }
    }

    private fun startLivePulse(view: View) {
        val pulse = android.view.animation.ScaleAnimation(
            1f, 1.02f, 1f, 1.02f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        view.startAnimation(pulse)
    }

    override fun getItemCount(): Int = periods.size

    fun updateList(newList: List<Period>, isToday: Boolean, resetAnimation: Boolean = false) {
        periods = newList
        isViewingToday = isToday
        if (resetAnimation) {
            lastAnimatedPosition = -1
        }
        notifyDataSetChanged()
    }

    fun setPausedByCalendarException(paused: Boolean) {
        isPausedByCalendarException = paused
        notifyDataSetChanged()
    }
}
