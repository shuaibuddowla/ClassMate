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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.PeriodAdapter
import com.shuaib.classmate.databinding.ActivityTimetableManagementBinding
import com.shuaib.classmate.databinding.DialogAddPeriodBinding
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.repositories.NoticeRepository
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.SemesterManager
import com.shuaib.classmate.utils.CoursePicker
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.WidgetUpdater
import com.shuaib.classmate.utils.toTimetablePeriods
import com.shuaib.classmate.data.remote.supabase.SupabaseScheduleRepository
import com.shuaib.classmate.data.remote.supabase.CourseOfferingOption
import com.shuaib.classmate.domain.auth.SessionRepository
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.NotificationSender
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Calendar

@AndroidEntryPoint
class TimetableManagementActivity : AppCompatActivity() {

    @Inject lateinit var v2ScheduleRepository: SupabaseScheduleRepository
    @Inject lateinit var v2SessionRepository: SessionRepository

    private lateinit var binding: ActivityTimetableManagementBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var periodAdapter: PeriodAdapter
    private val periodList = mutableListOf<Period>()
    private var currentDay = "saturday"
    private var courseListener: ListenerRegistration? = null
    private var usingSupabase = false
    private var offeringOptions: List<CourseOfferingOption> = emptyList()

    private val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
    private val dayShort = listOf("SAT", "SUN", "MON", "TUE", "WED", "THU", "FRI")
    private val dayFull = listOf("Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        usingSupabase = v2ScheduleRepository.isConfigured
        setupRecyclerView()
        setupDaySelector()
        setupSwipeToDelete()

        binding.toolbar.subtitle = "Managing ${SemesterManager.getActiveSemesterDisplay()}"
        binding.toolbar.setNavigationOnClickListener { 
            finish()
        }

        binding.fabAddPeriod.setOnClickListener { showPeriodDialog(null) }
        if (usingSupabase) lifecycleScope.launch {
            if (!AppContextManager.appContextFlow.value.v2SessionActive) {
                v2SessionRepository.refresh().onSuccess { profile ->
                    AppContextManager.applyV2Session(profile)
                }.onFailure { error ->
                    binding.fabAddPeriod.isEnabled = false
                    Toast.makeText(this@TimetableManagementActivity, "V2 sign-in failed: ${error.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            v2ScheduleRepository.manageableOfferings(
                AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
                SemesterManager.getActiveSemester()
            ).onSuccess { options ->
                offeringOptions = options
                fetchTimetable(currentDay)
            }.onFailure { error ->
                binding.fabAddPeriod.isEnabled = false
                Toast.makeText(this@TimetableManagementActivity, "V2 schedule setup is unavailable: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (usingSupabase) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(10000)
                    val batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }
                    v2ScheduleRepository.manageableOfferings(batchId, SemesterManager.getActiveSemester())
                        .onSuccess { options ->
                            offeringOptions = options
                            fetchTimetable(currentDay)
                        }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        periodAdapter = PeriodAdapter(
            periods = periodList,
            onPeriodClick = { period -> showPeriodDialog(period) },
            onPeriodLongClick = { period -> if (usingSupabase) showClassChangeOptions(period) }
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
                if (position !in periodList.indices) return
                val period = periodList[position]
                val context = AppContextManager.appContextFlow.value
                if (usingSupabase && period.createdBy != context.profileId && !context.isAdmin()) {
                    periodAdapter.notifyItemChanged(position)
                    Toast.makeText(this@TimetableManagementActivity, "Only the owner can delete this period", Toast.LENGTH_SHORT).show()
                    return
                }
                showDeleteConfirmation(period, position)
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.rvTimetable)
    }

    private fun showDeleteConfirmation(period: Period, position: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Period")
            .setMessage("Permanently delete ${period.subject} from the timetable? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deletePeriod(period) }
            .setNegativeButton("Cancel") { _, _ -> periodAdapter.notifyItemChanged(position) }
            .setOnCancelListener { periodAdapter.notifyItemChanged(position) }
            .show()
    }

    private fun showClassChangeOptions(period: Period) {
        val labels = arrayOf("Cancel this class", "Change room", "Change time", "Reschedule")
        MaterialAlertDialogBuilder(this)
            .setTitle("Class change for ${dateForDay(currentDay)}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> promptForChange(period, "cancelled", "Reason (optional)", null)
                    1 -> promptForChange(period, "room_changed", "New room", period.room)
                    2 -> promptForChange(period, "time_changed", "New start and end (HH:mm, HH:mm)", "${period.startTime}, ${period.endTime}")
                    3 -> promptForChange(period, "rescheduled", "New start and end (HH:mm, HH:mm)", "${period.startTime}, ${period.endTime}")
                }
            }
            .show()
    }

    private fun promptForChange(period: Period, kind: String, hint: String, initial: String?) {
        val input = android.widget.EditText(this).apply {
            this.hint = hint
            setText(initial.orEmpty())
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${kind.replace('_', ' ').replaceFirstChar(Char::uppercase)}")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                val times = if (kind == "time_changed" || kind == "rescheduled") {
                    value.split(',').map(String::trim).takeIf { it.size == 2 && it.all { time -> TIME_PATTERN.matches(time) } }
                } else null
                if ((kind == "time_changed" || kind == "rescheduled") && times == null) {
                    Toast.makeText(this, "Enter times as HH:mm, HH:mm", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if ((kind == "time_changed" || kind == "rescheduled") &&
                    runCatching { java.time.LocalTime.parse(times!![1]) <= java.time.LocalTime.parse(times[0]) }.getOrDefault(true)
                ) {
                    Toast.makeText(this, "The end time must be after the start time.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (kind == "room_changed" && value.isBlank()) {
                    Toast.makeText(this, "Enter the new room.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (kind == "cancelled") {
                        val targetDate = dateForDay(currentDay).toString()
                        val batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }
                        val title = "${period.subject} Class Cancelled"
                        val body = buildString {
                            append("${period.subject} class has been cancelled for $targetDate.")
                            if (value.isNotBlank()) append("\n\nReason: $value")
                        }
                        runCatching {
                            NoticeRepository.getInstance(this@TimetableManagementActivity)
                                .publishSupabaseClassCancellation(
                                    batchId, period.courseOfferingId, targetDate, title, body
                                )
                        }.onSuccess { noticeId ->
                            runCatching {
                                NoticeRepository.getInstance(this@TimetableManagementActivity)
                                    .syncFromSupabase(batchId)
                            }
                            NotificationSender.sendCancellationAlert(
                                subject = period.subject,
                                whenText = targetDate,
                                noticeId = noticeId,
                                day = currentDay,
                                batchId = batchId,
                                onFailure = { error ->
                                    Toast.makeText(
                                        this@TimetableManagementActivity,
                                        "Cancellation saved, but push failed: $error",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                            Toast.makeText(this@TimetableManagementActivity, "Cancellation notice published", Toast.LENGTH_SHORT).show()
                            fetchTimetable(currentDay)
                            refreshWidgetAfterTimetableChange(currentDay)
                        }.onFailure { error ->
                            Toast.makeText(this@TimetableManagementActivity, "Could not cancel class: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    v2ScheduleRepository.createClassChange(
                        routineSlotId = period.id,
                        effectiveDate = dateForDay(currentDay).toString(),
                        kind = kind,
                        previousRoom = period.room,
                        newRoom = if (kind == "room_changed") value else null,
                        previousStartsAt = if (kind == "time_changed" || kind == "rescheduled") period.startTime else null,
                        previousEndsAt = if (kind == "time_changed" || kind == "rescheduled") period.endTime else null,
                        newStartsAt = times?.get(0),
                        newEndsAt = times?.get(1),
                        reason = value.takeIf { kind == "cancelled" }
                    ).onSuccess {
                        Toast.makeText(this@TimetableManagementActivity, "Class change saved", Toast.LENGTH_SHORT).show()
                        fetchTimetable(currentDay)
                        refreshWidgetAfterTimetableChange(currentDay)
                    }.onFailure { error ->
                        Toast.makeText(this@TimetableManagementActivity, "Could not save change: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun dateForDay(day: String): LocalDate {
        val today = LocalDate.now()
        val todayIndex = (today.dayOfWeek.value + 1) % 7
        val targetIndex = days.indexOf(day).coerceAtLeast(0)
        if (todayIndex >= 5) {
            return today.plusDays(((targetIndex - todayIndex + 7) % 7).toLong())
        }
        return today.minusDays(todayIndex.toLong()).plusDays(targetIndex.toLong())
    }

    private fun fetchTimetable(day: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        if (usingSupabase) {
            if (!AppContextManager.appContextFlow.value.v2SessionActive) return
            lifecycleScope.launch {
                val weekday = POSTGRES_WEEKDAYS[day] ?: 6
                val result = v2ScheduleRepository.loadDay(
                    weekday, dateForDay(day).toString(),
                    AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
                    SemesterManager.getActiveSemester(),
                    false
                )
                binding.progressBar.visibility = View.GONE
                result.onSuccess { schedule ->
                    periodList.clear()
                    periodList.addAll(schedule.toTimetablePeriods())
                    renderPeriods(day)
                }.onFailure { error ->
                    Toast.makeText(this@TimetableManagementActivity, "Could not load V2 timetable: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        TimetableRepository.getInstance(this).getPeriodsCollection(day)
            .get()
            .addOnSuccessListener { documents ->
                binding.progressBar.visibility = View.GONE
                val fetchedPeriods = documents.mapNotNull { doc ->
                    doc.toObject(Period::class.java).copy(id = doc.id)
                }.sortedBy { it.startTime }

                periodList.clear()
                periodList.addAll(fetchedPeriods)

                renderPeriods(day)
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

        courseListener?.remove()
        if (usingSupabase) {
            val batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }
            val semesterId = SemesterManager.getActiveSemester()
            var selectedOffering = period?.courseOfferingId?.let { id ->
                offeringOptions.firstOrNull { it.id == id }
            }
            fun bindOfferings(preferredCourse: String? = null) {
                val mayAddCourse = AppContextManager.appContextFlow.value.canManageBatch(batchId)
                val labels = offeringOptions.map { it.label } + if (mayAddCourse) {
                    listOf("+ Add new course", "+ Set selected course teacher")
                } else emptyList()
                dialogBinding.dropdownSubject.setAdapter(
                    ArrayAdapter(this, R.layout.item_course_dropdown, labels)
                )
                dialogBinding.dropdownSubject.setOnItemClickListener { _, _, position, _ ->
                    if (mayAddCourse && position == offeringOptions.size) {
                        dialogBinding.dropdownSubject.setText("", false)
                        val previousOfferingIds = offeringOptions.map { it.id }.toSet()
                        CoursePicker.showAddCourseDialog(this, batchId, semesterId) {
                            lifecycleScope.launch {
                                v2ScheduleRepository.manageableOfferings(batchId, semesterId)
                                    .onSuccess { options ->
                                        offeringOptions = options
                                        bindOfferings(preferredCourse)
                                        val preferred = offeringOptions.firstOrNull { it.id !in previousOfferingIds }
                                            ?: offeringOptions.firstOrNull { it.name.equals(preferredCourse, true) }
                                        if (preferred != null) {
                                            selectedOffering = preferred
                                            dialogBinding.dropdownSubject.setText(preferred.label, false)
                                        }
                                    }
                                    .onFailure { error ->
                                        Toast.makeText(this@TimetableManagementActivity, "Course added, but offerings could not refresh: ${error.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    } else if (mayAddCourse && position == offeringOptions.size + 1) {
                        val chosen = selectedOffering
                        if (chosen == null) {
                            dialogBinding.dropdownSubject.setText("", false)
                            Toast.makeText(this, "Choose a course first.", Toast.LENGTH_SHORT).show()
                        } else {
                            dialogBinding.dropdownSubject.setText(chosen.label, false)
                            CoursePicker.showAddCourseDialog(
                                this, batchId, semesterId,
                                existing = Course(
                                    name = chosen.name, code = chosen.code,
                                    teacherName = chosen.teacherName,
                                    type = if (chosen.classKind == "lab") "lab" else "regular"
                                )
                            ) {
                                lifecycleScope.launch {
                                    v2ScheduleRepository.manageableOfferings(batchId, semesterId)
                                        .onSuccess { options ->
                                            offeringOptions = options
                                            selectedOffering = options.firstOrNull { it.id == chosen.id }
                                            bindOfferings()
                                            dialogBinding.dropdownSubject.setText(chosen.label, false)
                                            fetchTimetable(currentDay)
                                        }
                                }
                            }
                        }
                    } else {
                        selectedOffering = offeringOptions.getOrNull(position)
                    }
                }
                if (!preferredCourse.isNullOrBlank()) {
                    offeringOptions.firstOrNull { it.name.equals(preferredCourse, true) }
                        ?.let {
                            selectedOffering = it
                            dialogBinding.dropdownSubject.setText(it.label, false)
                        }
                }
            }
            bindOfferings()
        } else {
            courseListener = CoursePicker.bind(this, dialogBinding.dropdownSubject)
        }

        if (isEdit) {
            val selectedLabel = if (usingSupabase) {
                offeringOptions.firstOrNull { it.id == period?.courseOfferingId }?.label ?: period?.subject.orEmpty()
            } else period.subject
            dialogBinding.dropdownSubject.setText(selectedLabel, false)
            dialogBinding.etStartTime.setText(period?.startTime)
            dialogBinding.etEndTime.setText(period?.endTime)
            dialogBinding.etRoom.setText(period?.room)
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
                val start = dialogBinding.etStartTime.text.toString()
                val end = dialogBinding.etEndTime.text.toString()
                val room = dialogBinding.etRoom.text.toString().trim().ifBlank { null }

                if (usingSupabase && !runCatching {
                        !start.isBlank() && !end.isBlank() && java.time.LocalTime.parse(end) > java.time.LocalTime.parse(start)
                    }.getOrDefault(false)
                ) {
                    Toast.makeText(this, "Enter a valid start and end time.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                    if (selectedSubject.isNotEmpty()) {
                        val updatedPeriod = Period(
                        id = period?.id ?: "",
                        subject = selectedSubject,
                        teacher = period?.teacher.orEmpty(),
                        startTime = start,
                        endTime = end,
                        room = room,
                        classKind = period?.classKind ?: "theory",
                        courseOfferingId = period?.courseOfferingId.orEmpty()
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
        if (usingSupabase) {
            val offering = offeringOptions.firstOrNull { it.label == period.subject }
            if (offering == null) {
                Toast.makeText(this, "Choose a course offering from the list.", Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch {
                val weekday = POSTGRES_WEEKDAYS[currentDay] ?: 6
                val classKind = if (isEdit) period.classKind else offering.classKind
                val result = if (isEdit) {
                    v2ScheduleRepository.updateRoutine(period.id, offering.id, weekday, period.startTime, period.endTime, period.room, classKind)
                } else {
                    v2ScheduleRepository.createRoutine(offering.id, weekday, period.startTime, period.endTime, period.room, classKind)
                }
                result.onSuccess {
                    Toast.makeText(this@TimetableManagementActivity, if (isEdit) "Period updated" else "Period added", Toast.LENGTH_SHORT).show()
                    fetchTimetable(currentDay)
                    refreshWidgetAfterTimetableChange(currentDay)
                }.onFailure { error ->
                    Toast.makeText(this@TimetableManagementActivity, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val collection = TimetableRepository.getInstance(this).getPeriodsCollection(currentDay)
        val savedPeriod = if (isEdit) period else period.copy(
            createdBy = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        )
        val task = if (isEdit) {
            collection.document(period.id).set(savedPeriod)
        } else {
            collection.add(savedPeriod)
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

    private fun renderPeriods(day: String) {
        if (periodList.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvTimetable.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvTimetable.visibility = View.VISIBLE
            periodAdapter.updateList(periodList, day == getTodayName())
        }
    }

    override fun onDestroy() {
        courseListener?.remove()
        super.onDestroy()
    }

    private fun deletePeriod(period: Period) {
        if (usingSupabase) {
            val context = AppContextManager.appContextFlow.value
            if (period.createdBy != context.profileId && !context.isAdmin()) return
            lifecycleScope.launch {
                v2ScheduleRepository.deleteRoutine(period.id).onSuccess {
                    Toast.makeText(this@TimetableManagementActivity, "Period deleted", Toast.LENGTH_SHORT).show()
                    periodList.removeAll { it.id == period.id }
                    renderPeriods(currentDay)
                    fetchTimetable(currentDay)
                    refreshWidgetAfterTimetableChange(currentDay)
                }.onFailure { error ->
                    Toast.makeText(this@TimetableManagementActivity, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
                    fetchTimetable(currentDay)
                }
            }
            return
        }
        TimetableRepository.getInstance(this).getPeriodsCollection(currentDay)
            .document(period.id)
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
            if (usingSupabase) {
                val weekday = POSTGRES_WEEKDAYS[day] ?: 6
                val batchId = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() }
                val semesterId = AppContextManager.getSemesterId()
                v2ScheduleRepository.loadDay(weekday, dateForDay(day).toString(), batchId, semesterId, false).onSuccess { schedule ->
                    TimetableRepository.getInstance(this@TimetableManagementActivity).cacheSupabaseDay(
                        "v2-$batchId", semesterId, day, schedule.toTimetablePeriods()
                    )
                }
            } else {
                runCatching {
                    TimetableRepository.getInstance(this@TimetableManagementActivity)
                        .syncDayFromFirestore(day = day, source = Source.DEFAULT)
                }
            }
            if (day == DateHelper.todayDayString()) {
                WidgetUpdater.refresh(this@TimetableManagementActivity, syncTodayTimetable = false)
            }
        }
    }

    private companion object {
        val TIME_PATTERN = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        val POSTGRES_WEEKDAYS = mapOf(
            "sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3,
            "thursday" to 4, "friday" to 5, "saturday" to 6
        )
    }
}
