package com.shuaib.classmate.activities

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.PeriodAdapter
import com.shuaib.classmate.databinding.ActivityTimetableManagementBinding
import com.shuaib.classmate.databinding.DialogAddPeriodBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.SubjectList
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.WidgetUpdater
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Calendar

class TimetableManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableManagementBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var periodAdapter: PeriodAdapter
    private val periodList = mutableListOf<Period>()
    private var currentDay = "saturday"

    private val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
    private val dayShort = listOf("SAT", "SUN", "MON", "TUE", "WED", "THU", "FRI")
    private val dayFull = listOf("Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        setupRecyclerView()
        setupDaySelector()
        setupSwipeToDelete()

        binding.toolbar.setNavigationOnClickListener { 
            finish()
        }

        binding.fabAddPeriod.setOnClickListener { showPeriodDialog(null) }
    }

    private fun setupRecyclerView() {
        periodAdapter = PeriodAdapter(
            periods = periodList,
            onPeriodClick = { period -> showPeriodDialog(period) }
        )

        binding.rvTimetable.apply {
            layoutManager = LinearLayoutManager(this@TimetableManagementActivity)
            adapter = periodAdapter
        }
    }

    private fun setupDaySelector() {
        val todayIndex = getTodayIndex()
        currentDay = days[todayIndex]
        fetchTimetable(currentDay)

        val weekDates = getWeekDates()
        val margin = (6 * resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(this)

        binding.daySelector.removeAllViews()
        days.forEachIndexed { index, _ ->
            val cardView = inflater.inflate(R.layout.item_day_card, binding.daySelector, false)

            cardView.findViewById<TextView>(R.id.tvDayShort).text = dayShort[index]
            cardView.findViewById<TextView>(R.id.tvDayDate).text =
                String.format("%02d", weekDates[index])

            val params = cardView.layoutParams as LinearLayout.LayoutParams
            params.setMargins(margin, margin / 2, margin, margin / 2)
            cardView.layoutParams = params

            cardView.setOnClickListener {
                selectDay(index)
            }

            binding.daySelector.addView(cardView)
        }

        applyDayCardStyles(todayIndex)
    }

    private fun selectDay(index: Int) {
        currentDay = days[index]
        fetchTimetable(currentDay)
        applyDayCardStyles(index)
    }

    private fun applyDayCardStyles(selectedIndex: Int) {
        val todayIndex = getTodayIndex()
        for (i in 0 until binding.daySelector.childCount) {
            val cardView = binding.daySelector.getChildAt(i)
            val tvDayShort = cardView.findViewById<TextView>(R.id.tvDayShort)
            val tvDayDate = cardView.findViewById<TextView>(R.id.tvDayDate)
            val vIndicator = cardView.findViewById<View>(R.id.vDayIndicator)

            when (i) {
                selectedIndex -> {
                    cardView.setBackgroundResource(R.drawable.bg_day_card_selected)
                    cardView.elevation = 5 * resources.displayMetrics.density
                    tvDayShort.setTextColor(0xCCFFFFFF.toInt())
                    tvDayDate.setTextColor(Color.WHITE)
                    vIndicator.visibility = View.VISIBLE
                }
                todayIndex -> {
                    cardView.setBackgroundResource(R.drawable.bg_day_card_unselected)
                    cardView.elevation = 2 * resources.displayMetrics.density
                    tvDayShort.setTextColor(ThemeColors.primary(this))
                    tvDayDate.setTextColor(ThemeColors.primary(this))
                    vIndicator.visibility = View.INVISIBLE
                }
                else -> {
                    cardView.setBackgroundResource(R.drawable.bg_day_card_unselected)
                    cardView.elevation = 2 * resources.displayMetrics.density
                    tvDayShort.setTextColor(ThemeColors.textDisabled(this))
                    tvDayDate.setTextColor(ThemeColors.textPrimary(this))
                    vIndicator.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun getTodayIndex(): Int {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> 0
            Calendar.SUNDAY -> 1
            Calendar.MONDAY -> 2
            Calendar.TUESDAY -> 3
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY -> 5
            Calendar.FRIDAY -> 6
            else -> 0
        }
    }

    private fun getWeekDates(): IntArray {
        val today = LocalDate.now()
        val currentDayOfWeek = today.dayOfWeek.value
        
        val stepsToSaturday = when (currentDayOfWeek) {
            DayOfWeek.SATURDAY.value -> 0
            DayOfWeek.SUNDAY.value -> -1
            DayOfWeek.MONDAY.value -> -2
            DayOfWeek.TUESDAY.value -> -3
            DayOfWeek.WEDNESDAY.value -> -4
            DayOfWeek.THURSDAY.value -> -5
            DayOfWeek.FRIDAY.value -> -6
            else -> 0
        }
        
        val dates = IntArray(7)
        val saturdayDate = today.plusDays(stepsToSaturday.toLong())
        for (i in 0..6) {
            dates[i] = saturdayDate.plusDays(i.toLong()).dayOfMonth
        }
        return dates
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val period = periodList[position]
                showDeleteConfirmation(period, position)
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.rvTimetable)
    }

    private fun showDeleteConfirmation(period: Period, position: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Period")
            .setMessage("Are you sure you want to delete ${period.subject}?")
            .setPositiveButton("Delete") { _, _ -> deletePeriod(period) }
            .setNegativeButton("Cancel") { _, _ -> periodAdapter.notifyItemChanged(position) }
            .show()
    }

    private fun fetchTimetable(day: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        firestore.collection("timetable").document(day)
            .collection("periods")
            .get()
            .addOnSuccessListener { documents ->
                binding.progressBar.visibility = View.GONE
                val fetchedPeriods = documents.mapNotNull { doc ->
                    doc.toObject(Period::class.java).copy(id = doc.id)
                }.sortedBy { it.startTime }

                periodList.clear()
                periodList.addAll(fetchedPeriods)

                if (periodList.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvTimetable.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvTimetable.visibility = View.VISIBLE
                    val isToday = day == getTodayName()
                    periodAdapter.updateList(periodList, isToday)
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getTodayName(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            else -> "saturday"
        }
    }

    private fun showPeriodDialog(period: Period?) {
        val dialogBinding = DialogAddPeriodBinding.inflate(LayoutInflater.from(this))
        val isEdit = period != null

        val subjectNames = SubjectList.subjects.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, subjectNames)
        dialogBinding.dropdownSubject.setAdapter(adapter)

        if (isEdit) {
            dialogBinding.dropdownSubject.setText("${period?.subject}", false)
            dialogBinding.etTeacher.setText(period?.teacher)
            dialogBinding.etStartTime.setText(period?.startTime)
            dialogBinding.etEndTime.setText(period?.endTime)
        }

        dialogBinding.etStartTime.setOnClickListener {
            showTimePicker { time -> dialogBinding.etStartTime.setText(time) }
        }

        dialogBinding.etEndTime.setOnClickListener {
            showTimePicker { time -> dialogBinding.etEndTime.setText(time) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) "Edit Period" else "Add New Period")
            .setView(dialogBinding.root)
            .setPositiveButton(if (isEdit) "Update" else "Add") { _, _ ->
                val selectedSubject = dialogBinding.dropdownSubject.text.toString().trim()
                val teacher = dialogBinding.etTeacher.text.toString().trim()
                val start = dialogBinding.etStartTime.text.toString()
                val end = dialogBinding.etEndTime.text.toString()

                if (selectedSubject.isNotEmpty() && teacher.isNotEmpty()) {
                    val updatedPeriod = Period(
                        id = period?.id ?: "",
                        subject = selectedSubject,
                        teacher = teacher,
                        startTime = start,
                        endTime = end
                    )
                    savePeriod(updatedPeriod, isEdit)
                } else {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimePicker(onTimeSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        
        // Use 24-hour mode for the internal storage to keep sorting easy
        TimePickerDialog(this, { _, hour, minute ->
            val time24 = String.format("%02d:%02d", hour, minute)
            onTimeSelected(time24)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }

    private fun savePeriod(period: Period, isEdit: Boolean) {
        val collection = firestore.collection("timetable").document(currentDay).collection("periods")
        val task = if (isEdit) {
            collection.document(period.id).set(period)
        } else {
            collection.add(period)
        }

        task.addOnSuccessListener {
            Toast.makeText(this, if (isEdit) "Period updated" else "Period added", Toast.LENGTH_SHORT).show()
            fetchTimetable(currentDay)
            refreshWidgetAfterTimetableChange(currentDay)
        }
        .addOnFailureListener { e ->
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePeriod(period: Period) {
        firestore.collection("timetable").document(currentDay)
            .collection("periods").document(period.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Period deleted", Toast.LENGTH_SHORT).show()
                fetchTimetable(currentDay)
                refreshWidgetAfterTimetableChange(currentDay)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshWidgetAfterTimetableChange(day: String) {
        lifecycleScope.launch {
            runCatching {
                TimetableRepository.getInstance(this@TimetableManagementActivity)
                    .syncDayFromFirestore(day, Source.DEFAULT)
            }
            if (day == DateHelper.todayDayString()) {
                WidgetUpdater.refresh(this@TimetableManagementActivity, syncTodayTimetable = false)
            }
        }
    }
}
