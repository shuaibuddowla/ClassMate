package com.shuaib.classmate.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemAttendanceSummaryBinding

class AttendanceSummaryAdapter(
    private val onClick: (AttendanceSummaryRow) -> Unit
) : ListAdapter<AttendanceSummaryAdapter.AttendanceSummaryRow, AttendanceSummaryAdapter.ViewHolder>(Diff) {

    data class AttendanceSummaryRow(
        val studentUid: String,
        val studentName: String,
        val present: Int,
        val total: Int
    ) {
        val percentage: Int get() = if (total == 0) 0 else (present * 100 / total)
    }

    inner class ViewHolder(val binding: ItemAttendanceSummaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemAttendanceSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val color = when {
            row.percentage >= 75 -> Color.parseColor("#34D399")
            row.percentage >= 50 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#FF4D6D")
        }
        holder.binding.tvStudentName.text = row.studentName
        holder.binding.tvPresentTotal.text = "${row.present} / ${row.total} present"
        holder.binding.tvPercentage.text = "${row.percentage}%"
        holder.binding.tvPercentage.setTextColor(color)
        holder.binding.progressAttendance.progress = row.percentage
        holder.binding.progressAttendance.progressTintList = ColorStateList.valueOf(color)
        holder.binding.root.setOnClickListener { onClick(row) }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<AttendanceSummaryRow>() {
            override fun areItemsTheSame(oldItem: AttendanceSummaryRow, newItem: AttendanceSummaryRow) = oldItem.studentUid == newItem.studentUid
            override fun areContentsTheSame(oldItem: AttendanceSummaryRow, newItem: AttendanceSummaryRow) = oldItem == newItem
        }
    }
}
