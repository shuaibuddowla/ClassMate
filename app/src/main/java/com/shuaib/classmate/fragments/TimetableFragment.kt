// com/shuaib/classmate/fragments/TimetableFragment.kt
package com.shuaib.classmate.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.adapters.CountdownAdapter
import com.shuaib.classmate.adapters.PeriodAdapter
import com.shuaib.classmate.databinding.FragmentTimetableBinding
import com.shuaib.classmate.models.AcademicCalendarException
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.utils.CountdownManager
import com.shuaib.classmate.utils.NetworkMonitor
import com.shuaib.classmate.utils.NotificationRouter
import com.shuaib.classmate.utils.animateSpringScale
import com.shuaib.classmate.viewmodels.TimetableViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private val timetableViewModel: TimetableViewModel by viewModels()
    private var selectedDay = ""
    private var timetableJob: Job? = null
    private var todayNoticeRegistration: ListenerRegistration? = null
    private var exceptionRegistration: ListenerRegistration? = null
    private var isAdmin = false
    private var countdownAdapter: CountdownAdapter? = null
    private var periodAdapter: PeriodAdapter? = null
    private val academicCalendarRepository = AcademicCalendarRepository()
    private var activeException: AcademicCalendarException? = null
    private var lastAnimatedDay = ""

    private val days = listOf(
        "saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday"
    )
    private val dayShort = listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")
    private val dayFull = listOf(
        "Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupDaySelector()
        // setupNoticeBoard()
        // listenForTodayNotices()
        listenForAcademicCalendarExceptions()
        checkAdminAccess()
        loadUserCountdowns()
    }

    override fun onResume() {
        super.onResume()

        // Handle target day from notification if any
        val pendingDay = NotificationRouter.pendingDay
        if (pendingDay != null) {
            val index = days.indexOf(pendingDay.lowercase())
            if (index != -1) {
                selectDay(index)
                NotificationRouter.pendingDay = null
                return
            }
        }

        val todayIndex = getTodayIndex()
        if (todayIndex != -1 && selectedDay.isBlank()) {
            selectDay(todayIndex)
        }
    }

    private fun loadUserCountdowns() {
        CountdownManager.getUserCountdowns { assignments ->
            if (_binding == null) return@getUserCountdowns

            if (assignments.isEmpty()) {
                binding.countdownSection.isVisible = false
            } else {
                binding.countdownSection.isVisible = true
                countdownAdapter?.cancelAllTimers()

                val adapter = CountdownAdapter(assignments.toMutableList()) { assignment ->
                    // Remove countdown
                    CountdownManager.removeCountdown(
                        assignment.id,
                        onSuccess = {
                            context?.let { ctx ->
                                Toast.makeText(ctx, "Countdown removed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFailure = { error ->
                            context?.let { ctx ->
                                Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                countdownAdapter = adapter
                binding.rvCountdowns.layoutManager = LinearLayoutManager(requireContext())
                binding.rvCountdowns.adapter = adapter
            }
        }
    }

    private fun setupDaySelector() {
        val todayIndex = getTodayIndex()
        selectedDay = days[todayIndex]
        binding.tvSelectedDay.text = dayFull[todayIndex]
        loadTimetable(selectedDay)

        binding.daySelector.removeAllViews()
        days.forEachIndexed { index, day ->
            val pill = TextView(requireContext()).apply {
                text = dayShort[index]
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(32, 16, 32, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6, 0, 6, 0) }

                setOnClickListener {
                    it.animateSpringScale(0.9f)
                    selectDay(index)
                }
            }
            binding.daySelector.addView(pill)
        }

        highlightSelectedPill(todayIndex, todayIndex)
    }

/*
    private fun setupNoticeBoard() {
        binding.cardNoticeBoard.setOnClickListener {
            (activity as? MainActivity)?.showMainTab(R.id.nav_notices)
        }
    }

    private fun listenForTodayNotices() {
        todayNoticeRegistration?.remove()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        todayNoticeRegistration = db.collection("notices")
            .whereGreaterThanOrEqualTo("timestamp", Timestamp(startOfDay))
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                val binding = _binding ?: return@addSnapshotListener
                val context = context ?: return@addSnapshotListener

                if (error != null) {
                    binding.tvTodayNoticeCount.text = "0"
                    binding.tvNoticeBoardSubtitle.text = if (NetworkMonitor.isOnline(context)) {
                        "Tap to open the notice board"
                    } else {
                        "Open cached notices offline"
                    }
                    return@addSnapshotListener
                }

                val todayCount = snapshot?.size() ?: 0
                binding.tvTodayNoticeCount.text = todayCount.toString()
                binding.tvNoticeBoardSubtitle.text = when (todayCount) {
                    0 -> "No notices posted today"
                    1 -> "1 notice posted today"
                    else -> "$todayCount notices posted today"
                }
            }
    }
*/

    private fun selectDay(index: Int) {
        if (_binding == null) return
        val todayIndex = getTodayIndex()
        selectedDay = days[index]
        binding.tvSelectedDay.text = dayFull[index]

        binding.tvTodayLabel.text = if (index == todayIndex) "TODAY" else "SCHEDULE"
        binding.tvPeriodCount.isVisible = true

        highlightSelectedPill(index, todayIndex)
        renderCalendarExceptionState()
        loadTimetable(selectedDay)
    }

    private fun highlightSelectedPill(selectedIndex: Int, todayIndex: Int) {
        val binding = _binding ?: return
        for (i in 0 until binding.daySelector.childCount) {
            val pill = binding.daySelector.getChildAt(i) as TextView
            val context = pill.context
            when {
                i == selectedIndex -> {
                    pill.setBackgroundResource(R.drawable.bg_day_pill_selected)
                    pill.setTextColor(com.shuaib.classmate.utils.ThemeColors.onPrimary(context))
                }
                i == todayIndex -> {
                    pill.setBackgroundResource(R.drawable.bg_day_pill_today)
                    pill.setTextColor(com.shuaib.classmate.utils.ThemeColors.onPrimary(context))
                }
                else -> {
                    pill.setBackgroundResource(R.drawable.bg_day_pill_unselected)
                    pill.setTextColor(com.shuaib.classmate.utils.ThemeColors.textMuted(context))
                }
            }
        }
    }

    private fun getTodayIndex(): Int {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY  -> 0
            Calendar.SUNDAY    -> 1
            Calendar.MONDAY    -> 2
            Calendar.TUESDAY   -> 3
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY  -> 5
            Calendar.FRIDAY     -> 6
            else               -> 0
        }
    }

    private fun loadTimetable(day: String) {
        timetableJob?.cancel()
        timetableJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                timetableViewModel.observePeriods(day).collect { periods ->
                    renderTimetable(day, periods)
                }
            }
        }
        timetableViewModel.refreshDay(day)
    }

    private fun renderTimetable(day: String, periods: List<Period>) {
        if (_binding == null) return
        val isToday = day.lowercase() == days[getTodayIndex()].lowercase()

        if (periods.isEmpty()) {
            binding.rvPeriods.isVisible = false
            binding.emptyState.isVisible = true
            lastAnimatedDay = "" // Allow animation when data arrives

            if (day == "thursday" || day == "friday") {
                binding.tvNoClassesTitle.text = "No Classes Today!"
                binding.tvNoClassesSubtitle.text = "Enjoy your day off!"
            } else {
                binding.tvNoClassesTitle.text = getString(R.string.no_classes_title)
                binding.tvNoClassesSubtitle.text = getString(R.string.enjoy_free_day)
            }
        } else {
            binding.emptyState.isVisible = false
            binding.rvPeriods.isVisible = true

            // Animate entrance only once per day switch to avoid "multiple bounce" on sync
            if (day != lastAnimatedDay) {
                binding.rvPeriods.post {
                    if (_binding != null) binding.rvPeriods.animateSpringScale(0.96f)
                }
                lastAnimatedDay = day
            }

            if (binding.rvPeriods.adapter == null) {
                binding.rvPeriods.layoutManager = LinearLayoutManager(requireContext())
            }

            if (periodAdapter == null) {
                periodAdapter = PeriodAdapter(
                    periods = periods,
                    isPausedByCalendarException = shouldPauseSelectedDay(),
                    isViewingToday = isToday,
                    onPeriodClick = { period ->
                        val bundle = bundleOf("subjectName" to period.subject)
                        (activity as? MainActivity)?.openChildDestination(
                            R.id.nav_pdf,
                            R.id.fragment_subject_pdf_list,
                            bundle
                        )
                    },
                    onPeriodLongClick = { period ->
                        if (isAdmin) showDeleteDialog(period)
                    }
                )
                binding.rvPeriods.adapter = periodAdapter
            } else {
                periodAdapter?.setPausedByCalendarException(shouldPauseSelectedDay())
                periodAdapter?.updateList(periods, isToday)
            }
        }

        val classCount = periods.size
        val cancelledCount = periods.count { it.isCancelled }
        binding.tvPeriodCount.text = when {
            cancelledCount > 0 -> "$classCount classes - $cancelledCount cancelled"
            else -> "$classCount classes"
        }
    }
    private fun listenForAcademicCalendarExceptions() {
        exceptionRegistration?.remove()
        exceptionRegistration = academicCalendarRepository.observeActiveExceptions(
            onResult = { exceptions ->
                if (_binding == null) return@observeActiveExceptions
                val today = try { LocalDate.now() } catch (e: Exception) { null } ?: return@observeActiveExceptions
                activeException = exceptions
                    .filter { it.scope == AcademicCalendarException.SCOPE_ALL_CLASSES }
                    .filter { it.type in AcademicCalendarException.SUPPORTED_TYPES }
                    .firstOrNull { exception ->
                        val start = exception.startDate.toLocalDateOrNull()
                        val end = exception.endDate.toLocalDateOrNull()
                        start != null && end != null && !today.isBefore(start) && !today.isAfter(end)
                    }
                renderCalendarExceptionState()
                loadTimetable(selectedDay)
            }
        )
    }

    private fun renderCalendarExceptionState() {
        if (_binding == null) return
        val showOverride = activeException != null && shouldPauseSelectedDay()

        binding.calendarExceptionBanner.isVisible = showOverride
        binding.calendarExceptionStatus.isVisible = showOverride

        if (!showOverride) return
        val exception = activeException ?: return

        binding.tvExceptionTitle.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION -> "${exception.title.ifBlank { "Vacation" }} Ongoing"
            AcademicCalendarException.TYPE_HOLIDAY -> exception.title.ifBlank { "University Holiday" }
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "Classes Suspended Today"
            else -> exception.title
        }
        binding.tvExceptionDateRange.text = formatDateRange(exception.startDate, exception.endDate)
        binding.tvExceptionMessage.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION ->
                "Enjoy your break! Your timetable is preserved and will resume after vacation."
            AcademicCalendarException.TYPE_HOLIDAY ->
                "No regular classes today."
            AcademicCalendarException.TYPE_CLASS_SUSPENDED ->
                exception.reason.ifBlank { "All scheduled classes have been suspended today." }
            else ->
                "Class reminders are temporarily paused during this period."
        }
        binding.tvExceptionStatus.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION -> "Timetable preserved - Vacation override active"
            AcademicCalendarException.TYPE_HOLIDAY -> "Timetable preserved - Holiday override active"
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "Timetable preserved - Class suspension active"
            else -> "Timetable preserved - Calendar override active"
        }
    }
    private fun shouldPauseSelectedDay(): Boolean {
        val todayIndex = getTodayIndex()
        val selectedIndex = days.indexOf(selectedDay)
        val exception = activeException
        return selectedIndex == todayIndex &&
            exception != null &&
            exception.isActive &&
            exception.scope == AcademicCalendarException.SCOPE_ALL_CLASSES
    }

    private fun formatDateRange(startDate: String, endDate: String): String {
        val start = startDate.toLocalDateOrNull() ?: return "$startDate - $endDate"
        val end = endDate.toLocalDateOrNull() ?: return "$startDate - $endDate"
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val startText = start.format(formatter)
        val endText = end.format(formatter)
        return if (start.year == end.year) "$startText - $endText" else "$startText ${start.year} - $endText ${end.year}"
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return try {
            LocalDate.parse(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun showDeleteDialog(period: Period) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Period")
            .setMessage("Are you sure you want to delete this period?")
            .setPositiveButton("Delete") { _, _ ->
                db.collection("timetable").document(selectedDay)
                    .collection("periods").document(period.id).delete()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                val canEdit = doc.getBoolean("permissions.canEditTimetable") ?: false
                isAdmin = role == "superadmin" || canEdit
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timetableJob?.cancel()
        // todayNoticeRegistration?.remove()
        exceptionRegistration?.remove()
        countdownAdapter?.cancelAllTimers()
        periodAdapter = null
        _binding = null
    }
}
