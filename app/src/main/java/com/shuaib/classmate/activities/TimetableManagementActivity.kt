// C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/TimetableManagementActivity.kt
package com.shuaib.classmate.activities

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.PeriodAdapter
import com.shuaib.classmate.databinding.ActivityTimetableManagementBinding
import com.shuaib.classmate.databinding.DialogAddPeriodBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.utils.SubjectList
import java.util.Calendar

class TimetableManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableManagementBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var periodAdapter: PeriodAdapter
    private val periodList = mutableListOf<Period>()
    private var currentDay = "saturday"

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

        binding.chipGroupDays.check(R.id.chipSat)
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
        binding.chipGroupDays.setOnCheckedStateChangeListener { _, checkedIds ->
            currentDay = when (checkedIds.firstOrNull()) {
                R.id.chipSat -> "saturday"
                R.id.chipSun -> "sunday"
                R.id.chipMon -> "monday"
                R.id.chipTue -> "tuesday"
                R.id.chipWed -> "wednesday"
                R.id.chipThu -> "thursday"
                R.id.chipFri -> "friday"
                else -> "saturday"
            }
            fetchTimetable(currentDay)
        }
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
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
