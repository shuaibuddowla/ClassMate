// com/shuaib/classmate/adapters/CountdownAdapter.kt
package com.shuaib.classmate.adapters

import android.graphics.Color
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemAssignmentCountdownBinding
import com.shuaib.classmate.models.Assignment
import java.util.Calendar

class CountdownAdapter(
    private val assignments: MutableList<Assignment>,
    private val onRemove: (Assignment) -> Unit
) : RecyclerView.Adapter<CountdownAdapter.CountdownViewHolder>() {

    // Timer map to hold CountDownTimer per position
    private val timers = mutableMapOf<String, CountDownTimer>()

    inner class CountdownViewHolder(
        val binding: ItemAssignmentCountdownBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup, viewType: Int
    ): CountdownViewHolder {
        val binding = ItemAssignmentCountdownBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CountdownViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: CountdownViewHolder,
        position: Int
    ) {
        val assignment = assignments[position]
        val b = holder.binding
        val label = if (assignment.type == "class_test") "CLASS TEST" else "ASSIGNMENT"

        b.tvSubject.text = assignment.subject
        b.tvTopic.text = assignment.topic
        b.tvDueDate.text = "Due: ${assignment.submissionDate}"
        b.tvTypeLabel.text = "$label FOR"
        b.btnRemove.setOnClickListener {
            timers[assignment.id]?.cancel()
            onRemove(assignment)
        }

        // Cancel existing timer for this assignment
        timers[assignment.id]?.cancel()

        // Parse deadline
        val sdf = java.text.SimpleDateFormat(
            "yyyy-MM-dd", java.util.Locale.getDefault()
        )
        val deadlineDate = try {
            sdf.parse(assignment.submissionDate)
        } catch (e: Exception) { null }

        if (deadlineDate == null) {
            b.tvDays.text = "--"
            b.tvHours.text = "--"
            b.tvMinutes.text = "--"
            b.tvUrgency.text = "CHECK DATE"
            b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_purple)
            b.tvUrgency.setTextColor(Color.parseColor("#FBBF24"))
            return
        }

        // Set deadline to end of that day 23:59:59
        val deadlineCalendar = Calendar.getInstance().apply {
            time = deadlineDate
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }

        val deadlineMillis = deadlineCalendar.timeInMillis
        val now = System.currentTimeMillis()
        val remaining = deadlineMillis - now

        if (remaining <= 0) {
            // Deadline passed
            b.tvDays.text = "00"
            b.tvHours.text = "00"
            b.tvMinutes.text = "00"
            b.tvUrgency.text = "OVERDUE"
            b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_red)
            b.tvUrgency.setTextColor(Color.parseColor("#FF4D6D"))
            return
        }

        // Set urgency badge
        val daysLeft = remaining / (1000 * 60 * 60 * 24)
        when {
            daysLeft == 0L -> {
                b.tvUrgency.text = "DUE TODAY"
                b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_red)
                b.tvUrgency.setTextColor(Color.parseColor("#FF4D6D"))
            }
            daysLeft == 1L -> {
                b.tvUrgency.text = "DUE TOMORROW"
                b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_purple)
                b.tvUrgency.setTextColor(Color.parseColor("#FBBF24"))
            }
            daysLeft <= 3L -> {
                b.tvUrgency.text = "SOON"
                b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_purple)
                b.tvUrgency.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                b.tvUrgency.text = "ON TRACK"
                b.tvUrgency.setBackgroundResource(R.drawable.bg_badge_blue)
                b.tvUrgency.setTextColor(Color.parseColor("#3B82F6"))
            }
        }

        // Start live countdown timer
        val timer = object : CountDownTimer(remaining, 60000) {
            override fun onTick(millisUntilFinished: Long) {
                val d = millisUntilFinished / (1000 * 60 * 60 * 24)
                val h = (millisUntilFinished % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                val m = (millisUntilFinished % (1000 * 60 * 60)) / (1000 * 60)

                b.tvDays.text = String.format("%02d", d)
                b.tvHours.text = String.format("%02d", h)
                b.tvMinutes.text = String.format("%02d", m)
            }

            override fun onFinish() {
                b.tvDays.text = "00"
                b.tvHours.text = "00"
                b.tvMinutes.text = "00"
                b.tvUrgency.text = "OVERDUE"
            }
        }
        timer.start()
        timers[assignment.id] = timer

        // Initial values
        val d = remaining / (1000 * 60 * 60 * 24)
        val h = (remaining % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
        val m = (remaining % (1000 * 60 * 60)) / (1000 * 60)
        b.tvDays.text = String.format("%02d", d)
        b.tvHours.text = String.format("%02d", h)
        b.tvMinutes.text = String.format("%02d", m)

    }

    override fun getItemCount() = assignments.size

    // Cancel all timers when adapter is destroyed
    fun cancelAllTimers() {
        timers.values.forEach { it.cancel() }
        timers.clear()
    }
}
