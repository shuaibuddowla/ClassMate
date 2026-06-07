package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemAcademicCalendarExceptionBinding
import com.shuaib.classmate.models.AcademicCalendarException

class AcademicCalendarExceptionAdapter(
    private var items: List<AcademicCalendarException> = emptyList(),
    private val onClick: (AcademicCalendarException) -> Unit,
    private val onLongClick: (AcademicCalendarException) -> Unit
) : RecyclerView.Adapter<AcademicCalendarExceptionAdapter.ExceptionViewHolder>() {

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
        val item = items[position]
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

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<AcademicCalendarException>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun displayType(type: String): String = when (type) {
        AcademicCalendarException.TYPE_VACATION -> "Vacation"
        AcademicCalendarException.TYPE_HOLIDAY -> "Holiday"
        AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "Classes Suspended"
        else -> type
    }
}
