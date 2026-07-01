// com/shuaib/classmate/fragments/TimetableFragment.kt
package com.shuaib.classmate.fragments

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.adapters.CountdownAdapter
import com.shuaib.classmate.adapters.PeriodAdapter
import com.shuaib.classmate.databinding.FragmentTimetableBinding
import com.shuaib.classmate.models.AcademicCalendarException
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.utils.CountdownManager
import com.shuaib.classmate.utils.NotificationRouter
import com.shuaib.classmate.utils.AnimUtils
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.animateSpringScale
import com.shuaib.classmate.utils.applyClickAnimation
import com.shuaib.classmate.viewmodels.TimetableViewModel
import com.shuaib.classmate.models.BusSchedule
import com.shuaib.classmate.adapters.BusScheduleAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private val timetableViewModel: TimetableViewModel by viewModels()
    private val selectedDayFlow = MutableStateFlow("")
    private var isViewingRoutine = true
    private var busScheduleAdapter: BusScheduleAdapter? = null
    private val busSchedules = mutableListOf<BusSchedule>()
    private var exceptionRegistration: ListenerRegistration? = null
    private var userRoleRegistration: ListenerRegistration? = null
    private var countdownsRegistration: ListenerRegistration? = null
    private var busScheduleRegistration: ListenerRegistration? = null
    private var isAdmin = false
    private var countdownAdapter: CountdownAdapter? = null
    private var periodAdapter: PeriodAdapter? = null
    private val academicCalendarRepository = AcademicCalendarRepository()
    private var activeExceptionsList: List<AcademicCalendarException> = emptyList()
    private var lastAnimatedDay = ""
    private var isHeroExpanded = false
    private var todayPeriods: List<Period>? = null
    private var currentHeroSubject: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val showLoadingRunnable = Runnable { showLoadingState() }

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

        loadUserGreeting()
        setupWhatsAppButton()
        setupDaySelector()
        setupReactiveTimetableCollection()
        listenForAcademicCalendarExceptions()
        checkAdminAccess()
        loadUserCountdowns()
        setupHeroClassClickListeners()
        setupHeroCountdownTimer()
        setupScheduleToggle()
        timetableViewModel.refreshAll()
    }

    override fun onResume() {
        super.onResume()

        // Refresh greeting (time of day may have changed)
        binding.tvGreeting.text = getGreeting()

        // Handle target day from notification
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
        if (todayIndex != -1 && selectedDayFlow.value.isBlank()) {
            selectDay(todayIndex)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Greeting & Username
    // ─────────────────────────────────────────────────────────────

    private fun loadUserGreeting() {
        binding.tvGreeting.text = getGreeting()

        // Try Firebase Auth display name first (instant, no network)
        val displayName = auth.currentUser?.displayName
        if (!displayName.isNullOrBlank()) {
            binding.tvUserName.text = displayName.split(" ").firstOrNull() ?: displayName
        }

        // Fetch from Firestore for accurate name
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val name = doc.getString("name")
                    ?: doc.getString("displayName")
                    ?: displayName
                    ?: "Student"
                val firstName = name.split(" ").firstOrNull()?.ifBlank { name } ?: name
                binding.tvUserName.text = firstName
            }
    }

    private fun getGreeting(): String {
        return when (LocalTime.now().hour) {
            in 5..11  -> "Good Morning,"
            in 12..16 -> "Good Afternoon,"
            in 17..20 -> "Good Evening,"
            else       -> "Good Night,"
        }
    }

    private fun setupWhatsAppButton() {
        binding.btnWhatsApp.setOnClickListener {
            it.animateSpringScale(0.88f)
            openWhatsAppGroup()
        }
    }

    private fun openWhatsAppGroup() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(com.shuaib.classmate.utils.AppConstants.WHATSAPP_GROUP_LINK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open WhatsApp group link", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Day Selector
    // ─────────────────────────────────────────────────────────────

    private fun setupDaySelector() {
        val todayIndex = getTodayIndex()
        loadTimetable(days[todayIndex])

        val weekDates = getWeekDates()
        val margin = (6 * resources.displayMetrics.density).toInt()
        val inflater = LayoutInflater.from(requireContext())

        // If today is Thursday (5) or Friday (6), start the display array from today index
        val displayIndices = if (todayIndex == 5 || todayIndex == 6) {
            List(7) { i -> (todayIndex + i) % 7 }
        } else {
            List(7) { i -> i }
        }

        binding.daySelector.removeAllViews()
        displayIndices.forEach { index ->
            val cardView = inflater.inflate(R.layout.item_day_card, binding.daySelector, false)

            // Set day name & date
            cardView.findViewById<TextView>(R.id.tvDayShort).text = dayShort[index]
            cardView.findViewById<TextView>(R.id.tvDayDate).text =
                String.format("%02d", weekDates[index])

            // Margins: slightly more on edges
            val params = cardView.layoutParams as LinearLayout.LayoutParams
            params.setMargins(margin, margin / 2, margin, margin / 2)
            cardView.layoutParams = params

            cardView.setOnClickListener {
                it.animateSpringScale(0.9f)
                selectDay(index)
            }

            cardView.tag = index
            binding.daySelector.addView(cardView)
        }

        applyDayCardStyles(todayIndex)
        scrollToDay(todayIndex)
    }

    private fun selectDay(index: Int) {
        if (_binding == null) return
        val day = days[index]
        loadTimetable(day)

        // Update schedule section label
        val todayIndex = getTodayIndex()
        val label = if (index == todayIndex) "TODAY'S SCHEDULE" else "${dayFull[index].uppercase()}'S SCHEDULE"
        binding.tvScheduleLabel.text = label

        applyDayCardStyles(index)
        renderCalendarExceptionState()
        scrollToDay(index)

        if (!isViewingRoutine) {
            fetchBusSchedules(day)
        }
    }

    private fun scrollToDay(index: Int) {
        val binding = _binding ?: return
        binding.daySelector.post {
            val count = binding.daySelector.childCount
            for (i in 0 until count) {
                val child = binding.daySelector.getChildAt(i)
                val originalIndex = child.tag as? Int ?: i
                if (originalIndex == index) {
                    val parentScrollView = binding.daySelector.parent as? HorizontalScrollView
                    parentScrollView?.smoothScrollTo(child.left - parentScrollView.paddingStart, 0)
                    break
                }
            }
        }
    }

    private fun applyDayCardStyles(selectedIndex: Int) {
        val binding = _binding ?: return
        val todayIndex = getTodayIndex()
        val ctx = context ?: return

        for (i in 0 until binding.daySelector.childCount) {
            val cardView = binding.daySelector.getChildAt(i)
            val originalIndex = cardView.tag as? Int ?: i
            val tvDayShort = cardView.findViewById<TextView>(R.id.tvDayShort)
            val tvDayDate = cardView.findViewById<TextView>(R.id.tvDayDate)
            val vIndicator = cardView.findViewById<View>(R.id.vDayIndicator)

            when (originalIndex) {
                selectedIndex -> {
                    // Selected: gradient background, white text, show indicator
                    cardView.setBackgroundResource(R.drawable.bg_day_card_selected)
                    cardView.elevation = 5 * resources.displayMetrics.density
                    tvDayShort.setTextColor(0xCCFFFFFF.toInt())
                    tvDayDate.setTextColor(Color.WHITE)
                    vIndicator.visibility = View.VISIBLE
                }
                todayIndex -> {
                    // Today but not selected: white bg, blue text
                    cardView.setBackgroundResource(R.drawable.bg_day_card_unselected)
                    cardView.elevation = 2 * resources.displayMetrics.density
                    tvDayShort.setTextColor(ThemeColors.primary(ctx))
                    tvDayDate.setTextColor(ThemeColors.primary(ctx))
                    vIndicator.visibility = View.INVISIBLE
                }
                else -> {
                    // Regular day: white bg, muted text
                    cardView.setBackgroundResource(R.drawable.bg_day_card_unselected)
                    cardView.elevation = 2 * resources.displayMetrics.density
                    tvDayShort.setTextColor(ThemeColors.textDisabled(ctx))
                    tvDayDate.setTextColor(ThemeColors.textPrimary(ctx))
                    vIndicator.visibility = View.INVISIBLE
                }
            }
        }
    }

    /**
     * Returns the date numbers (1-31) for each day of the app week (Sat–Fri)
     * based on the current calendar week.
     */
    private fun getWeekDates(): IntArray {
        val today = LocalDate.now()
        val todayIndex = getTodayIndex()
        val dates = IntArray(7)
        if (todayIndex == 5 || todayIndex == 6) {
            // Thursday or Friday: start list from todayIndex, dates are today.plusDays(pos)
            for (i in 0 until 7) {
                val index = (todayIndex + i) % 7
                dates[index] = today.plusDays(i.toLong()).dayOfMonth
            }
        } else {
            // Regular week: start list from Saturday (0), dates are saturday.plusDays(i)
            val saturday = today.minusDays(todayIndex.toLong())
            for (i in 0 until 7) {
                dates[i] = saturday.plusDays(i.toLong()).dayOfMonth
            }
        }
        return dates
    }

    private fun getTodayIndex(): Int {
        return when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY  -> 0
            DayOfWeek.SUNDAY    -> 1
            DayOfWeek.MONDAY    -> 2
            DayOfWeek.TUESDAY   -> 3
            DayOfWeek.WEDNESDAY -> 4
            DayOfWeek.THURSDAY  -> 5
            DayOfWeek.FRIDAY    -> 6
            else                -> 0
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Timetable loading (Reactive Flow Collection)
    // ─────────────────────────────────────────────────────────────

    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun setupReactiveTimetableCollection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectedDayFlow
                    .filter { it.isNotBlank() }
                    .flatMapLatest { day ->
                        timetableViewModel.observePeriods(day)
                    }
                    .collect { periods ->
                        if (isViewingRoutine) {
                            renderTimetable(selectedDayFlow.value, periods)
                        }
                    }
            }
        }
    }

    private fun loadTimetable(day: String) {
        handler.removeCallbacks(showLoadingRunnable)
        handler.postDelayed(showLoadingRunnable, 150)
        selectedDayFlow.value = day
    }

    private fun showLoadingState() {
        if (_binding != null) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvPeriods.isVisible = false
            binding.scheduleHeader.isVisible = false
            binding.heroNextClass.isVisible = false
            binding.emptyState.isVisible = false
        }
    }

    private fun renderTimetable(day: String, periods: List<Period>) {
        if (_binding == null) return

        handler.removeCallbacks(showLoadingRunnable)

        if (shouldPauseSelectedDay()) {
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.rvPeriods.isVisible = false
            binding.scheduleHeader.isVisible = false
            binding.heroNextClass.isVisible = false
            binding.emptyState.isVisible = false
            renderCalendarExceptionState()
            return
        }

        binding.shimmerView.stopShimmer()
        binding.shimmerView.isVisible = false

        val isToday = day.lowercase() == days[getTodayIndex()].lowercase()
        todayPeriods = if (isToday) periods else null

        if (periods.isEmpty()) {
            binding.rvPeriods.isVisible = false
            binding.scheduleHeader.isVisible = false
            binding.heroNextClass.isVisible = false
            binding.emptyState.isVisible = true
            lastAnimatedDay = ""

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
            binding.scheduleHeader.isVisible = true

            val classCount = periods.size
            val cancelledCount = periods.count { it.isCancelled }
            binding.tvPeriodCount.text = when {
                cancelledCount > 0 -> "$classCount classes · $cancelledCount cancelled"
                else -> "$classCount classes"
            }

            // Hero card only when viewing today
            if (isToday && !shouldPauseSelectedDay()) {
                updateHeroNextClass(periods)
            } else {
                binding.heroNextClass.isVisible = false
            }

            // Animate entrance once per day switch
            val isDaySwitch = day != lastAnimatedDay
            if (isDaySwitch) {
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
                periodAdapter?.updateList(periods, isToday, isDaySwitch)
            }
        }
    }

    private fun setupHeroClassClickListeners() {
        binding.cardSmartDigest.applyClickAnimation {
            val intent = Intent(requireContext(), com.shuaib.classmate.activities.SmartDigestActivity::class.java)
            startActivity(intent)
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        binding.heroNextClass.setOnClickListener { card ->
            val context = card.context
            val reduceMotion = AnimUtils.isReduceMotionEnabled(context)
            if (!reduceMotion) {
                card.animateSpringScale(0.97f)
            }
            isHeroExpanded = !isHeroExpanded
            
            val transition = androidx.transition.TransitionSet().apply {
                ordering = androidx.transition.TransitionSet.ORDERING_TOGETHER
                addTransition(androidx.transition.ChangeBounds())
                addTransition(androidx.transition.Fade(androidx.transition.Fade.IN))
                addTransition(androidx.transition.Fade(androidx.transition.Fade.OUT))
                duration = if (reduceMotion) 0L else 180L
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root as ViewGroup, transition)
            binding.heroDetailsContainer.isVisible = isHeroExpanded
            
            if (reduceMotion) {
                binding.ivHeroChevron.rotation = if (isHeroExpanded) 90f else 0f
            } else {
                binding.ivHeroChevron.animate().rotation(if (isHeroExpanded) 90f else 0f).setDuration(180).start()
            }
        }

        binding.btnHeroClassNotes.setOnClickListener {
            it.animateSpringScale(0.95f)
            val subjectName = currentHeroSubject
            if (!subjectName.isNullOrBlank()) {
                val bundle = bundleOf("subjectName" to subjectName)
                (activity as? MainActivity)?.openChildDestination(
                    R.id.nav_pdf,
                    R.id.fragment_subject_pdf_list,
                    bundle
                )
            }
        }
    }

    private fun setupHeroCountdownTimer() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val todayName = days.getOrNull(getTodayIndex()) ?: ""
                    if (selectedDayFlow.value.lowercase() == todayName.lowercase() && !shouldPauseSelectedDay()) {
                        val periods = todayPeriods
                        if (periods != null) {
                            updateHeroNextClass(periods)
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    private fun updateHeroNextClass(periods: List<Period>) {
        val now = LocalTime.now()
        val currentSeconds = now.hour * 3600 + now.minute * 60 + now.second

        val nextPeriod = periods
            .filter { !it.isCancelled }
            .firstOrNull { period ->
                try {
                    val end = LocalTime.parse(period.endTime)
                    val endSeconds = end.hour * 3600 + end.minute * 60
                    currentSeconds < endSeconds
                } catch (_: Exception) { false }
            }

        if (nextPeriod == null) {
            binding.heroNextClass.isVisible = false
            return
        }

        binding.heroNextClass.isVisible = true
        currentHeroSubject = nextPeriod.subject
        binding.tvHeroSubject.text = nextPeriod.subject
        binding.tvHeroTeacher.text = nextPeriod.teacher.ifBlank { "Course Teacher" }
        binding.tvHeroTime.text = "${formatTo12Hour(nextPeriod.startTime)} → ${formatTo12Hour(nextPeriod.endTime)}"

        // Sync expandable UI states
        binding.heroDetailsContainer.isVisible = isHeroExpanded
        binding.ivHeroChevron.rotation = if (isHeroExpanded) 90f else 0f

        try {
            val start = LocalTime.parse(nextPeriod.startTime)
            val startSeconds = start.hour * 3600 + start.minute * 60
            val end = LocalTime.parse(nextPeriod.endTime)
            val endSeconds = end.hour * 3600 + end.minute * 60
            val durationSeconds = endSeconds - startSeconds

            val diffSeconds = startSeconds - currentSeconds

            when {
                diffSeconds <= 0 -> {
                    // Ongoing
                    binding.tvHeroBadge.text = "LIVE NOW"
                    binding.tvHeroCountdown.text = "In progress"
                    binding.tvHeroCountdown.setTextColor(Color.parseColor("#4ADE80")) // Vibrant light green
                    
                    // Show progress bar
                    val elapsed = currentSeconds - startSeconds
                    val progress = if (durationSeconds > 0) {
                        ((elapsed.toFloat() / durationSeconds.toFloat()) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    binding.pbHeroTime.progress = progress
                    binding.pbHeroTime.isVisible = true
                }
                diffSeconds < 3600 -> {
                    // Under an hour
                    binding.tvHeroBadge.text = "NEXT CLASS"
                    val mins = diffSeconds / 60
                    val secs = diffSeconds % 60
                    binding.tvHeroCountdown.text = String.format(Locale.US, "in %dm %02ds", mins, secs)
                    binding.tvHeroCountdown.setTextColor(
                        if (diffSeconds < 600) Color.parseColor("#FBBF24") // Amber/orange if starting soon (< 10m)
                        else Color.parseColor("#CCFFFFFF") // Default white
                    )
                    binding.pbHeroTime.isVisible = false
                }
                else -> {
                    binding.tvHeroBadge.text = "NEXT CLASS"
                    val hrs = diffSeconds / 3600
                    val mins = (diffSeconds % 3600) / 60
                    binding.tvHeroCountdown.text = if (mins == 0) "in ${hrs}h" else "in ${hrs}h ${mins}m"
                    binding.tvHeroCountdown.setTextColor(Color.parseColor("#CCFFFFFF")) // Default white
                    binding.pbHeroTime.isVisible = false
                }
            }
        } catch (_: Exception) {
            binding.tvHeroCountdown.text = ""
            binding.tvHeroCountdown.setTextColor(Color.parseColor("#CCFFFFFF"))
            binding.pbHeroTime.isVisible = false
        }
    }

    private fun formatTo12Hour(time24: String): String {
        return try {
            val time = LocalTime.parse(time24)
            val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            time.format(formatter)
        } catch (_: Exception) { time24 }
    }

    // ─────────────────────────────────────────────────────────────
    // Countdowns
    // ─────────────────────────────────────────────────────────────

    private fun loadUserCountdowns() {
        countdownsRegistration?.remove()
        countdownsRegistration = CountdownManager.getUserCountdowns { assignments ->
            if (_binding == null) return@getUserCountdowns

            if (assignments.isEmpty()) {
                binding.countdownSection.isVisible = false
            } else {
                binding.countdownSection.isVisible = true
                countdownAdapter?.cancelAllTimers()

                val adapter = CountdownAdapter(assignments.toMutableList()) { assignment ->
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

    // ─────────────────────────────────────────────────────────────
    // Calendar Exceptions
    // ─────────────────────────────────────────────────────────────

    private fun listenForAcademicCalendarExceptions() {
        exceptionRegistration?.remove()
        exceptionRegistration = academicCalendarRepository.observeActiveExceptions(
            onResult = { exceptions ->
                if (_binding == null) return@observeActiveExceptions
                activeExceptionsList = exceptions
                renderCalendarExceptionState()
                if (selectedDayFlow.value.isNotBlank()) {
                    loadTimetable(selectedDayFlow.value)
                }
            }
        )
    }

    private fun getDateForSelectedDay(selectedDayName: String): LocalDate {
        val today = LocalDate.now()
        val todayIndex = getTodayIndex()
        val targetIndex = days.indexOf(selectedDayName.lowercase())
        if (targetIndex == -1) return today

        return if (todayIndex == 5 || todayIndex == 6) {
            var offset = targetIndex - todayIndex
            if (offset < 0) offset += 7
            today.plusDays(offset.toLong())
        } else {
            val saturday = today.minusDays(todayIndex.toLong())
            val offset = targetIndex
            saturday.plusDays(offset.toLong())
        }
    }

    private fun getExceptionForSelectedDay(): AcademicCalendarException? {
        val selectedDay = selectedDayFlow.value
        if (selectedDay.isBlank()) return null
        val selectedDate = getDateForSelectedDay(selectedDay)
        
        return activeExceptionsList
            .filter { it.scope == AcademicCalendarException.SCOPE_ALL_CLASSES }
            .filter { it.type in AcademicCalendarException.SUPPORTED_TYPES }
            .firstOrNull { ex ->
                val start = ex.startDate.toLocalDateOrNull()
                val end = ex.endDate.toLocalDateOrNull()
                start != null && end != null && !selectedDate.isBefore(start) && !selectedDate.isAfter(end)
            }
    }

    private fun renderCalendarExceptionState() {
        if (_binding == null) return
        val exception = getExceptionForSelectedDay()
        val showOverride = exception != null && shouldPauseSelectedDay()

        val context = binding.calendarExceptionBanner.context
        val reduceMotion = AnimUtils.isReduceMotionEnabled(context)
        if (!reduceMotion && binding.calendarExceptionBanner.isVisible != showOverride) {
            val transition = androidx.transition.TransitionSet().apply {
                ordering = androidx.transition.TransitionSet.ORDERING_TOGETHER
                addTransition(androidx.transition.ChangeBounds())
                addTransition(androidx.transition.Fade(androidx.transition.Fade.IN))
                addTransition(androidx.transition.Fade(androidx.transition.Fade.OUT))
                duration = 250L
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup, transition)
        }
        binding.calendarExceptionBanner.isVisible = showOverride

        if (!showOverride || exception == null) return

        binding.tvExceptionBadge.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION -> "VACATION ACTIVE"
            AcademicCalendarException.TYPE_HOLIDAY -> "UNIVERSITY HOLIDAY"
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "CLASSES SUSPENDED"
            else -> "CALENDAR OVERRIDE"
        }

        binding.tvExceptionEmoji.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION -> "🌴"
            AcademicCalendarException.TYPE_HOLIDAY -> "🎉"
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "🛑"
            else -> "🔔"
        }

        binding.tvExceptionTitle.text = when (exception.type) {
            AcademicCalendarException.TYPE_VACATION -> exception.title.ifBlank { "Vacation" }
            AcademicCalendarException.TYPE_HOLIDAY -> exception.title.ifBlank { "University Holiday" }
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> exception.title.ifBlank { "Classes Suspended" }
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
    }

    private fun shouldPauseSelectedDay(): Boolean {
        val exception = getExceptionForSelectedDay()
        return exception != null &&
            exception.isActive &&
            exception.scope == AcademicCalendarException.SCOPE_ALL_CLASSES
    }

    private fun formatDateRange(startDate: String, endDate: String): String {
        val start = startDate.toLocalDateOrNull() ?: return "$startDate - $endDate"
        val end = endDate.toLocalDateOrNull() ?: return "$startDate - $endDate"
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        return if (start.year == end.year)
            "${start.format(formatter)} - ${end.format(formatter)}"
        else
            "${start.format(formatter)} ${start.year} - ${end.format(formatter)} ${end.year}"
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return try { LocalDate.parse(this) } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────
    // Admin
    // ─────────────────────────────────────────────────────────────

    private fun showDeleteDialog(period: Period) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Period")
            .setMessage("Are you sure you want to delete this period?")
            .setPositiveButton("Delete") { _, _ ->
                if (selectedDayFlow.value.isNotBlank()) {
                    db.collection("timetable").document(selectedDayFlow.value)
                        .collection("periods").document(period.id).delete()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: return
        userRoleRegistration?.remove()
        userRoleRegistration = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || _binding == null) return@addSnapshotListener
                val user = snapshot?.toObject(com.shuaib.classmate.models.User::class.java)
                isAdmin = user?.canEditTimetable() ?: false
            }
    }

    private fun setupScheduleToggle() {
        binding.btnToggleRoutine.setOnClickListener {
            if (isViewingRoutine) return@setOnClickListener
            isViewingRoutine = true
            
            (activity as? MainActivity)?.isViewingRoutineInTimetable = true
            
            // Remove bus listener since we are viewing the routine
            busScheduleRegistration?.remove()
            busScheduleRegistration = null
            
            binding.btnToggleRoutine.setBackgroundResource(R.drawable.bg_toggle_item_selected)
            binding.btnToggleRoutine.setTextColor(Color.WHITE)
            binding.btnToggleBus.setBackgroundColor(Color.TRANSPARENT)
            binding.btnToggleBus.setTextColor(ThemeColors.textSecondary(requireContext()))

            binding.rvPeriods.adapter = periodAdapter
            loadTimetable(selectedDayFlow.value)
        }

        binding.btnToggleBus.setOnClickListener {
            if (!isViewingRoutine) return@setOnClickListener
            isViewingRoutine = false
            
            (activity as? MainActivity)?.isViewingRoutineInTimetable = false
            
            binding.btnToggleBus.setBackgroundResource(R.drawable.bg_toggle_item_selected)
            binding.btnToggleBus.setTextColor(Color.WHITE)
            binding.btnToggleRoutine.setBackgroundColor(Color.TRANSPARENT)
            binding.btnToggleRoutine.setTextColor(ThemeColors.textSecondary(requireContext()))

            fetchBusSchedules(selectedDayFlow.value)
        }
    }

    private fun fetchBusSchedules(day: String) {
        if (_binding == null) return
        
        handler.removeCallbacks(showLoadingRunnable)
        handler.postDelayed(showLoadingRunnable, 150)

        val normalizedDay = day.lowercase()
        val scheduleType = if (normalizedDay == "thursday" || normalizedDay == "friday") "off_day" else "class_day"

        busScheduleRegistration?.remove()
        busScheduleRegistration = db.collection("bus_schedules")
            .whereEqualTo("scheduleType", scheduleType)
            .addSnapshotListener { documents, error ->
                if (error != null) {
                    if (_binding == null) return@addSnapshotListener
                    handler.removeCallbacks(showLoadingRunnable)
                    binding.shimmerView.stopShimmer()
                    binding.shimmerView.isVisible = false
                    Toast.makeText(context, "Error fetching bus schedule: ${error.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                if (documents == null || _binding == null) return@addSnapshotListener
                handler.removeCallbacks(showLoadingRunnable)
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false

                val fetchedSchedules = documents.mapNotNull { doc ->
                    doc.toObject(BusSchedule::class.java).copy(id = doc.id)
                }.sortedBy { 
                    parseTimeToMinutes(it.time)
                }

                busSchedules.clear()
                busSchedules.addAll(fetchedSchedules)

                renderBusSchedules(scheduleType)
            }
    }

    private fun renderBusSchedules(scheduleType: String) {
        val binding = _binding ?: return
        
        binding.heroNextClass.isVisible = false
        binding.countdownSection.isVisible = false
        binding.calendarExceptionBanner.isVisible = false

        if (busSchedules.isEmpty()) {
            binding.rvPeriods.isVisible = false
            binding.scheduleHeader.isVisible = false
            binding.emptyState.isVisible = true
            
            binding.tvNoClassesTitle.text = "No Buses Scheduled"
            binding.tvNoClassesSubtitle.text = "There are no bus schedules configured for this day type."
        } else {
            binding.emptyState.isVisible = false
            binding.rvPeriods.isVisible = true
            binding.scheduleHeader.isVisible = true

            val typeLabel = if (scheduleType == "class_day") "CLASS DAY BUSES" else "OFF DAY BUSES"
            binding.tvScheduleLabel.text = typeLabel
            binding.tvPeriodCount.text = "${busSchedules.size} departures"

            if (binding.rvPeriods.layoutManager == null) {
                binding.rvPeriods.layoutManager = LinearLayoutManager(requireContext())
            }

            if (busScheduleAdapter == null) {
                busScheduleAdapter = BusScheduleAdapter(busSchedules) {
                    // Read-only for students in this view
                }
            } else {
                busScheduleAdapter?.updateList(busSchedules)
            }
            binding.rvPeriods.adapter = busScheduleAdapter
        }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        try {
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

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(showLoadingRunnable)
        exceptionRegistration?.remove()
        userRoleRegistration?.remove()
        countdownsRegistration?.remove()
        busScheduleRegistration?.remove()
        busScheduleRegistration = null
        countdownAdapter?.cancelAllTimers()
        countdownAdapter = null
        periodAdapter = null
        _binding = null
    }
}
