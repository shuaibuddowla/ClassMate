package com.shuaib.classmate.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemDigestDeadlineBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.utils.ThemeColors
import java.util.Date

class DigestDeadlineAdapter(
    private var deadlines: List<Notice> = emptyList(),
    private val onItemClick: (Notice) -> Unit
) : RecyclerView.Adapter<DigestDeadlineAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDigestDeadlineBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDigestDeadlineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notice = deadlines[position]
        val ctx = holder.binding.root.context
        
        holder.binding.apply {
            tvDeadlineSubject.text = notice.displaySubject.uppercase()
            tvDeadlineTitle.text = notice.title
            tvDeadlineBody.text = notice.body
            
            val deadlineDate = notice.deadlineAt?.toDate()
            if (deadlineDate != null) {
                val now = Date()
                val diffMs = deadlineDate.time - now.time
                val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()
                
                val (urgencyColor, countdownText) = when {
                    diffMs <= 0 -> {
                        Pair(ThemeColors.error(ctx), "Overdue")
                    }
                    diffDays == 0 -> {
                        Pair(ThemeColors.error(ctx), "Due today")
                    }
                    diffDays == 1 -> {
                        Pair(ThemeColors.warning(ctx), "1 day left")
                    }
                    diffDays <= 2 -> {
                        Pair(ThemeColors.warning(ctx), "$diffDays days left")
                    }
                    diffDays <= 7 -> {
                        Pair(Color.parseColor("#FFC107"), "$diffDays days left")
                    }
                    else -> {
                        Pair(ThemeColors.success(ctx), "$diffDays days left")
                    }
                }
                
                viewUrgencyDot.backgroundTintList = ColorStateList.valueOf(urgencyColor)
                tvDeadlineCountdown.text = countdownText
                tvDeadlineCountdown.setTextColor(if (diffDays <= 2) urgencyColor else ThemeColors.textSecondary(ctx))
            } else {
                viewUrgencyDot.backgroundTintList = ColorStateList.valueOf(ThemeColors.textMuted(ctx))
                tvDeadlineCountdown.text = "No date"
                tvDeadlineCountdown.setTextColor(ThemeColors.textMuted(ctx))
            }
            
            root.setOnClickListener { onItemClick(notice) }
        }
    }

    override fun getItemCount() = deadlines.size

    fun updateList(newDeadlines: List<Notice>) {
        deadlines = newDeadlines
        notifyDataSetChanged()
    }
}
