package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.shuaib.classmate.databinding.ItemAttendanceSessionCardBinding
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceSessionsAdapter(
    private val onClick: (AttendanceSessionRow) -> Unit,
    private val onDownload: ((AttendanceSessionRow) -> Unit)? = null
) : ListAdapter<AttendanceSessionsAdapter.AttendanceSessionRow, AttendanceSessionsAdapter.ViewHolder>(Diff) {

    data class AttendanceSessionRow(
        val sessionId: String,
        val subject: String,
        val startTime: Timestamp?,
        val endTime: Timestamp?,
        val presentCount: Int,
        val totalStudents: Int
    )

    inner class ViewHolder(val binding: ItemAttendanceSessionCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemAttendanceSessionCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        holder.binding.tvSubject.text = row.subject
        holder.binding.tvDate.text = row.startTime?.toDate()?.let { dateFormat.format(it) } ?: "-"
        val start = row.startTime?.toDate()?.let { timeFormat.format(it) } ?: "-"
        val end = row.endTime?.toDate()?.let { timeFormat.format(it) } ?: "-"
        holder.binding.tvTimeRange.text = "$start -> $end"
        holder.binding.tvPresentCount.text = "${row.presentCount} / ${row.totalStudents}"
        holder.binding.root.setOnClickListener { onClick(row) }
        if (onDownload != null) {
            holder.binding.btnDownload.visibility = ViewGroup.VISIBLE
            holder.binding.btnDownload.setOnClickListener { onDownload(row) }
        } else {
            holder.binding.btnDownload.visibility = ViewGroup.GONE
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        private val Diff = object : DiffUtil.ItemCallback<AttendanceSessionRow>() {
            override fun areItemsTheSame(oldItem: AttendanceSessionRow, newItem: AttendanceSessionRow) = oldItem.sessionId == newItem.sessionId
            override fun areContentsTheSame(oldItem: AttendanceSessionRow, newItem: AttendanceSessionRow) = oldItem == newItem
        }
    }
}
