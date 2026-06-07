package com.shuaib.classmate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemPeriodBinding

class PeriodAdapter(private val periods: List<Period>) :
    RecyclerView.Adapter<PeriodAdapter.PeriodViewHolder>() {

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
        holder.binding.tvSubject.text = period.subject
        holder.binding.tvTeacher.text = period.teacher
        holder.binding.tvStartTime.text = period.startTime
        holder.binding.tvEndTime.text = period.endTime
    }

    override fun getItemCount() = periods.size
}