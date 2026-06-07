// com/shuaib/classmate/adapters/PeriodAdapter.kt
package com.shuaib.classmate.adapters

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.Animation
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemPeriodBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.animateSpringScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PeriodAdapter(
    private var periods: List<Period>,
    private var isPausedByCalendarException: Boolean = false,
    private var isViewingToday: Boolean = false,
    private val onPeriodClick: (Period) -> Unit = {},
    private val onPeriodLongClick: (Period) -> Unit = {}
) : RecyclerView.Adapter<PeriodAdapter.PeriodViewHolder>() {

    inner class PeriodViewHolder(val binding: ItemPeriodBinding) :
        RecyclerView.ViewHolder(binding.root)

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

        b.tvSubject.text = period.subject
        b.tvTeacher.text = "👤 ${period.teacher}"
        b.tvStartTime.text = formatTo12Hour(period.startTime)
        b.tvEndTime.text = formatTo12Hour(period.endTime)

        b.cardRoot.alpha = if (isPausedByCalendarException) 0.58f else 1f
        b.root.isEnabled = !isPausedByCalendarException
        b.root.isClickable = !isPausedByCalendarException

        val isLive = isViewingToday && !isPausedByCalendarException && !period.isCancelled && checkIsLive(period)
        if (isLive) {
            b.cardRoot.setBackgroundResource(R.drawable.bg_card_live)
            if (b.cardRoot.animation == null) {
                startLivePulse(b.cardRoot)
            }
            b.tvTypeBadge.text = "● LIVE NOW"
            b.tvTypeBadge.setBackgroundResource(R.drawable.bg_library_badge_green)
            b.tvTypeBadge.setTextColor(ThemeColors.success(context))
        } else {
            b.cardRoot.clearAnimation()
            when {
                isPausedByCalendarException -> {
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_paused)
                    b.tvCancelledMsg.isVisible = false
                    b.tvSubstituteMsg.isVisible = true
                    b.tvSubstituteMsg.text = "Class reminders are paused today"
                    b.tvTypeBadge.text = "Ⅱ PAUSED"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_paused)
                    b.tvTypeBadge.setTextColor(ThemeColors.textMuted(context))
                    b.tvSubject.paintFlags = b.tvSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                period.isCancelled -> {
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_notice_cancel)
                    b.tvCancelledMsg.isVisible = true
                    b.tvCancelledMsg.text = "This class has been cancelled"
                    b.tvSubstituteMsg.isVisible = false
                    b.tvTypeBadge.text = "CANCELLED"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_red)
                    b.tvTypeBadge.setTextColor(ThemeColors.error(context))
                    b.tvSubject.paintFlags = b.tvSubject.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
                period.isSubstitute -> {
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_notice_sub)
                    b.tvSubstituteMsg.isVisible = true
                    b.tvSubstituteMsg.text = "🔄 Substitute: ${period.substituteTeacher}"
                    b.tvCancelledMsg.isVisible = false
                    b.tvTypeBadge.text = "SUBSTITUTE"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_purple)
                    b.tvTypeBadge.setTextColor(ThemeColors.warning(context))
                    b.tvSubject.paintFlags = b.tvSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                else -> {
                    b.cardRoot.setBackgroundResource(R.drawable.bg_card_primary)
                    b.tvCancelledMsg.isVisible = false
                    b.tvSubstituteMsg.isVisible = false
                    b.tvTypeBadge.text = "CLASS"
                    b.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_blue)
                    b.tvTypeBadge.setTextColor(ThemeColors.primary(context))
                    b.tvSubject.paintFlags = b.tvSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
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
    }

    private fun formatTo12Hour(time24: String): String {
        return try {
            val sdf24 = SimpleDateFormat("HH:mm", Locale.getDefault())
            val sdf12 = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val date = sdf24.parse(time24)
            date?.let { sdf12.format(it) } ?: time24
        } catch (e: Exception) {
            time24
        }
    }

    private fun checkIsLive(period: Period): Boolean {
        return try {
            val now = Calendar.getInstance()
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val start = sdf.parse(period.startTime)
            val end = sdf.parse(period.endTime)

            if (start == null || end == null) return false

            val startCal = Calendar.getInstance().apply { time = start }
            val endCal = Calendar.getInstance().apply { time = end }

            val startMinutes = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
            val endMinutes = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)

            currentMinutes in startMinutes until endMinutes
        } catch (e: Exception) {
            false
        }
    }

    private fun startLivePulse(view: android.view.View) {
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

    fun updateList(newList: List<Period>, isToday: Boolean) {
        periods = newList
        isViewingToday = isToday
        notifyDataSetChanged()
    }

    fun setPausedByCalendarException(paused: Boolean) {
        isPausedByCalendarException = paused
        notifyDataSetChanged()
    }
}
