package com.shuaib.classmate

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: FirebaseFirestore
    private val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday")
    private var selectedDay = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()

        // Get today's day
        val calendar = Calendar.getInstance()
        val dayIndex = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> 0
            Calendar.SUNDAY -> 1
            Calendar.MONDAY -> 2
            Calendar.TUESDAY -> 3
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY -> 5
            else -> 0
        }

        selectedDay = days[dayIndex]
        binding.tvDay.text = selectedDay.replaceFirstChar { it.uppercase() }

        // Build day selector buttons
        days.forEach { day ->
            val btn = Button(this)
            btn.text = day.take(3).replaceFirstChar { it.uppercase() }
            btn.isAllCaps = false
            btn.setPadding(24, 8, 24, 8)
            btn.gravity = Gravity.CENTER

            if (day == selectedDay) {
                btn.setBackgroundColor(0xFF1976D2.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(0xFFFFFFFF.toInt())
                btn.setTextColor(0xFF1976D2.toInt())
            }

            btn.setOnClickListener {
                selectedDay = day
                binding.tvDay.text = day.replaceFirstChar { it.uppercase() }
                loadTimetable(day)
                // Reset all buttons then highlight selected
                refreshDayButtons(day)
            }

            binding.daySelector.addView(btn)
        }

        // Setup RecyclerView
        binding.rvPeriods.layoutManager = LinearLayoutManager(this)

        // Load today's timetable
        loadTimetable(selectedDay)
    }

    private fun loadTimetable(day: String) {
        db.collection("timetable").document(day)
            .collection("periods")
            .orderBy("startTime")
            .get()
            .addOnSuccessListener { result ->
                val periods = result.map { doc ->
                    Period(
                        id = doc.id,
                        subject = doc.getString("subject") ?: "",
                        teacher = doc.getString("teacher") ?: "",
                        startTime = doc.getString("startTime") ?: "",
                        endTime = doc.getString("endTime") ?: ""
                    )
                }

                if (periods.isEmpty()) {
                    Toast.makeText(this, "No classes for $day", Toast.LENGTH_SHORT).show()
                }

                binding.rvPeriods.adapter = PeriodAdapter(periods)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load timetable", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshDayButtons(selectedDay: String) {
        for (i in 0 until binding.daySelector.childCount) {
            val btn = binding.daySelector.getChildAt(i) as Button
            if (days[i] == selectedDay) {
                btn.setBackgroundColor(0xFF1976D2.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(0xFFFFFFFF.toInt())
                btn.setTextColor(0xFF1976D2.toInt())
            }
        }
    }
}