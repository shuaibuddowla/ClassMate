package com.shuaib.classmate.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemBusScheduleBinding
import com.shuaib.classmate.models.BusSchedule
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.animateSpringScale

class BusScheduleAdapter(
    private var schedules: List<BusSchedule>,
    private val onItemClick: (BusSchedule) -> Unit = {}
) : RecyclerView.Adapter<BusScheduleAdapter.BusViewHolder>() {

    inner class BusViewHolder(val binding: ItemBusScheduleBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusViewHolder {
        val binding = ItemBusScheduleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BusViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BusViewHolder, position: Int) {
        val schedule = schedules[position]
        val b = holder.binding
        val context = holder.itemView.context

        b.tvBusName.text = schedule.busName
        b.tvRoute.text = schedule.route
        b.tvDepartureTime.text = schedule.time
        b.tvDirectionBadge.text = "From ${schedule.departureFrom}"

        val isFromCampus = schedule.departureFrom.equals("Campus", ignoreCase = true)

        if (isFromCampus) {
            val greenColor = ThemeColors.success(context)
            b.vStatusIndicator.backgroundTintList = ColorStateList.valueOf(greenColor)
            b.ivBusIcon.imageTintList = ColorStateList.valueOf(greenColor)
            b.layoutIconBg.setBackgroundColor(ColorUtils.setAlphaComponent(greenColor, 24))
            b.tvDirectionBadge.setBackgroundResource(R.drawable.bg_badge_green)
            b.tvDirectionBadge.setTextColor(greenColor)
            b.tvDepartureTime.setTextColor(greenColor)
        } else {
            val blueColor = ThemeColors.primary(context)
            b.vStatusIndicator.backgroundTintList = ColorStateList.valueOf(blueColor)
            b.ivBusIcon.imageTintList = ColorStateList.valueOf(blueColor)
            b.layoutIconBg.setBackgroundColor(ColorUtils.setAlphaComponent(blueColor, 24))
            b.tvDirectionBadge.setBackgroundResource(R.drawable.bg_badge_blue)
            b.tvDirectionBadge.setTextColor(blueColor)
            b.tvDepartureTime.setTextColor(blueColor)
        }

        b.root.setOnClickListener {
            it.animateSpringScale(0.97f)
            onItemClick(schedule)
        }
    }

    override fun getItemCount(): Int = schedules.size

    fun updateList(newSchedules: List<BusSchedule>) {
        schedules = newSchedules
        notifyDataSetChanged()
    }
}
