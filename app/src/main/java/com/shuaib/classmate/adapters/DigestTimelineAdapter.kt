package com.shuaib.classmate.adapters

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemDigestTimelineBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.ThemeColors

class DigestTimelineAdapter(
    private var periods: List<Period> = emptyList()
) : RecyclerView.Adapter<DigestTimelineAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDigestTimelineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDigestTimelineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val period = periods[position]
        val ctx = holder.binding.root.context
        val isLast = position == periods.size - 1
        
        holder.binding.apply {
            tvTimelineTime.text = "${period.startTime} – ${period.endTime}"
            tvTimelineSubject.text = period.subject
            tvTimelineTeacher.text = if (period.isSubstitute && period.substituteTeacher.isNotBlank()) {
                "${period.substituteTeacher} (Substitute)"
            } else {
                period.teacher
            }
            
            // Hide line connector for last item
            viewTimelineLine.isVisible = !isLast
            
            // Cancelled state
            if (period.isCancelled) {
                tvTimelineSubject.paintFlags = tvTimelineSubject.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tvTimelineSubject.alpha = 0.5f
                tvTimelineTeacher.alpha = 0.5f
                tvTimelineTime.alpha = 0.5f
                tvCancelledBadge.isVisible = true
                viewTimelineDot.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeColors.error(ctx))
            } else {
                tvTimelineSubject.paintFlags = tvTimelineSubject.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvTimelineSubject.alpha = 1f
                tvTimelineTeacher.alpha = 1f
                tvTimelineTime.alpha = 1f
                tvCancelledBadge.isVisible = false
                val dotColor = if (period.isSubstitute) ThemeColors.warning(ctx) else ThemeColors.primary(ctx)
                viewTimelineDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)
            }
        }
    }

    override fun getItemCount() = periods.size

    fun updateList(newPeriods: List<Period>) {
        periods = newPeriods
        notifyDataSetChanged()
    }
}
