package com.shuaib.classmate.activities

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.BusScheduleAdapter
import com.shuaib.classmate.databinding.ActivityBusScheduleManagementBinding
import com.shuaib.classmate.databinding.DialogAddBusScheduleBinding
import com.shuaib.classmate.models.BusSchedule
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.data.remote.supabase.SupabaseScheduleRepository
import com.shuaib.classmate.domain.auth.SessionRepository
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.repositories.TimetableRepository
import kotlinx.coroutines.launch
import java.util.Calendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@AndroidEntryPoint
class BusScheduleManagementActivity : AppCompatActivity() {

    @Inject lateinit var v2ScheduleRepository: SupabaseScheduleRepository
    @Inject lateinit var v2SessionRepository: SessionRepository

    private lateinit var binding: ActivityBusScheduleManagementBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var busAdapter: BusScheduleAdapter
    private val busList = mutableListOf<BusSchedule>()
    private var currentType = "class_day" // "class_day" or "off_day"
    private var usingSupabase = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusScheduleManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        setupRecyclerView()
        setupCategoryToggle()
        setupSwipeToDelete()

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.fabAddBus.setOnClickListener {
            showBusDialog(null)
        }

        usingSupabase = v2ScheduleRepository.isConfigured

        if (usingSupabase) lifecycleScope.launch {
            v2SessionRepository.refresh().onSuccess { AppContextManager.applyV2Session(it) }
                .onFailure { error ->
                    binding.fabAddBus.isEnabled = false
                    Toast.makeText(this@BusScheduleManagementActivity, "V2 sign-in failed: ${error.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            fetchBusSchedules()
        } else fetchBusSchedules()
    }

    private fun setupRecyclerView() {
        busAdapter = BusScheduleAdapter(
            schedules = busList,
            onItemClick = { schedule -> showBusDialog(schedule) }
        )

        binding.rvBusSchedules.apply {
            layoutManager = LinearLayoutManager(this@BusScheduleManagementActivity)
            adapter = busAdapter
        }
    }

    private fun setupCategoryToggle() {
        binding.btnToggleClassDays.setOnClickListener {
            if (currentType == "class_day") return@setOnClickListener
            currentType = "class_day"
            updateToggleUI()
            fetchBusSchedules()
        }

        binding.btnToggleOffDays.setOnClickListener {
            if (currentType == "off_day") return@setOnClickListener
            currentType = "off_day"
            updateToggleUI()
            fetchBusSchedules()
        }
    }

    private fun updateToggleUI() {
        if (currentType == "class_day") {
            binding.btnToggleClassDays.setBackgroundResource(R.drawable.bg_toggle_item_selected)
            binding.btnToggleClassDays.setTextColor(Color.WHITE)
            binding.btnToggleOffDays.setBackgroundColor(Color.TRANSPARENT)
            binding.btnToggleOffDays.setTextColor(ThemeColors.textSecondary(this))
        } else {
            binding.btnToggleOffDays.setBackgroundResource(R.drawable.bg_toggle_item_selected)
            binding.btnToggleOffDays.setTextColor(Color.WHITE)
            binding.btnToggleClassDays.setBackgroundColor(Color.TRANSPARENT)
            binding.btnToggleClassDays.setTextColor(ThemeColors.textSecondary(this))
        }
    }

    private fun setupSwipeToDelete() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val schedule = busList[position]
                showDeleteConfirmation(schedule, position)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvBusSchedules)
    }

    private fun showDeleteConfirmation(schedule: BusSchedule, position: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Bus Departure")
            .setMessage("Are you sure you want to delete the ${schedule.time} departure of ${schedule.busName}?")
            .setPositiveButton("Delete") { _, _ -> deleteBusSchedule(schedule) }
            .setNegativeButton("Cancel") { _, _ -> busAdapter.notifyItemChanged(position) }
            .show()
    }

    private fun fetchBusSchedules() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        if (usingSupabase) {
            lifecycleScope.launch {
                val result = v2ScheduleRepository.loadAllBusDepartures()
                binding.progressBar.visibility = View.GONE
                result.onSuccess { departures ->
                    val schedules = departures.map { departure ->
                        BusSchedule(
                            id = departure.id,
                            time = departure.departureTime.take(5),
                            departureFrom = departure.origin,
                            busName = departure.routeName,
                            route = departure.notes.orEmpty(),
                            scheduleType = if (departure.weekdays.any { it in OFF_DAYS }) "off_day" else "class_day",
                            destination = departure.destination,
                            weekdays = departure.weekdays,
                            notes = departure.notes
                        )
                    }
                    val repository = TimetableRepository.getInstance(this@BusScheduleManagementActivity)
                    WEEKDAYS.forEach { weekday ->
                        repository.cacheBusSchedules(weekday, schedules.filter { weekday in it.weekdays })
                    }
                    updateSchedules(schedules.filter { schedule ->
                        if (currentType == "off_day") schedule.weekdays.any { it in OFF_DAYS }
                        else schedule.weekdays.any { it in CLASS_DAYS }
                    })
                }.onFailure { error ->
                    val repository = TimetableRepository.getInstance(this@BusScheduleManagementActivity)
                    val cached = WEEKDAYS.flatMap { weekday -> repository.getCachedBusSchedules(weekday) }.distinctBy { it.id }
                    updateSchedules(cached.filter {
                        if (currentType == "off_day") it.weekdays.any { weekday -> weekday in OFF_DAYS }
                        else it.weekdays.any { weekday -> weekday in CLASS_DAYS }
                    })
                    Toast.makeText(this@BusScheduleManagementActivity, "${if (cached.isEmpty()) "Could not load" else "Showing saved"} bus schedules: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        firestore.collection("bus_schedules")
            .whereEqualTo("scheduleType", currentType)
            .get()
            .addOnSuccessListener { documents ->
                binding.progressBar.visibility = View.GONE
                val fetched = documents.mapNotNull { doc ->
                    doc.toObject(BusSchedule::class.java).copy(id = doc.id)
                }.sortedBy { parseTimeToMinutes(it.time) }

                updateSchedules(fetched)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateSchedules(schedules: List<BusSchedule>) {
        busList.clear()
        busList.addAll(schedules.sortedBy { parseTimeToMinutes(it.time) })
        binding.tvEmptyState.visibility = if (busList.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBusSchedules.visibility = if (busList.isEmpty()) View.GONE else View.VISIBLE
        busAdapter.updateList(busList)
    }

    private fun showBusDialog(schedule: BusSchedule?) {
        val dialogBinding = DialogAddBusScheduleBinding.inflate(LayoutInflater.from(this))
        val isEdit = schedule != null

        if (isEdit) {
            dialogBinding.tvDialogTitle.text = "Edit Bus Schedule"
            dialogBinding.etTime.setText(schedule?.time)
            dialogBinding.etBusName.setText(schedule?.busName)
            dialogBinding.etRoute.setText(schedule?.route)
            if (!schedule?.destination.isNullOrBlank()) {
                if (schedule?.departureFrom.equals("City", true)) dialogBinding.rbCity.isChecked = true
                else dialogBinding.rbCampus.isChecked = true
            }
            if (schedule?.departureFrom.equals("City", ignoreCase = true)) {
                dialogBinding.rbCity.isChecked = true
            } else {
                dialogBinding.rbCampus.isChecked = true
            }
        }

        dialogBinding.etTime.setOnClickListener {
            showTimePicker { time -> dialogBinding.etTime.setText(time) }
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(if (isEdit) "Update" else "Add") { _, _ ->
                val time = dialogBinding.etTime.text.toString().trim()
                val busName = dialogBinding.etBusName.text.toString().trim()
                val route = dialogBinding.etRoute.text.toString().trim()
                val departureFrom = if (dialogBinding.rbCity.isChecked) "City" else "Campus"

                if (time.isNotEmpty() && busName.isNotEmpty()) {
                    val updated = BusSchedule(
                        id = schedule?.id ?: "",
                        time = time,
                        departureFrom = departureFrom,
                        busName = busName,
                        route = route,
                        scheduleType = currentType,
                        destination = schedule?.destination.orEmpty(),
                        weekdays = schedule?.weekdays.orEmpty(),
                        notes = route
                    )
                    saveBusSchedule(updated, isEdit)
                } else {
                    Toast.makeText(this, "Please enter time and bus details", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimePicker(onTimeSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            val amPm = if (selectedHour < 12) "AM" else "PM"
            val displayHour = when {
                selectedHour == 0 -> 12
                selectedHour > 12 -> selectedHour - 12
                else -> selectedHour
            }
            val formattedTime = String.format("%02d:%02d %s", displayHour, selectedMinute, amPm)
            onTimeSelected(formattedTime)
        }, hour, minute, false).show()
    }

    private fun saveBusSchedule(schedule: BusSchedule, isEdit: Boolean) {
        binding.progressBar.visibility = View.VISIBLE

        if (usingSupabase) {
            lifecycleScope.launch {
                val origin = schedule.departureFrom
                val destination = schedule.destination.ifBlank { if (origin.equals("Campus", true)) "City" else "Campus" }
                val time24 = runCatching {
                    LocalTime.parse(schedule.time.uppercase(Locale.US), DateTimeFormatter.ofPattern("hh:mm a", Locale.US)).toString()
                }.getOrElse { schedule.time }
                val weekdays = schedule.weekdays.ifEmpty {
                    if (schedule.scheduleType == "off_day") listOf(4, 5) else listOf(0, 1, 2, 3, 6)
                }
                val result = if (isEdit) {
                    v2ScheduleRepository.updateBus(schedule.id, schedule.busName, time24, origin, destination, weekdays, schedule.route)
                } else {
                    v2ScheduleRepository.createBus(schedule.busName, time24, origin, destination, weekdays, schedule.route)
                }
                binding.progressBar.visibility = View.GONE
                result.onSuccess {
                    Toast.makeText(this@BusScheduleManagementActivity, if (isEdit) "Bus schedule updated" else "Bus schedule added", Toast.LENGTH_SHORT).show()
                    fetchBusSchedules()
                }.onFailure { error ->
                    Toast.makeText(this@BusScheduleManagementActivity, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        
        val data = hashMapOf(
            "time" to schedule.time,
            "departureFrom" to schedule.departureFrom,
            "busName" to schedule.busName,
            "route" to schedule.route,
            "scheduleType" to schedule.scheduleType
        )

        val task = if (isEdit) {
            firestore.collection("bus_schedules").document(schedule.id).set(data)
        } else {
            firestore.collection("bus_schedules").add(data)
        }

        task.addOnSuccessListener {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, if (isEdit) "Bus schedule updated" else "Bus schedule added", Toast.LENGTH_SHORT).show()
            fetchBusSchedules()
        }.addOnFailureListener { e ->
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteBusSchedule(schedule: BusSchedule) {
        binding.progressBar.visibility = View.VISIBLE
        if (usingSupabase) {
            lifecycleScope.launch {
                v2ScheduleRepository.deleteBus(schedule.id).onSuccess {
                    Toast.makeText(this@BusScheduleManagementActivity, "Bus departure deleted", Toast.LENGTH_SHORT).show()
                    fetchBusSchedules()
                }.onFailure { error ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@BusScheduleManagementActivity, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
                    fetchBusSchedules()
                }
            }
            return
        }
        firestore.collection("bus_schedules").document(schedule.id).delete()
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Bus departure deleted", Toast.LENGTH_SHORT).show()
                fetchBusSchedules()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                fetchBusSchedules()
            }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        try {
            if (timeStr.matches(Regex("^\\d{2}:\\d{2}$"))) {
                val parts = timeStr.split(":")
                return parts[0].toInt() * 60 + parts[1].toInt()
            }
            val parts = timeStr.trim().split(" ")
            if (parts.size != 2) return 0
            val timeParts = parts[0].split(":")
            if (timeParts.size != 2) return 0
            var hour = timeParts[0].toInt()
            val minute = timeParts[1].toInt()
            val amPm = parts[1].uppercase()
            
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
            return hour * 60 + minute
        } catch (e: Exception) {
            return 0
        }
    }

    private companion object {
        val WEEKDAYS = (0..6).toList()
        val OFF_DAYS = setOf(4, 5)
        val CLASS_DAYS = setOf(0, 1, 2, 3, 6)
    }
}
