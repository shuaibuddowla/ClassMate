package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemAcademicCalendarExceptionBinding
import com.shuaib.classmate.models.AcademicCalendarException

class AcademicCalendarExceptionAdapter(
    items: List<AcademicCalendarException> = emptyList(),
    private val onClick: (AcademicCalendarException) -> Unit,
    private val onLongClick: (AcademicCalendarException) -> Unit
) : ListAdapter<AcademicCalendarException, AcademicCalendarExceptionAdapter.ExceptionViewHolder>(DiffCallback) {

    init {
        if (items.isNotEmpty()) submitList(items)
    }

    inner class ExceptionViewHolder(val binding: ItemAcademicCalendarExceptionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExceptionViewHolder {
        val binding = ItemAcademicCalendarExceptionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ExceptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExceptionViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.tvTitle.text = item.title
        b.tvMeta.text = "${displayType(item.type)} - ${item.startDate} to ${item.endDate}"
        b.tvReason.text = item.reason.ifBlank { "All classes" }
        b.tvState.text = if (item.isActive) "ACTIVE" else "OFF"

        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    private fun displayType(type: String): String = when (type) {
        AcademicCalendarException.TYPE_VACATION -> "Vacation"
        AcademicCalendarException.TYPE_HOLIDAY -> "Holiday"
        AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "Classes Suspended"
        else -> type
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<AcademicCalendarException>() {
            override fun areItemsTheSame(oldItem: AcademicCalendarException, newItem: AcademicCalendarException): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: AcademicCalendarException, newItem: AcademicCalendarException): Boolean =
                oldItem == newItem
        }
    }
}
