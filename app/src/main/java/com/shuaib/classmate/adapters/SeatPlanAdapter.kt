/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/adapters/SeatPlanAdapter.kt
 */
package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemSeatPlanBinding
import com.shuaib.classmate.models.SeatPlan
import com.shuaib.classmate.utils.applyClickAnimation
import java.text.SimpleDateFormat
import java.util.*

class SeatPlanAdapter(
    private var seatPlans: List<SeatPlan>,
    private val rootView: ViewGroup? = null,
    private val onSeatPlanClick: (SeatPlan) -> Unit
) : RecyclerView.Adapter<SeatPlanAdapter.SeatPlanViewHolder>() {

    var onItemBound: ((SeatPlanViewHolder, Int) -> Unit)? = null

    inner class SeatPlanViewHolder(val binding: ItemSeatPlanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeatPlanViewHolder {
        val binding = ItemSeatPlanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SeatPlanViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SeatPlanViewHolder, position: Int) {
        val seatPlan = seatPlans[position]
        holder.binding.apply {
            tvExamTitle.text = seatPlan.title
            
            val timestamp = seatPlan.timestamp
            if (timestamp != null) {
                val date = timestamp.toDate()
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                tvUploadDate.text = "Uploaded: ${sdf.format(date)}"
            } else {
                tvUploadDate.text = "Date unknown"
            }

            root.applyClickAnimation { onSeatPlanClick(seatPlan) }
        }
        onItemBound?.invoke(holder, position)
    }

    override fun getItemCount(): Int = seatPlans.size

    fun updateList(newList: List<SeatPlan>) {
        seatPlans = newList
        notifyDataSetChanged()
    }
}
