package com.shuaib.classmate.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.shuaib.classmate.databinding.ItemAttendanceStudentBinding
import com.shuaib.classmate.utils.ThemeColors
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceDashboardAdapter(
    private val onStudentLongPress: (AttendanceStudentRow) -> Unit
) : ListAdapter<AttendanceDashboardAdapter.AttendanceStudentRow, AttendanceDashboardAdapter.StudentViewHolder>(
    DiffCallback
) {

    data class AttendanceStudentRow(
        val uid: String,
        val name: String,
        val status: String,
        val detectedAt: Timestamp?
    ) {
        val isPresent: Boolean
            get() = status == STATUS_PRESENT
    }

    inner class StudentViewHolder(
        val binding: ItemAttendanceStudentBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentViewHolder {
        val binding = ItemAttendanceStudentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StudentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StudentViewHolder, position: Int) {
        val row = getItem(position)
        val binding = holder.binding

        binding.tvStudentName.text = row.name
        binding.tvDetectedTime.text = when (row.status) {
            STATUS_PRESENT -> "Marked at ${formatTime(row.detectedAt)}"
            STATUS_ABSENT -> "Marked absent"
            else -> "Not marked yet"
        }

        binding.tvStatusBadge.text = when (row.status) {
            STATUS_PRESENT -> "Present"
            STATUS_ABSENT -> "Absent"
            else -> "Waiting..."
        }
        binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(
            when (row.status) {
                STATUS_PRESENT -> ThemeColors.success(binding.root.context)
                STATUS_ABSENT -> ThemeColors.error(binding.root.context)
                else -> ThemeColors.textMuted(binding.root.context)
            }
        )

        binding.root.setOnLongClickListener {
            onStudentLongPress(row)
            true
        }
    }

    private fun formatTime(timestamp: Timestamp?): String {
        if (timestamp == null) return "now"
        return timeFormatter.format(timestamp.toDate())
    }

    companion object {
        const val STATUS_PRESENT = "present"
        const val STATUS_ABSENT = "absent"
        const val STATUS_WAITING = "waiting"

        private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

        private val DiffCallback = object : DiffUtil.ItemCallback<AttendanceStudentRow>() {
            override fun areItemsTheSame(
                oldItem: AttendanceStudentRow,
                newItem: AttendanceStudentRow
            ): Boolean = oldItem.uid == newItem.uid

            override fun areContentsTheSame(
                oldItem: AttendanceStudentRow,
                newItem: AttendanceStudentRow
            ): Boolean = oldItem == newItem
        }
    }
}
