package com.shuaib.classmate.activities

import android.content.Intent
import androidx.core.view.doOnAttach
import com.shuaib.classmate.services.ClassMateNoticeReminderScheduler
import java.time.Instant
import java.time.ZonedDateTime
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.messaging.FirebaseMessaging
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.update.UpdateActionActivity
import com.shuaib.classmate.update.UpdateCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Binds the original four ClassMate layouts to the Supabase Auth/RLS backend. */
internal class ClassMateAcademicScreensSupabase(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val batchId: () -> String,
    private val batchLabel: () -> String,
    private val profile: () -> JSONObject,
    private val onPostNotice: () -> Unit,
    private val onUpload: () -> Unit,
    private val onEditFile: (JSONObject) -> Unit,
    private val onSignOut: () -> Unit,
    private val onSwitchBatch: () -> Unit,
    private val onAddPeriod: (JSONObject?, Int) -> Unit,
    private val onAddBus: (JSONObject?, String) -> Unit,
    private val onOpenCourse: (String, String, Boolean) -> Unit,
    private val onEditProfile: () -> Unit,
    private val onManage: () -> Unit,
    private val onConfigure: () -> Unit,
) {
    private var selectedDay = LocalDate.now().dayOfWeek.value % 7
    private var busMode = false
    private var calendarMode = false
    private val calendarData = ClassMateCalendarData(activity) { profile().optString("id") }
    private fun busDayKind() = calendarData.kindFor(selectedScheduleDate())
    private var timetableRequest = 0
    private var navShown = true
    private var noticeHeaderCollapsed = false
    private var noticeScrollTravel = 0
    private var noticeRequest = 0
    private var noticeBatch = ""
    private var noticeFeed = emptyList<JSONObject>()
    private val noticeStates = mutableMapOf<String, JSONObject>()
    private val noticeAuthors = mutableMapOf<String, JSONObject>()
    private val noticeReadCounts = mutableMapOf<String, JSONObject>()
    private val noticeReaderPreviews = mutableMapOf<String, List<JSONObject>>()
    private val noticeReadSent = mutableSetOf<String>()
    private var noticeVisibleIds = emptyList<String>()
    private var noticeHeaderOffset = 0
    private val noticeBusy = mutableSetOf<String>()
    private val ownGoogleAvatar by lazy {
        GoogleSignIn.getLastSignedInAccount(activity)?.photoUrl?.toString()
            ?.takeIf { it.startsWith("https://") }
    }
    private var readerDialog: AlertDialog? = null
    private var libraryCategory = "theory"
    private var libraryFilter = "All"
    private data class LibrarySnapshot(
        val batchId: String,
        val offerings: List<JSONObject>,
        val courses: Map<String, JSONObject>,
        val files: List<JSONObject>,
        val favoriteIds: MutableSet<String>,
    )
    private var librarySnapshot: LibrarySnapshot? = null
    private var libraryLoadingRoot: View? = null
    fun invalidateLibrary() { librarySnapshot = null }
    fun invalidateNotices() { noticeRequest++; noticeFeed=emptyList(); noticeBatch="" }
    private val inflater get() = LayoutInflater.from(activity)
    private val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private fun selectedScheduleDate(): LocalDate {
        val today = LocalDate.now()
        val todayIndex = today.dayOfWeek.value % 7
        return today.plusDays(((selectedDay - todayIndex + 7) % 7).toLong())
    }

    fun render(tab: Int, host: LinearLayout) {
        val layout = when (tab) {
            R.id.nav_timetable -> R.layout.fragment_timetable
            R.id.nav_notices -> R.layout.fragment_notice
            R.id.nav_pdf -> R.layout.fragment_pdf_library
            else -> R.layout.classmate_profile
        }
        val root = inflater.inflate(layout, host, false)
        host.addView(root, LinearLayout.LayoutParams(-1, -1))
        navShown = true
        noticeHeaderCollapsed = false
        noticeScrollTravel = 0
        // Cold offline loads contain no suspending network call. Wait for attachment
        // so visibility guards do not discard the cached result before the first draw.
        root.doOnAttach {
            when (tab) {
                R.id.nav_timetable -> setupTimetable(root)
                R.id.nav_notices -> setupNotices(root)
                R.id.nav_pdf -> setupLibrary(root)
                else -> setupProfile(root)
            }
            bindFloatingNavigation(tab, root)
        }
    }

    private fun bindFloatingNavigation(tab: Int, root: View) {
        var navigationTravel = 0
        val threshold = (16 * activity.resources.displayMetrics.density).toInt()
        fun react(delta: Int, atTop: Boolean) {
            if (atTop) {
                navigationTravel = 0
                setNavigationShown(true)
                return
            }
            if (delta == 0) return
            navigationTravel = if ((navigationTravel >= 0 && delta > 0) ||
                (navigationTravel <= 0 && delta < 0))
                (navigationTravel + delta).coerceIn(-threshold * 2, threshold * 2) else delta
            if (navigationTravel >= threshold) setNavigationShown(false)
            if (navigationTravel <= -threshold) setNavigationShown(true)
        }
        when (tab) {
            R.id.nav_notices -> root.v<RecyclerView>(R.id.rvNotices)
                .addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        react(dy, !recyclerView.canScrollVertically(-1))
                        if (!recyclerView.canScrollVertically(-1)) {
                            noticeScrollTravel = 0
                            setNoticeHeaderCollapsed(root, false)
                        } else if (dy != 0) {
                            noticeScrollTravel = if ((noticeScrollTravel >= 0 && dy > 0) ||
                                (noticeScrollTravel <= 0 && dy < 0))
                                (noticeScrollTravel + dy).coerceIn(-threshold * 2, threshold * 2) else dy
                            if (noticeScrollTravel >= threshold) setNoticeHeaderCollapsed(root, true)
                            if (noticeScrollTravel <= -threshold) setNoticeHeaderCollapsed(root, false)
                        }
                        markVisibleNoticesRead(root, recyclerView)
                    }
                })
            R.id.nav_timetable, R.id.nav_pdf -> root.v<androidx.core.widget.NestedScrollView>(
                R.id.nestedScrollView).setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView,
                    _: Int, scrollY: Int, _: Int, oldY: Int -> react(scrollY - oldY, scrollY == 0) }
            R.id.nav_profile -> (root as androidx.core.widget.NestedScrollView)
                .setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView,
                    _: Int, scrollY: Int, _: Int, oldY: Int -> react(scrollY - oldY, scrollY == 0) }
        }
        if (tab == R.id.nav_timetable) {
            (root.getTag(R.id.btnToggleCalendar) as? ClassMateCalendarUi)?.scroll?.setOnScrollChangeListener { _, _, y, _, oldY ->
                react(y - oldY, y == 0)
            }
        }
    }

    private fun setNoticeHeaderCollapsed(root: View, collapsed: Boolean) {
        if (noticeHeaderCollapsed == collapsed || !root.isAttachedToWindow) return
        noticeHeaderCollapsed = collapsed
        val title = root.v<TextView>(R.id.tvNoticeTitle)
        val density = activity.resources.displayMetrics.density
        title.pivotX = 0f
        title.pivotY = 0f
        title.animate().cancel()
        title.animate().scaleX(if (collapsed) 0.86f else 1f)
            .scaleY(if (collapsed) 0.86f else 1f)
            .translationY(if (collapsed) 3f * density else 0f)
            .setDuration(180).start()
        val subtitle = root.v<View>(R.id.tvNoticeSubtitle)
        subtitle.animate().cancel()
        subtitle.animate().alpha(if (collapsed) 0f else 1f)
            .translationY(if (collapsed) -5f * density else 0f)
            .setDuration(150).start()
        listOf(R.id.btnSearch, R.id.btnPostNotice).forEach { id ->
            root.v<View>(id).animate().cancel()
            root.v<View>(id).animate().scaleX(if (collapsed) 0.88f else 1f)
                .scaleY(if (collapsed) 0.88f else 1f).setDuration(180).start()
        }
    }

    private fun setNavigationShown(show: Boolean) {
        if (navShown == show) return
        navShown = show
        val nav = activity.findViewById<View>(R.id.classmate_home_nav) ?: return
        nav.animate().cancel()
        nav.animate().translationY(if (show) 0f else
            nav.height.toFloat() + 24f * activity.resources.displayMetrics.density)
            .alpha(if (show) 1f else 0.15f).setDuration(220).start()
    }

    private fun <T : View> View.v(id: Int): T = findViewById(id)
    private fun rows(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
    private fun launch(root: View, task: suspend () -> Unit) {
        scope.launch {
            runCatching { task() }.onFailure { error ->
                if (root.isAttachedToWindow) Toast.makeText(activity,
                    error.message ?: "Could not load data", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun active(root: View) = root.isAttachedToWindow && batchId().isNotBlank()
    private fun text(view: View, id: Int, value: String) { view.v<TextView>(id).text = value }
    private fun hour(value: String): String = runCatching {
        LocalTime.parse(value.take(8)).format(DateTimeFormatter.ofPattern("hh:mm a"))
    }.getOrElse { value.take(5) }

    private class Cards(
        private val layout: Int,
        private val items: List<JSONObject>,
        private val bind: (View, JSONObject) -> Unit,
    ) : RecyclerView.Adapter<Cards.Holder>() {
        class Holder(val root: View) : RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.root, items[position])
    }
    private fun recycler(root: View, id: Int, layout: Int, items: List<JSONObject>,
                         bind: (View, JSONObject) -> Unit) {
        root.v<RecyclerView>(id).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = Cards(layout, items, bind)
        }
    }

    private fun updateDayPills(root: View) {
        val selector = root.v<LinearLayout>(R.id.daySelector)
        for (i in 0 until selector.childCount) {
            val card = selector.getChildAt(i)
            val date = card.tag as? LocalDate ?: continue
            val selected = date.dayOfWeek.value % 7 == selectedDay
            val closure = calendarData.classClosure(date)
            card.setBackgroundResource(if (selected) R.drawable.bg_day_card_selected else R.drawable.bg_day_card_unselected)
            if (closure != null && !selected) card.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * activity.resources.displayMetrics.density
                setColor(activity.getColor(R.color.cm_period_cancel_bg))
                setStroke((activity.resources.displayMetrics.density).toInt().coerceAtLeast(1), activity.getColor(R.color.cm_notice_cancel_text))
            }
            card.v<TextView>(R.id.tvDayShort).setTextColor(activity.getColor(if (selected) android.R.color.white else if (closure != null) R.color.cm_notice_cancel_text else R.color.cm_text_disabled))
            card.v<TextView>(R.id.tvDayDate).setTextColor(activity.getColor(if (selected) android.R.color.white else if (closure != null) R.color.cm_notice_cancel_text else R.color.cm_text_primary))
            card.v<View>(R.id.vDayIndicator).apply {
                visibility = if (selected || closure != null) View.VISIBLE else View.INVISIBLE
                backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(if (closure != null) R.color.cm_error else android.R.color.white))
            }
            card.contentDescription = "${days[date.dayOfWeek.value % 7]}, $date${closure?.let { ", $it. No classes" }.orEmpty()}"
        }
    }

    private fun setupTimetable(root: View) {
        val name = profile().optString("full_name").substringBefore(' ').ifBlank { "Student" }
        text(root, R.id.tvUserName, name)
        val hour = LocalTime.now().hour
        val greetings = when (hour) {
            in 0..4 -> listOf("Up late?", "A little planning for tomorrow?", "Your campus, even after hours.")
            in 5..11 -> listOf("Ready for a fresh start?", "Welcome back.", "What’s on your schedule?")
            in 12..17 -> listOf("Let’s plan the rest of your day.", "Your next class, at a glance.", "Ready for what’s next?")
            else -> listOf("Time to wrap up your day.", "A quick look at tomorrow?", "Welcome back.")
        }
        text(root, R.id.tvGreeting, greetings[LocalDate.now().dayOfYear % greetings.size])
        root.v<View>(R.id.heroNextClass).visibility = View.GONE
        root.v<View>(R.id.calendarExceptionBanner).visibility = View.GONE
        root.v<View>(R.id.countdownSection).visibility = View.GONE
        root.v<View>(R.id.btnAddPeriod).apply {
            visibility = if (profile().optString("role") in setOf("admin", "teacher") ||
                profile().optBoolean("is_cr")) View.VISIBLE else View.GONE
            setOnClickListener { onAddPeriod(null, selectedDay) }
        }
        val selector = root.v<LinearLayout>(R.id.daySelector)
        selector.removeAllViews()
        (0..6).forEach { offset ->
            val date = LocalDate.now().plusDays(offset.toLong())
            val index = date.dayOfWeek.value % 7
            val card = inflater.inflate(R.layout.item_day_card, selector, false)
            text(card, R.id.tvDayShort, days[index].take(3))
            text(card, R.id.tvDayDate, date.dayOfMonth.toString())
            card.tag = date
            if (index == selectedDay) {
                card.setBackgroundResource(R.drawable.bg_day_card_selected)
                card.v<View>(R.id.vDayIndicator).visibility = View.VISIBLE
                card.v<TextView>(R.id.tvDayShort).setTextColor(android.graphics.Color.WHITE)
                card.v<TextView>(R.id.tvDayDate).setTextColor(android.graphics.Color.WHITE)
            }
            card.setOnClickListener { selectedDay = index; setupTimetable(root) }
            selector.addView(card)
        }
        updateDayPills(root)
        val routine = root.v<TextView>(R.id.btnToggleRoutine)
        val bus = root.v<TextView>(R.id.btnToggleBus)
        val calendar = root.v<TextView>(R.id.btnToggleCalendar)
        val calendarUi = (root.getTag(R.id.btnToggleCalendar) as? ClassMateCalendarUi)
            ?: ClassMateCalendarUi(activity, scope, calendarData) { profile().optString("role") == "admin" }.also {
                root.setTag(R.id.btnToggleCalendar, it)
                (root as LinearLayout).addView(it.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        fun updateToggle() {
            root.v<View>(R.id.daySelector).visibility = if (calendarMode) View.GONE else View.VISIBLE
            root.v<View>(R.id.swipeRefresh).visibility = if (calendarMode) View.GONE else View.VISIBLE
            calendarUi.scroll.visibility = if (calendarMode) View.VISIBLE else View.GONE
            calendar.setBackgroundResource(if (calendarMode) R.drawable.bg_toggle_item_selected else android.R.color.transparent)
            calendar.setTextColor(activity.getColor(if (calendarMode) android.R.color.white else R.color.cm_text_secondary))
            routine.setBackgroundResource(if (busMode || calendarMode) android.R.color.transparent else R.drawable.bg_toggle_item_selected)
            bus.setBackgroundResource(if (busMode && !calendarMode) R.drawable.bg_toggle_item_selected else android.R.color.transparent)
            routine.setTextColor(activity.getColor(if (busMode || calendarMode) R.color.cm_text_secondary else android.R.color.white))
            bus.setTextColor(activity.getColor(if (busMode && !calendarMode) android.R.color.white else R.color.cm_text_secondary))
            val timetable = com.shuaib.classmate.databinding.FragmentTimetableBinding.bind(root)
            timetable.tvAddPeriod.text = if (busMode) "Add Bus" else "Add Period"
            timetable.btnAddPeriod.apply {
                val canEdit = if (busMode) canEditBus()
                    else profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr")
                visibility = if (canEdit && !calendarMode) View.VISIBLE else View.GONE
                setOnClickListener { if (busMode) onAddBus(null,busDayKind()) else onAddPeriod(null, selectedDay) }
            }
            if (calendarMode) { timetableRequest++; calendarUi.show() } else loadTimetable(root)
        }
        routine.setOnClickListener { busMode = false; calendarMode = false; updateToggle() }
        bus.setOnClickListener { busMode = true; calendarMode = false; updateToggle() }
        calendar.setOnClickListener { calendarMode = true; updateToggle() }
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadTimetable(root, true) }
        updateToggle()
    }

    private fun canEditBus(): Boolean {
        val account = profile()
        return account.optString("role") == "admin" || (account.optBoolean("is_cr") &&
            account.optString("verification_status") == "active" &&
            (account.isNull("cr_valid_until") || runCatching {
                Instant.parse(account.optString("cr_valid_until")).isAfter(Instant.now())
            }.getOrDefault(false)))
    }

    private fun loadTimetable(root: View, force: Boolean = false): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val shimmer = root.v<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerView)
        val day = selectedDay
        val effectiveDate = selectedScheduleDate()
        val selectedBatch = batchId()
        val requestedBusMode = busMode
        val request = ++timetableRequest
        if (calendarData.snapshot(effectiveDate.year) == null) {
            // Do not flash recurring classes before a cold calendar sync resolves the date.
            shimmer.visibility = View.VISIBLE; shimmer.startShimmer()
            root.v<View>(R.id.rvPeriods).visibility = View.GONE
            root.v<View>(R.id.emptyState).visibility = View.GONE
        }
        if (calendarData.snapshot(effectiveDate.year) == null || force) {
            runCatching { calendarData.refresh(effectiveDate.year, force) }
        } else {
            val oldCalendar = calendarData.events(effectiveDate.year).joinToString { it.toString() }
            scope.launch {
                runCatching { calendarData.refresh(effectiveDate.year) }
                if (active(root) && !calendarMode && day == selectedDay && requestedBusMode == busMode &&
                    oldCalendar != calendarData.events(effectiveDate.year).joinToString { it.toString() }) loadTimetable(root)
            }
        }
        if (request != timetableRequest || calendarMode || day != selectedDay) return@launch
        if (!active(root) || requestedBusMode != busMode || selectedBatch != batchId()) return@launch
        updateDayPills(root)
        val otherYears = (0..6).map { LocalDate.now().plusDays(it.toLong()).year }.toSet() - effectiveDate.year
        if (otherYears.isNotEmpty()) scope.launch {
            otherYears.forEach { year -> runCatching { calendarData.refresh(year) } }
            if (active(root) && !calendarMode) updateDayPills(root)
        }
        val closure = calendarData.classClosure(effectiveDate)
        root.v<View>(R.id.calendarExceptionBanner).visibility = View.GONE
        if (!requestedBusMode && closure != null) {
            shimmer.stopShimmer(); shimmer.visibility = View.GONE; refresh.isRefreshing = false
            root.v<View>(R.id.scheduleHeader).visibility = View.VISIBLE
            text(root, R.id.tvScheduleLabel, "${days[day].uppercase()}'S SCHEDULE")
            text(root, R.id.tvPeriodCount, "Holiday")
            root.v<View>(R.id.rvPeriods).visibility = View.GONE
            root.v<View>(R.id.emptyState).visibility = View.VISIBLE
            text(root, R.id.tvNoClassesTitle, closure)
            text(root, R.id.tvNoClassesSubtitle, "${effectiveDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))} · No classes scheduled on this holiday")
            return@launch
        }
        text(root, R.id.tvNoClassesTitle, "No classes scheduled")
        text(root, R.id.tvNoClassesSubtitle, "Enjoy your free day")
        val requestedBusKind = busDayKind()
        val cacheKind = if (requestedBusMode) "bus" else "routine"
        val accountId = profile().optString("id")
        var snapshot = ClassMateAcademicCache.read(activity, accountId, selectedBatch, cacheKind)
        val hadSnapshot = snapshot != null
        var completed = false
        if (snapshot == null) {
            shimmer.visibility = View.VISIBLE; shimmer.startShimmer()
            root.v<View>(R.id.rvPeriods).visibility = View.GONE
            root.v<View>(R.id.emptyState).visibility = View.GONE
        }
        try {
            if (snapshot == null && !ClassMateAcademicCache.online(activity)) error("No saved schedule yet. Connect once to sync this batch.")
            if (snapshot == null || (force && ClassMateAcademicCache.online(activity))) {
                if (requestedBusMode) {
                    val buses = ClassMateAuthApi.rows("bus_schedules", "select=id,route_name,departure_time,city_departure_time,schedule_kind,origin,destination,weekdays,notes,active&active=eq.true&order=departure_time")
                    snapshot = JSONObject().put("entries", buses).put("saved_at", System.currentTimeMillis())
                } else {
                    val semester = ClassMateAuthApi.rows("semesters", "select=id&batch_id=eq.$selectedBatch&status=eq.active&limit=1").optJSONObject(0)?.optString("id")
                    val offerings = if (semester == null) emptyList() else rows(ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester"))
                    val ids = offerings.map { it.getString("id") }
                    val bundle = coroutineScope {
                        val courses = async { rows(ClassMateCourses.catalog(selectedBatch)).associateBy { it.getString("id") } }
                        val routines = async { if (ids.isEmpty()) JSONArray() else ClassMateAuthApi.rows("routine_slots", "select=id,semester_course_id,day_of_week,start_time,end_time,room,type&semester_course_id=in.(${ids.joinToString(",")})&order=start_time") }
                        val teachers = async { if (ids.isEmpty()) JSONArray() else JSONArray(ClassMateAuthApi.rpcText("timetable_details", JSONObject().put("target_batch", selectedBatch).put("target_date", LocalDate.now().toString()).put("target_course_ids", JSONArray(ids)))) }
                        val changes = async { ClassMateAuthApi.rows("class_changes", "select=semester_course_id,effective_date&batch_id=eq.$selectedBatch&kind=eq.cancelled&effective_date=gte.${LocalDate.now()}&effective_date=lte.${LocalDate.now().plusDays(7)}") }
                        val assigned = async { when {
                            profile().optString("role") == "admin" || profile().optBoolean("is_cr") -> ids
                            profile().optString("role") == "teacher" -> rows(ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.$accountId&active=eq.true")).map { it.getString("semester_course_id") }
                            else -> emptyList()
                        } }
                        val names = JSONObject(); val catalog = courses.await()
                        offerings.forEach { names.put(it.getString("id"), catalog[it.getString("course_id")]?.optString("course_title") ?: "Course") }
                        JSONObject().put("entries", routines.await()).put("names", names).put("details", teachers.await()).put("changes", changes.await()).put("editable", JSONArray(assigned.await())).put("saved_at", System.currentTimeMillis())
                    }
                    snapshot = bundle
                }
                ClassMateAcademicCache.save(activity, accountId, selectedBatch, cacheKind, snapshot!!)
            }
            val data = snapshot!!
            val allEntries = rows(data.optJSONArray("entries") ?: JSONArray())
            val entries = allEntries.filter { item -> if (requestedBusMode) {
                val kind=item.optString("schedule_kind")
                if(kind in setOf("office_open","closed")) kind==requestedBusKind else {
                    val weekdays=item.optJSONArray("weekdays")
                    weekdays==null || (0 until weekdays.length()).any { weekdays.optInt(it)==day }
                }
            } else item.optInt("day_of_week") == day }
            val nameJson = data.optJSONObject("names") ?: JSONObject()
            val names = nameJson.keys().asSequence().associateWith { nameJson.optString(it) }
            val cancellations = rows(data.optJSONArray("changes") ?: JSONArray()).filter { it.optString("effective_date") == effectiveDate.toString() }.map { it.optString("semester_course_id") }.toSet()
            val details = rows(data.optJSONArray("details") ?: JSONArray()).associate { detail ->
                detail.getString("semester_course_id") to JSONObject(detail.toString()).put("cancelled", detail.getString("semester_course_id") in cancellations)
            }
            val editableCourses = data.optJSONArray("editable")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() } ?: emptySet()
            if (!active(root) || request != timetableRequest || day != selectedDay ||
                calendarMode || requestedBusMode != busMode || (requestedBusMode && requestedBusKind!=busDayKind()) || selectedBatch != batchId()) return@launch
            root.v<View>(R.id.scheduleHeader).visibility = View.VISIBLE
            text(root, R.id.tvScheduleLabel, if(requestedBusMode) "${days[day].uppercase()}'S BUS SCHEDULE" else "${days[day].uppercase()}'S SCHEDULE")
            text(root, R.id.tvPeriodCount, "${entries.size} ${if (requestedBusMode) "buses" else "classes"}")
            root.v<View>(R.id.emptyState).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            root.v<View>(R.id.rvPeriods).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            if (requestedBusMode) recycler(root, R.id.rvPeriods, R.layout.item_student_bus_schedule, entries) { card, item ->
                val paired=item.optString("schedule_kind") in setOf("office_open","closed") && !item.isNull("city_departure_time")
                text(card,R.id.tvBusScheduleName,if(paired) "Student bus departures" else item.optString("route_name"))
                text(card,R.id.tvCampusDepartureLabel,if(paired) "Campus → City" else "${item.optString("origin")} → ${item.optString("destination")}")
                text(card,R.id.tvCampusDeparture,hour(item.optString("departure_time")))
                card.v<View>(R.id.cityDepartureColumn).visibility=if(paired) View.VISIBLE else View.GONE
                if(paired) text(card,R.id.tvCityDeparture,hour(item.optString("city_departure_time")))
                if (canEditBus()) {
                    card.setOnClickListener { onAddBus(item,requestedBusKind) }
                    card.setOnLongClickListener { onAddBus(item,requestedBusKind); true }
                    card.contentDescription = "${item.optString("route_name")}. Tap to edit bus schedule"
                }
            } else recycler(root, R.id.rvPeriods, R.layout.item_period, entries) { card, item ->
                val courseId = item.optString("semester_course_id")
                val detail = details[courseId]
                val cancelled = detail?.optBoolean("cancelled") == true
                text(card, R.id.tvSubject, names[courseId] ?: "Class")
                text(card, R.id.tvStartTime, hour(item.optString("start_time")))
                text(card, R.id.tvEndTime, hour(item.optString("end_time")))
                val teacherName = detail?.optString("teacher_name").orEmpty().trim().takeUnless { it.equals("null", true) }.orEmpty()
                text(card, R.id.tvTeacher, teacherName)
                card.v<View>(R.id.layoutTeacherInfo).visibility =
                    if (teacherName.isBlank()) View.GONE else View.VISIBLE
                val room = item.optString("room").takeUnless { it == "null" }.orEmpty()
                text(card, R.id.tvRoom, room)
                card.v<View>(R.id.layoutRoomInfo).visibility =
                    if (room.isBlank()) View.GONE else View.VISIBLE
                text(card, R.id.tvTypeBadge,
                    if (cancelled) "CANCELLED" else item.optString("type").uppercase())
                if (cancelled) {
                    card.v<com.google.android.material.card.MaterialCardView>(R.id.cardRoot)
                        .setCardBackgroundColor(activity.getColor(R.color.cm_period_cancel_bg))
                    card.v<TextView>(R.id.tvTypeBadge).apply {
                        setBackgroundResource(R.drawable.bg_badge_cancelled)
                        setTextColor(activity.getColor(R.color.cm_notice_cancel_text))
                    }
                }
                val minutes = runCatching {
                    java.time.Duration.between(LocalTime.parse(item.getString("start_time")),
                        LocalTime.parse(item.getString("end_time"))).toMinutes()
                }.getOrDefault(0)
                text(card, R.id.tvDuration, "$minutes min")
                if (item.optString("semester_course_id") in editableCourses) {
                    card.setOnLongClickListener { onAddPeriod(item, day); true }
                    card.setOnClickListener { onAddPeriod(item, day) }
                }
            }
            completed = true
            if (!force && ClassMateAcademicCache.online(activity) && System.currentTimeMillis() - data.optLong("saved_at") > 60_000)
                root.post { if (active(root) && day == selectedDay && requestedBusMode == busMode) loadTimetable(root, true) }
        } catch (e: Exception) {
            if (!hadSnapshot) throw e
            // A failed background sync never replaces the visible, last-synced schedule.
        } finally {
            refresh.isRefreshing = false
            if (request == timetableRequest && root.isAttachedToWindow) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
                if (!completed && !hadSnapshot) {
                    root.v<View>(R.id.emptyState).visibility = View.VISIBLE
                    text(root, R.id.tvNoClassesTitle, "Could not load schedule")
                    text(root, R.id.tvNoClassesSubtitle, "Connect and pull to sync this batch")
                }
            }
        }
    }

    private fun setupNotices(root: View) {
        val feed = root.v<RecyclerView>(R.id.rvNotices)
        feed.itemAnimator = null
        val header = root.v<View>(R.id.noticeHeaderPanel)
        var headerPositioned = false
        fun fitFeedBelowHeader() {
            // Full-screen feed: padding sets its starting position without making
            // opaque header/footer bands. Anchor the first layout after measuring
            // the header so cached cards cannot retain the earlier padding offset.
            if (header.height == 0) return
            val params = feed.layoutParams as android.view.ViewGroup.MarginLayoutParams
            val top = header.bottom + (8 * activity.resources.displayMetrics.density).toInt()
            val atTop = !headerPositioned || !feed.canScrollVertically(-1)
            if (params.topMargin != 0) { params.topMargin = 0; feed.layoutParams = params }
            if (feed.paddingTop != top) {
                feed.setPadding(feed.paddingLeft, top, feed.paddingRight, feed.paddingBottom)
                if (atTop) (feed.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            }
            headerPositioned = true
            listOf(R.id.shimmerView, R.id.emptyNoticeState).forEach { id ->
                val overlay = root.v<View>(id)
                val overlayParams = overlay.layoutParams as android.view.ViewGroup.MarginLayoutParams
                if (overlayParams.topMargin != 0) { overlayParams.topMargin = 0; overlay.layoutParams = overlayParams }
                overlay.setPadding(overlay.paddingLeft, top, overlay.paddingRight, overlay.paddingBottom)
            }
        }
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitFeedBelowHeader() }
        header.post { if (root.isAttachedToWindow) fitFeedBelowHeader() }
        root.v<View>(R.id.shimmerView).visibility = View.GONE
        root.v<View>(R.id.layoutReminderBanner).visibility = View.GONE
        root.v<View>(R.id.btnLoadOlder).visibility = View.GONE
        root.v<View>(R.id.progressOlder).visibility = View.GONE
        val canPost = profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr")
        root.v<View>(R.id.btnPostNotice).apply {
            visibility = if (canPost) View.VISIBLE else View.GONE
            setOnClickListener { onPostNotice() }
        }
        root.v<View>(R.id.btnSearch).setOnClickListener {
            root.v<View>(R.id.headerTitleBlock).visibility = View.GONE
            root.v<View>(R.id.btnSearch).visibility = View.GONE
            root.v<View>(R.id.searchContainer).visibility = View.VISIBLE
            val input = root.v<EditText>(R.id.etNoticeSearch)
            input.requestFocus()
            input.post {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
                    android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
            }
        }
        root.v<View>(R.id.btnCloseSearch).setOnClickListener {
            root.v<EditText>(R.id.etNoticeSearch).text.clear()
            root.v<View>(R.id.searchContainer).visibility = View.GONE
            root.v<View>(R.id.headerTitleBlock).visibility = View.VISIBLE
            root.v<View>(R.id.btnSearch).visibility = View.VISIBLE
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
                android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
            renderNoticeFeed(root)
        }
        root.v<EditText>(R.id.etNoticeSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderNoticeFeed(root)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadNotices(root) }
        if (noticeBatch != batchId() || noticeFeed.isEmpty()) restoreNoticeCache()
        if (noticeBatch == batchId() && noticeFeed.isNotEmpty()) renderNoticeFeed(root)
        if (ClassMateAcademicCache.online(activity)) loadNotices(root)
    }

    private fun loadNotices(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
        if (!ClassMateAcademicCache.online(activity)) { refresh.isRefreshing = false; renderNoticeFeed(root); return@launch }
        val request = ++noticeRequest
        val shimmer = root.v<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerView)
        if (noticeFeed.isEmpty() || noticeBatch != selectedBatch) {
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
        }
        try {
                val visible = rows(ClassMateAuthApi.rows("notices",
                "select=id,title,body,published_at,semester_course_id,author_id,resource_id" +
                    "&batch_id=eq.$selectedBatch&order=published_at.desc&limit=100"))
            if (!active(root) || request != noticeRequest || selectedBatch != batchId()) return@launch
            noticeBatch = selectedBatch
            noticeFeed = visible
            saveNoticeCache()
            renderNoticeFeed(root)
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            val (engagement, authors, reads, previews) = if (visible.isEmpty())
                listOf(emptyList<JSONObject>(), emptyList(), emptyList(), emptyList())
            else {
                val details=ClassMateAuthApi.rpc("notice_feed_details",JSONObject().put("target_ids",JSONArray(visible.map { it.getString("id") })))
                listOf("engagement","authors","reads","previews").map { key -> rows(details.optJSONArray(key) ?: JSONArray()) }
            }
            if (!active(root) || request != noticeRequest || selectedBatch != batchId()) return@launch
            previews.groupBy { it.getString("notice_id") }.forEach { (id, readers) -> noticeReaderPreviews[id] = readers }
            engagement.forEach { noticeStates[it.getString("notice_id")] = it }
            authors.forEach { noticeAuthors[it.getString("notice_id")] = it }
            reads.forEach {
                noticeReadCounts[it.getString("notice_id")] = it
                if (it.optBoolean("read_by_me")) noticeReadSent.add(it.getString("notice_id"))
            }
            saveNoticeCache()
            renderNoticeFeed(root)
        } catch (e: Exception) {
            if (noticeFeed.isEmpty()) throw e
        } finally {
            refresh.isRefreshing = false
            if (request == noticeRequest && root.isAttachedToWindow) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
            }
        }
    }

    private fun saveNoticeCache() {
        fun mapJson(map: Map<String, JSONObject>) = JSONObject().apply { map.forEach { (id, value) -> put(id, value) } }
        val previews = JSONObject().apply { noticeReaderPreviews.forEach { (id, value) -> put(id, JSONArray(value)) } }
        ClassMateAcademicCache.save(activity, profile().optString("id"), batchId(), "notices", JSONObject()
            .put("feed", JSONArray(noticeFeed)).put("states", mapJson(noticeStates)).put("authors", mapJson(noticeAuthors))
            .put("reads", mapJson(noticeReadCounts)).put("previews", previews))
    }
    private fun restoreNoticeCache() {
        noticeFeed = emptyList(); noticeStates.clear(); noticeAuthors.clear(); noticeReadCounts.clear(); noticeReaderPreviews.clear()
        noticeBatch = batchId()
        val data = ClassMateAcademicCache.read(activity, profile().optString("id"), batchId(), "notices") ?: return
        noticeBatch = batchId(); noticeFeed = rows(data.optJSONArray("feed") ?: JSONArray())
        fun restore(name: String, map: MutableMap<String, JSONObject>) { val json = data.optJSONObject(name) ?: return; json.keys().forEach { id -> json.optJSONObject(id)?.let { map[id] = it } } }
        restore("states", noticeStates); restore("authors", noticeAuthors); restore("reads", noticeReadCounts)
        data.optJSONObject("previews")?.let { json -> json.keys().forEach { id -> noticeReaderPreviews[id] = rows(json.optJSONArray(id) ?: JSONArray()) } }
    }

    private fun renderNoticeFeed(root: View) {
        if (!root.isAttachedToWindow) return
        val search = root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()
        text(root, R.id.tvNoticeSubtitle, "${noticeFeed.size} updates · ${if (ClassMateAcademicCache.online(activity)) "Pull to refresh" else "Offline · Last synced"}")
        val filtered = noticeFeed.filter { search.isBlank() ||
            it.optString("title").contains(search, true) || it.optString("body").contains(search, true) }
            .sortedWith(compareByDescending<JSONObject> {
                noticeStates[it.optString("id")]?.optBoolean("is_pinned") == true
            }.thenByDescending { it.optString("published_at") })
        noticeVisibleIds = filtered.map { it.optString("id") }
        noticeHeaderOffset = if (filtered.isEmpty()) 0 else 1
        root.v<View>(R.id.emptyNoticeState).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        val list = root.v<RecyclerView>(R.id.rvNotices)
        val scrollState = list.layoutManager?.onSaveInstanceState()
        if (list.layoutManager == null) list.layoutManager = LinearLayoutManager(activity)
        val noticeCards = Cards(R.layout.item_notice_modern, filtered) { card, item ->
                val noticeId = item.getString("id")
                val state = noticeStates[noticeId]
                val resourceId = item.optString("resource_id").takeUnless {
                    it.isBlank() || it == "null" }
                val title = item.optString("title")
                val resourceNotice = resourceId != null || title.contains(
                    Regex("(?i)\\bresource\\s*:"))
                val cancellation = !resourceNotice && title.contains(
                    Regex("(?i)\\b(cancelled|canceled|cancellation)\\b"))
                val (background, icon, accent) = when {
                    resourceNotice -> Triple(R.drawable.bg_notice_premium_resource,
                        R.drawable.ic_notice_resource_art, R.color.cm_notice_premium_resource)
                    cancellation -> Triple(R.drawable.bg_notice_premium_cancel,
                        R.drawable.ic_notice_calendar_cancel_art, R.color.cm_notice_premium_cancel)
                    else -> Triple(R.drawable.bg_notice_premium_general,
                        R.drawable.ic_notice_megaphone_art, R.color.cm_notice_premium_general)
                }
                card.v<View>(R.id.noticeCardSurface).setBackgroundResource(background)
                card.v<android.widget.ImageView>(R.id.ivNoticeIllustration).setImageResource(icon)
                card.v<android.widget.ImageView>(R.id.ivNoticeIllustration).imageTintList =
                    if (resourceNotice) null else android.content.res.ColorStateList.valueOf(activity.getColor(accent))
                card.v<View>(R.id.noticeAccent).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(activity.getColor(accent))
                card.v<TextView>(R.id.tvTitle).text = ClassMateNoticeText.styled(card.v(R.id.tvTitle), title, search)
                ClassMateNoticeText.bind(card.v(R.id.tvPreview), item.optString("body"), search) { showNoticeContent(item, search) }
                card.v<View>(R.id.tvPreview).visibility =
                    if (item.optString("body").isBlank()) View.GONE else View.VISIBLE
                text(card, R.id.tvMeta, noticeDate(item.optString("published_at")))
                text(card, R.id.tvLikeCount, state?.optLong("like_count")?.toString() ?: "0")
                text(card, R.id.tvCommentCount, state?.optLong("comment_count")?.toString() ?: "0")
                text(card, R.id.tvReadCount, seenLabel(noticeReadCounts[noticeId]?.optLong("read_count") ?: 0))
                bindSeenAvatar(card, noticeId)
                card.v<View>(R.id.btnReadReceipts).setOnClickListener {
                    showNoticeReadReceipts(root, item)
                }
                card.v<android.widget.ImageView>(R.id.ivLikeIcon).setImageResource(
                    if (state?.optBoolean("is_liked") == true) R.drawable.ic_heart_filled
                    else R.drawable.ic_heart_outline)
                card.v<android.widget.ImageView>(R.id.ivLikeIcon).imageTintList =
                    android.content.res.ColorStateList.valueOf(activity.getColor(
                        if (state?.optBoolean("is_liked") == true) R.color.cm_notice_like
                        else R.color.cm_text_muted))
                card.v<View>(R.id.btnLike).setOnClickListener {
                    setNoticeReaction(root, card, noticeId)
                }
                card.v<View>(R.id.btnComment).setOnClickListener { showNoticeComments(root, item) }
                card.findViewById<View>(R.id.imagePreviewContainer)?.visibility = View.GONE
                card.v<View>(R.id.fileAttachmentContainer).visibility =
                    if (resourceId == null) View.GONE else View.VISIBLE
                if (resourceId != null) {
                    text(card, R.id.tvFileName, item.optString("title").removePrefix("Resource: "))
                    text(card, R.id.tvFileSize, "Tap to open protected file")
                    text(card, R.id.tvFileExtension, "FILE")
                    card.v<View>(R.id.fileAttachmentContainer).setOnClickListener {
                        openFile(JSONObject().put("id", resourceId))
                    }
                    card.v<View>(R.id.btnOpenFile).setOnClickListener {
                        openFile(JSONObject().put("id", resourceId))
                    }
                }
                val canManage = profile().optString("role") == "admin" ||
                    (item.optString("author_id") == profile().optString("id") &&
                        (profile().optString("role") == "teacher" || profile().optBoolean("is_cr")))
                card.v<View>(R.id.btnOptions).apply {
                    visibility = View.VISIBLE
                    isEnabled = canManage
                    alpha = if (canManage) 1f else 0.45f
                    setOnClickListener { if (canManage) showNoticeActions(root, item) }
                }
                card.v<View>(R.id.btnLike).visibility = View.VISIBLE
                card.v<View>(R.id.btnComment).visibility = View.VISIBLE
                card.v<View>(R.id.btnReminder).visibility = View.VISIBLE
                val reminderAt = state?.optString("reminder_at")
                val reminderPending = runCatching {
                    reminderAt?.let(Instant::parse)?.isAfter(Instant.now()) == true
                }.getOrDefault(false)
                setReminderAppearance(card, reminderPending)
                card.v<View>(R.id.btnReminder).setOnClickListener { showReminderOptions(root, card, item) }
                card.v<View>(R.id.cardRoot).setOnClickListener {
                    showNoticeContent(item, search)
                }
            }
        list.adapter = if (filtered.isEmpty()) noticeCards else ConcatAdapter(
            Cards(R.layout.item_notice_summary, listOf(filtered.maxBy { it.optString("published_at") })) { summary, latest ->
                val authorId = latest.optString("author_id")
                val author = noticeAuthors[latest.optString("id")]
                text(summary, R.id.tvSummaryName, author?.optString("author_name")
                    ?.takeIf { it.isNotBlank() } ?: if (authorId == profile().optString("id"))
                    profile().optString("full_name") else "ClassMate member")
                val count = filtered.count { it.optString("author_id") == authorId }
                text(summary, R.id.tvSummaryMeta,
                    "$count ${if (count == 1) "update" else "updates"} · Latest: " +
                        noticeDate(latest.optString("published_at")).substringBefore(','))
                val avatar = author?.optString("avatar_url").orEmpty()
                val image = summary.v<android.widget.ImageView>(R.id.ivSummaryAvatar)
                if (avatar.startsWith("https://")) com.bumptech.glide.Glide.with(summary)
                    .load(avatar).placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar).into(image)
                else image.setImageResource(R.drawable.ic_default_avatar)
            }, noticeCards)
        list.layoutManager?.onRestoreInstanceState(scrollState)
        list.post { if (root.isAttachedToWindow) markVisibleNoticesRead(root, list) }
    }

    private fun noticeDate(value: String): String = runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrElse { value.replace('T', ' ').take(16) }

    private fun compactCount(count: Long): String = when {
        count >= 1_000_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000_000.0)}m"
        count >= 1_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000.0)}k"
        else -> count.toString()
    }
    private fun seenLabel(count: Long) = "${compactCount(count)} seen"

    private fun showNoticeContent(item: JSONObject, query: String = "") {
        val form = ClassMateFormUi(activity)
        form.label(item.optString("body")).apply {
            textSize = 16f
            text = ClassMateNoticeText.styled(this, item.optString("body"), query)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(activity.getColor(R.color.cm_primary))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setBackground(noticeDialogSurface()).setTitle(item.optString("title")).setView(form.scroll)
            .setNegativeButton("Close", null).setPositiveButton("Copy notice", null).create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ClassMate notice", item.optString("title") + "\n\n" + item.optString("body")))
                    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(activity, "Notice copied", Toast.LENGTH_SHORT).show()
                } }; dialog.show()
            }
    }

    private fun bindSeenAvatar(card: View, noticeId: String) {
        val group = card.v<android.widget.FrameLayout>(R.id.seenAvatars)
        group.removeAllViews()
        val readers = noticeReaderPreviews[noticeId].orEmpty().take(if (activity.resources.configuration.screenWidthDp < 360) 3 else 4)
        group.visibility = if (readers.isEmpty()) View.GONE else View.VISIBLE
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        group.layoutParams = group.layoutParams.apply { width = dp(22 + (readers.size - 1).coerceAtLeast(0) * 14); height = dp(22) }
        readers.forEachIndexed { index, reader ->
            val image = de.hdodenhof.circleimageview.CircleImageView(activity).apply {
                borderWidth = dp(1); borderColor = activity.getColor(R.color.cm_surface)
                contentDescription = reader.optString("reader_name")
                setImageResource(R.drawable.ic_default_avatar)
            }
            group.addView(image, android.widget.FrameLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(index * 14) })
            reader.optString("avatar_url").takeIf { it.startsWith("https://") }?.let {
                com.bumptech.glide.Glide.with(card).load(it).placeholder(R.drawable.ic_default_avatar).error(R.drawable.ic_default_avatar).into(image)
            }
        }
    }

    private fun setReminderAppearance(card: View, pending: Boolean) {
        card.v<android.widget.ImageView>(R.id.ivReminderIcon).setImageResource(
            if (pending) R.drawable.ic_notice_reminder_active
            else R.drawable.ic_notice_reminder_outline)
        card.v<android.widget.ImageView>(R.id.ivReminderIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(activity.getColor(
                if (pending) R.color.cm_primary else R.color.cm_text_muted))
        card.v<View>(R.id.btnReminder).contentDescription =
            if (pending) "Reminder set. Change reminder" else "Set notice reminder"
    }

    private fun markVisibleNoticesRead(root: View, list: RecyclerView) {
        if (!ClassMateAcademicCache.online(activity)) return
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(noticeHeaderOffset)
        val last = manager.findLastVisibleItemPosition().coerceAtMost(
            noticeVisibleIds.lastIndex + noticeHeaderOffset)
        if (last < first) return
        val newIds = (first..last).mapNotNull { position ->
            val child = manager.findViewByPosition(position) ?: return@mapNotNull null
            if (child.bottom <= list.paddingTop || child.top >= list.height ||
                (minOf(child.bottom, list.height) - maxOf(child.top, list.paddingTop)) <
                child.height / 2) return@mapNotNull null
            noticeVisibleIds.getOrNull(position - noticeHeaderOffset)?.takeIf { noticeReadSent.add(it) }
        }
        if (newIds.isEmpty()) return
        launch(root) {
            runCatching {
                ClassMateAuthApi.rpcText("mark_notices_read", JSONObject()
                    .put("target_ids", JSONArray(newIds)))
            }.onSuccess {
                newIds.forEach { id ->
                    val previews = noticeReaderPreviews[id].orEmpty()
                    if (previews.none { it.optString("profile_id") == profile().optString("id") })
                        noticeReaderPreviews[id] = (listOf(JSONObject().put("profile_id", profile().optString("id"))
                            .put("reader_name", profile().optString("full_name")).put("avatar_url", ownGoogleAvatar ?: "")) + previews).take(4)
                    val count = noticeReadCounts.getOrPut(id) { JSONObject() }
                    if (!count.optBoolean("read_by_me")) {
                        count.put("read_by_me", true)
                        count.put("read_count", count.optLong("read_count") + 1)
                    }
                }
                saveNoticeCache()
                for (position in first..last) {
                    val id = noticeVisibleIds.getOrNull(position - noticeHeaderOffset)
                    val view = manager.findViewByPosition(position)
                    if (id in newIds && view != null) {
                        text(view, R.id.tvReadCount,
                            seenLabel(noticeReadCounts[id]?.optLong("read_count") ?: 0))
                        id?.let { bindSeenAvatar(view, it) }
                    }
                }
            }.onFailure { noticeReadSent.removeAll(newIds.toSet()) }
        }
    }

    private fun showNoticeReadReceipts(root: View, notice: JSONObject) {
        if (readerDialog?.isShowing == true) return
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val form = ClassMateFormUi(activity)
        val status = form.label("Loading readers…")
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        form.panel.addView(list)
        val background = com.google.android.material.shape.MaterialShapeDrawable(
            com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(dp(24).toFloat()).build()).apply {
            fillColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface))
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setBackground(background).setTitle("Seen by")
            .setView(form.scroll).setPositiveButton("Close", null).setNeutralButton("Refresh", null).create()
        readerDialog = dialog
        var cursorTime: String?=null
        var cursorId: String?=null
        var loading=false
        var loadedReaders=0
        val more=com.google.android.material.button.MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="Load more";visibility=View.GONE }
        form.panel.addView(more)
        fun load(reset: Boolean=true) {
            if(loading) return
            loading=true
            if(reset) {cursorTime=null;cursorId=null;loadedReaders=0;list.removeAllViews()}
            more.isEnabled=false
            status.visibility = View.VISIBLE; status.text = "Loading readers…"
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
            scope.launch {
                try {
                    val arguments=JSONObject().put("target_notice",notice.getString("id")).put("page_size",50)
                    cursorTime?.let { arguments.put("before_time",it).put("before_id",cursorId) }
                    val readers=rows(JSONArray(ClassMateAuthApi.rpcText("notice_readers_page",arguments)))
                    if (!dialog.isShowing || activity.isFinishing) return@launch
                    loadedReaders+=readers.size
                    dialog.setTitle("Seen by ${noticeReadCounts[notice.getString("id")]?.optLong("read_count") ?: loadedReaders}")
                    readers.lastOrNull()?.let { cursorTime=it.optString("read_at");cursorId=it.optString("profile_id") }
                    more.visibility=if(readers.size==50) View.VISIBLE else View.GONE
                    status.visibility = if (loadedReaders==0) View.VISIBLE else View.GONE
                    status.text = "No one has read this notice yet"
                    form.scroll.layoutParams = form.scroll.layoutParams.apply {
                        height = minOf(dp(48 + maxOf(1, loadedReaders) * 56), (activity.resources.displayMetrics.heightPixels * 0.48f).toInt())
                    }
                    readers.forEach { reader ->
                        val row = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
                        val avatar = de.hdodenhof.circleimageview.CircleImageView(activity).apply { setImageResource(R.drawable.ic_default_avatar) }
                        row.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
                        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                        labels.addView(TextView(activity).apply { text = reader.optString("reader_name"); textSize = 14f; setTextColor(activity.getColor(R.color.cm_text_primary)) })
                        labels.addView(TextView(activity).apply { text = noticeDate(reader.optString("read_at")); textSize = 12f; setTextColor(activity.getColor(R.color.cm_text_secondary)) })
                        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); list.addView(row)
                        reader.optString("avatar_url").takeIf { it.startsWith("https://") }?.let { com.bumptech.glide.Glide.with(activity).load(it).placeholder(R.drawable.ic_default_avatar).error(R.drawable.ic_default_avatar).into(avatar) }
                    }
                } catch (e: Exception) { if (dialog.isShowing) { status.visibility = View.VISIBLE; status.text = "Could not load readers. Tap Refresh to retry." } }
                finally { loading=false;more.isEnabled=true;if (dialog.isShowing) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true }
            }
        }
        dialog.setOnDismissListener { readerDialog = null }
        more.setOnClickListener { load(false) }
        dialog.setOnShowListener {
            form.scroll.layoutParams = form.scroll.layoutParams.apply { height = (activity.resources.displayMetrics.heightPixels * 0.48f).toInt() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load() }
            load()
        }
        dialog.show()
    }

    private fun setNoticeReaction(root: View, card: View, noticeId: String) {
        if (!ClassMateAcademicCache.online(activity)) { Toast.makeText(activity, "Connect to update reactions", Toast.LENGTH_SHORT).show(); return }
        if (!noticeBusy.add(noticeId)) return
        val state = noticeStates.getOrPut(noticeId) { JSONObject() }
        val ownKey = "is_liked"
        val wasEnabled = state.optBoolean(ownKey)
        val enabled = !wasEnabled
        val oldCount = state.optLong("like_count")
        state.put(ownKey, enabled)
        state.put("like_count", (oldCount + if (enabled) 1 else -1).coerceAtLeast(0))
        text(card, R.id.tvLikeCount, compactCount(state.optLong("like_count")))
        card.v<android.widget.ImageView>(R.id.ivLikeIcon).setImageResource(
            if (enabled) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        card.v<android.widget.ImageView>(R.id.ivLikeIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(activity.getColor(
                if (enabled) R.color.cm_notice_like else R.color.cm_text_muted))
        launch(root) {
            runCatching {
                val row = JSONObject().put("profile_id", profile().getString("id"))
                    .put("notice_id", noticeId).put("liked", state.optBoolean("is_liked"))
                    .put("pinned", state.optBoolean("is_pinned"))
                state.optString("reminder_at").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { row.put("reminder_at", it) }
                ClassMateAuthApi.upsert("notice_reactions", row)
                saveNoticeCache()
            }.onFailure {
                    state.put(ownKey, wasEnabled)
                    state.put("like_count", oldCount)
                    renderNoticeFeed(root)
                    Toast.makeText(activity, "Could not update notice", Toast.LENGTH_SHORT).show()
                }
            noticeBusy.remove(noticeId)
        }
    }

    private fun noticeDialogSurface() = com.google.android.material.shape.MaterialShapeDrawable(
        com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(24f * activity.resources.displayMetrics.density).build()
    ).apply { fillColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface)) }

    private fun showNoticeActions(root: View, notice: JSONObject) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setBackground(noticeDialogSurface()).setTitle("Manage notice")
            .setItems(arrayOf("Edit title and message", "Delete notice")) { _, option ->
                if (option == 0) editNotice(root, notice) else deleteNotice(root, notice)
            }.show()
    }

    private fun editNotice(root: View, notice: JSONObject) {
        val form = ClassMateFormUi(activity)
        val titleField = form.field("Notice title").apply { setText(notice.optString("title")) }
        val bodyField = form.field("Message · links supported", true).apply { setText(notice.optString("body")) }
        val status = form.status()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setBackground(noticeDialogSurface()).setTitle("Edit notice").setView(form.scroll)
            .setPositiveButton("Save changes", null).setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = titleField.text.toString().trim()
                        if (title.isBlank()) {
                            titleField.error = "Enter a title"
                            return@setOnClickListener
                        }
                        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        save.isEnabled = false
                        launch(root) {
                            try {
                            val updated = ClassMateAuthApi.rpc("edit_notice", JSONObject()
                                .put("target_id", notice.getString("id"))
                                .put("target_title", title)
                                .put("target_body", bodyField.text.toString().trim()))
                            val index = noticeFeed.indexOfFirst { it.optString("id") == notice.optString("id") }
                            if (index >= 0) noticeFeed = noticeFeed.toMutableList().apply { set(index, updated) }
                            saveNoticeCache()
                            renderNoticeFeed(root)
                            dialog.dismiss()
                            } catch (e: Exception) { status.visibility = View.VISIBLE; status.text = e.message ?: "Could not save. Try again." }
                            finally { save.isEnabled = true }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun deleteNotice(root: View, notice: JSONObject) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setBackground(noticeDialogSurface()).setTitle("Delete notice?")
            .setMessage("This notice and its comments will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                launch(root) {
                    val id = notice.getString("id")
                    ClassMateAuthApi.rpcText("delete_notice", JSONObject().put("target_id", id))
                    noticeFeed = noticeFeed.filterNot { it.optString("id") == id }
                    noticeStates.remove(id)
                    saveNoticeCache()
                    ClassMateNoticeReminderScheduler.schedule(activity, id,
                        notice.optString("title"), null)
                    renderNoticeFeed(root)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showNoticeComments(root: View, notice: JSONObject): Unit = launch(root) {
        val noticeId = notice.getString("id")
        val comments = rows(ClassMateAuthApi.rows("notice_comments",
            "select=id,body,created_at,author_id&notice_id=eq.$noticeId&order=created_at.asc&limit=100"))
        if (!active(root)) return@launch
        val content = comments.joinToString("\n\n") { comment ->
            val author = if (comment.optString("author_id") == profile().optString("id"))
                profile().optString("full_name") else "ClassMate member"
            "$author · ${comment.optString("created_at").take(10)}\n${comment.optString("body")}" 
        }.ifBlank { "No comments yet" }
        AlertDialog.Builder(activity).setTitle("Comments · ${notice.optString("title")}")
            .setMessage(content).setPositiveButton("Add comment") { _, _ ->
                val field = EditText(activity).apply { hint = "Write a comment" }
                AlertDialog.Builder(activity).setTitle("Add comment").setView(field)
                    .setPositiveButton("Post") { _, _ ->
                        val body = field.text.toString().trim()
                        if (body.isBlank()) return@setPositiveButton
                        launch(root) {
                            ClassMateAuthApi.insert("notice_comments", JSONObject()
                                .put("notice_id", noticeId).put("author_id", profile().getString("id"))
                                .put("body", body))
                            if (active(root)) { loadNotices(root); showNoticeComments(root, notice) }
                        }
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun showReminderOptions(root: View, card: View, notice: JSONObject) {
        val options = arrayOf("In 1 minute", "In one hour", "Tomorrow at 9 AM", "Clear reminder")
        AlertDialog.Builder(activity).setTitle("Remind me about this notice")
            .setItems(options) { _, choice ->
                val at = when (choice) {
                    0 -> Instant.now().plusSeconds(60)
                    1 -> Instant.now().plusSeconds(3600)
                    2 -> ZonedDateTime.now().plusDays(1).withHour(9).withMinute(0)
                        .withSecond(0).withNano(0).toInstant()
                    else -> null
                }
                launch(root) {
                    val profileId = profile().getString("id")
                    val noticeId = notice.getString("id")
                    val state = noticeStates.getOrPut(noticeId) { JSONObject() }
                    val row = JSONObject().put("profile_id", profileId).put("notice_id", noticeId)
                        .put("liked", state.optBoolean("is_liked"))
                        .put("pinned", state.optBoolean("is_pinned"))
                        .put("reminder_at", at?.toString() ?: JSONObject.NULL)
                    ClassMateAuthApi.upsert("notice_reactions", row)
                    ClassMateNoticeReminderScheduler.schedule(activity, noticeId,
                        notice.optString("title"), at)
                    if (active(root)) {
                        state.put("reminder_at", at?.toString() ?: JSONObject.NULL)
                        saveNoticeCache()
                        setReminderAppearance(card, at != null)
                        Toast.makeText(activity, if (at == null) "Reminder cleared" else "Reminder set",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun setupLibrary(root: View) {
        root.v<View>(R.id.btnUploadPdf).apply {
            visibility = if (profile().optString("role") in setOf("admin", "teacher") ||
                profile().optBoolean("is_cr")) View.VISIBLE else View.GONE
            setOnClickListener { onUpload() }
        }
        root.v<View>(R.id.btnLibrarySearch).setOnClickListener { searchLibrary(root) }
        root.v<View>(R.id.tvViewAll).setOnClickListener { showAllFiles() }
        root.v<EditText>(R.id.etLibrarySearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { loadLibrary(root) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        listOf("All" to R.id.chipAll, "Notes" to R.id.chipNotes,
            "Slides" to R.id.chipSlides, "Questions" to R.id.chipQuestions,
            "Starred" to R.id.chipStarred).forEach { (value, id) ->
            root.v<View>(id).setOnClickListener {
                libraryFilter = value
                updateLibraryFilterChips(root)
                if (librarySnapshot?.batchId == batchId()) loadLibrary(root)
            }
        }
        listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab,
            "other" to R.id.btnCategorySyllabus).forEach { (value, id) ->
            root.v<View>(id).setOnClickListener {
                libraryCategory = value
                updateLibrarySegment(root, true)
                if (librarySnapshot?.batchId == batchId()) loadLibrary(root)
            }
        }
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            librarySnapshot = null
            loadLibrary(root)
        }
        root.v<View>(R.id.libraryCategoryToggle).post { updateLibrarySegment(root, false) }
        updateLibraryFilterChips(root)
        loadLibrary(root)
    }

    private fun updateLibraryFilterChips(root: View) {
        listOf("All" to R.id.chipAll, "Notes" to R.id.chipNotes,
            "Slides" to R.id.chipSlides, "Questions" to R.id.chipQuestions,
            "Starred" to R.id.chipStarred).forEach { (name, id) ->
            root.v<TextView>(id).apply {
                setBackgroundResource(if (libraryFilter == name)
                    R.drawable.bg_library_chip_html_selected else R.drawable.bg_library_chip_html)
                setTextColor(activity.getColor(if (libraryFilter == name)
                    R.color.cm_text_inverse else R.color.cm_text_primary))
            }
        }
    }

    private fun updateLibrarySegment(root: View, animate: Boolean) {
        if (!root.isAttachedToWindow) return
        val track = root.v<View>(R.id.libraryCategoryToggle)
        val indicator = root.v<View>(R.id.libraryCategoryIndicator)
        val width = (track.width - track.paddingLeft - track.paddingRight) / 3
        if (width <= 0) return
        indicator.layoutParams = indicator.layoutParams.apply { this.width = width }
        val index = when (libraryCategory) { "lab" -> 1; "other" -> 2; else -> 0 }
        val target = (index * width).toFloat()
        indicator.animate().cancel()
        if (animate) indicator.animate().translationX(target).setDuration(180).start()
        else indicator.translationX = target
        listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab,
            "other" to R.id.btnCategorySyllabus).forEach { (value, id) ->
            root.v<TextView>(id).setTextColor(activity.getColor(
                if (libraryCategory == value) R.color.cm_text_inverse else R.color.cm_text_secondary))
        }
    }

    private fun loadLibrary(root: View): Unit = launch(root) {
        if (librarySnapshot == null && libraryLoadingRoot === root) return@launch
        if (librarySnapshot == null) libraryLoadingRoot = root
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
        try {
            val snapshot = librarySnapshot?.takeIf { it.batchId == selectedBatch } ?: run {
                val semesters = ClassMateAuthApi.rows("semesters",
                    "select=id&batch_id=eq.$selectedBatch&status=eq.active&limit=1")
                val semester = semesters.optJSONObject(0)?.optString("id")
                coroutineScope {
                    val offeringsJob = async { if (semester == null) emptyList() else rows(
                        ClassMateAuthApi.rows("semester_courses",
                            "select=id,course_id&semester_id=eq.$semester")) }
                    val coursesJob = async { rows(ClassMateCourses.catalog(selectedBatch))
                        .associateBy { it.getString("id") } }
                    val filesJob = async { rows(ClassMateAuthApi.rows("file_metadata",
                        "select=id,title,file_type,category,size_bytes,created_at,semester_course_id" +
                            "&batch_id=eq.$selectedBatch&status=eq.active&order=created_at.desc&limit=100")) }
                    val favoritesJob = async { rows(ClassMateAuthApi.rows("file_favorites",
                        "select=file_id&profile_id=eq.${profile().getString("id")}"))
                        .map { it.getString("file_id") }.toMutableSet() }
                    LibrarySnapshot(selectedBatch, offeringsJob.await(), coursesJob.await(),
                        filesJob.await(), favoritesJob.await()).also { librarySnapshot = it }
                }
            }
            val offerings = snapshot.offerings
            if (snapshot.offerings.isEmpty() && active(root) && root.getTag(R.id.tvLibrarySubtitle) != "setup_prompted" &&
                (profile().optString("role")=="admin" || profile().optBoolean("is_cr"))) {
                root.setTag(R.id.tvLibrarySubtitle,"setup_prompted")
                ClassMateCourses.prompt(activity,onConfigure)
            }
            val courses = snapshot.courses
            val files = snapshot.files
            val favoriteIds = snapshot.favoriteIds
            if (!active(root) || selectedBatch != batchId()) return@launch
            val selectedFilter = libraryFilter
            val selectedCategory = libraryCategory
            val query = root.v<EditText>(R.id.etLibrarySearch).text.toString().trim()
            val courseNames = offerings.associate { it.optString("id") to courses[it.optString("course_id")]?.optString("course_title").orEmpty() }
            val visibleFiles = files.filter { file ->
                (query.isBlank() || file.optString("title").contains(query, true) || courseNames[file.optString("semester_course_id")].orEmpty().contains(query, true)) && when (selectedFilter) {
                "All" -> true
                "Starred" -> file.optString("id") in favoriteIds
                else -> file.optString("category") == selectedFilter.lowercase()
            } }
            updateLibraryFilterChips(root)
            text(root, R.id.tvLibrarySubtitle,
                "${files.size} ${if (files.size == 1) "file" else "files"} this semester")
            root.v<View>(R.id.tvRecentEmpty).visibility = if (visibleFiles.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvRecent, R.layout.item_recent_pdf, if (query.isBlank()) visibleFiles.take(3) else visibleFiles) { card, file ->
                bindLibraryFile(root, card, file, favoriteIds)
            }
            val selected = offerings.mapNotNull { offering ->
                courses[offering.optString("course_id")]?.let { course -> offering to course }
            }.filter { it.second.optString("course_type") == selectedCategory && (query.isBlank() ||
                it.second.optString("course_title").contains(query, true) || it.second.optString("course_code").contains(query, true) ||
                visibleFiles.any { file -> file.optString("semester_course_id") == it.first.optString("id") }) }
            val items = selected.map { pair -> JSONObject(pair.second.toString())
                .put("offering_id", pair.first.getString("id")) }
            val syllabusFiles = visibleFiles.filter { it.optString("category") == "syllabus" }
            root.v<View>(R.id.tvNoSubjectResults).visibility = if (
                if (selectedCategory == "other") syllabusFiles.isEmpty() else items.isEmpty()
            ) View.VISIBLE else View.GONE
            text(root, R.id.tvLibrarySummary, if (selectedCategory == "other")
                "${syllabusFiles.size} files"
                else "${items.size} courses")
            listOf(R.id.rvRegular, R.id.rvLab, R.id.rvOther).forEach { root.v<View>(it).visibility = View.GONE }
            val listId = when (selectedCategory) {
                "lab" -> R.id.rvLab
                "other" -> R.id.rvOther
                else -> R.id.rvRegular
            }
            root.v<View>(listId).visibility = View.VISIBLE
            if (selectedCategory == "other") {
                recycler(root, listId, R.layout.item_recent_pdf,
                    syllabusFiles) { card, file ->
                    bindLibraryFile(root, card, file, favoriteIds)
                }
                return@launch
            }
            recycler(root, listId, R.layout.item_subject_card, items) { card, course ->
                card.v<TextView>(R.id.tvSubjectName).text = ClassMateNoticeText.styled(card.v(R.id.tvSubjectName), course.optString("course_title"), query)
                text(card, R.id.tvSubjectCode, course.optString("course_code"))
                val count = visibleFiles.count { it.optString("semester_course_id") == course.optString("offering_id") }
                text(card, R.id.tvPdfCount, if (count == 0) "No files" else "$count files")
                card.setOnClickListener {
                    onOpenCourse(course.optString("offering_id"), course.optString("course_title"),
                        profile().optString("role") in setOf("admin", "teacher") ||
                            profile().optBoolean("is_cr"))
                }
            }
        } finally { refresh.isRefreshing = false; if (libraryLoadingRoot === root) libraryLoadingRoot = null }
    }

    private fun bindLibraryFile(root: View, card: View, file: JSONObject, favorites: Set<String>) {
        val fileId = file.getString("id")
        card.v<TextView>(R.id.tvTitle).text = ClassMateNoticeText.styled(card.v(R.id.tvTitle), file.optString("title"), root.v<EditText>(R.id.etLibrarySearch).text.toString().trim())
        text(card, R.id.tvSubject, file.optString("category").replaceFirstChar { it.uppercase() })
        text(card, R.id.tvFileMeta, "${file.optLong("size_bytes") / 1024} KB")
        card.v<android.widget.ImageView>(R.id.btnFavorite).apply {
            setImageResource(if (fileId in favorites) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            setOnClickListener {
                launch(root) {
                    if (fileId in favorites) ClassMateAuthApi.delete("file_favorites",
                        "profile_id=eq.${profile().getString("id")}&file_id=eq.$fileId")
                    else ClassMateAuthApi.insert("file_favorites", JSONObject()
                        .put("profile_id", profile().getString("id")).put("file_id", fileId))
                    if (fileId in favorites) librarySnapshot?.favoriteIds?.remove(fileId)
                    else librarySnapshot?.favoriteIds?.add(fileId)
                    if (active(root)) loadLibrary(root)
                }
            }
        }
        val canDelete = profile().optString("role") in setOf("admin", "teacher") ||
            profile().optBoolean("is_cr")
        card.v<View>(R.id.btnDelete).apply {
            visibility = if (canDelete) View.VISIBLE else View.GONE
            contentDescription = "Manage file"
            (this as android.widget.ImageView).setImageResource(R.drawable.ic_more_vert)
            setOnClickListener {
                val canEdit = profile().optString("role") == "admin" || profile().optBoolean("is_cr")
                fun confirmDelete() {
                AlertDialog.Builder(activity).setTitle("Permanently delete ${file.optString("title")}?")
                    .setMessage("This removes the protected file, library entry, and linked notice. This cannot be undone.")
                    .setPositiveButton("Delete permanently") { _, _ -> launch(root) {
                        ClassMateAuthApi.deleteResource(fileId)
                        ClassMateAcademicCache.removeResourceNotice(activity, profile().optString("id"), batchId(), fileId)
                        noticeFeed = noticeFeed.filterNot { it.optString("resource_id") == fileId }
                        librarySnapshot?.let { snapshot ->
                            librarySnapshot = snapshot.copy(files = snapshot.files.filterNot {
                                it.optString("id") == fileId })
                        }
                        if (active(root)) loadLibrary(root)
                    } }.setNegativeButton("Cancel", null).show()
                }
                if (canEdit) com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Manage file").setItems(arrayOf("Edit file details", "Delete permanently")) { _, option ->
                        if(option == 0) onEditFile(file) else confirmDelete()
                    }.show()
                else confirmDelete()
            }
        }
        card.setOnClickListener { openFile(file) }
    }

    private fun searchLibrary(root: View) {
        val search = root.v<EditText>(R.id.etLibrarySearch)
        search.requestFocus()
        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    private fun showAllFiles() {
        onOpenCourse("", "Recently shared files", profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr"))
    }
    private fun chooseFile(files: List<JSONObject>) {
        if (files.isEmpty()) { Toast.makeText(activity, "No files", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(activity).setTitle("Files")
            .setItems(files.map { it.optString("title") }.toTypedArray()) { _, index -> openFile(files[index]) }
            .show()
    }
    private fun openFile(file: JSONObject) = launch(activity.window.decorView) {
        val url = ClassMateAuthApi.signedResource(file.getString("id"))
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun setupProfile(root: View) {
        fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
        val account = profile()
        text(root, R.id.tvAppVersion, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        root.v<View>(R.id.layoutCheckUpdates).setOnClickListener {
            activity.startActivity(Intent(activity, UpdateActionActivity::class.java)
                .setAction(UpdateActionActivity.ACTION_RETRY))
        }
        text(root, R.id.tvProfileName, account.optString("full_name").ifBlank { "ClassMate user" })
        text(root, R.id.tvRoleBadge, if (account.optString("role") == "admin") "ADMIN"
            else account.optString("role").uppercase())
        root.v<TextView>(R.id.tvRoleBadge).setBackgroundResource(R.drawable.bg_profile_role)
        text(root, R.id.tvUserSubInfo, account.optString("email"))
        text(root, R.id.tvProfileAcademic, listOf(account.optString("student_id").takeUnless { it.isBlank() || it == "null" }, batchLabel().takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "))
        if (account.optBoolean("is_cr")) text(root, R.id.tvRoleBadge, "CLASS REPRESENTATIVE")
        root.v<View>(R.id.btnSwitchBatch).apply {
            visibility = if (account.optString("role") in setOf("admin", "teacher")) View.VISIBLE else View.GONE
            setOnClickListener { onSwitchBatch() }
        }
        root.v<View>(R.id.layoutDeleteOfflineCache).setOnClickListener {
            AlertDialog.Builder(activity).setTitle("Clear offline cache")
                .setMessage("Remove temporary downloads from this device?")
                .setPositiveButton("Clear") { _, _ ->
                    ClassMateAcademicCache.clear(activity)
                    noticeFeed = emptyList(); noticeBatch = ""; noticeStates.clear(); noticeAuthors.clear(); noticeReadCounts.clear(); noticeReaderPreviews.clear()
                    activity.cacheDir.listFiles()?.forEach { file -> file.deleteRecursively() }
                    Toast.makeText(activity, "Offline cache cleared", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        }
        val photo = GoogleSignIn.getLastSignedInAccount(activity)?.photoUrl
        if (photo != null) com.bumptech.glide.Glide.with(activity).load(photo)
            .placeholder(R.drawable.ic_default_avatar).into(root.v(R.id.ivProfile))
        val prefs = AppPreferences(activity)
        root.v<SwitchCompat>(R.id.switchAutoUpdates).apply {
            isChecked = prefs.isAutoUpdateEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.setAutoUpdateEnabled(checked)
                UpdateCoordinator.schedule(activity)
                if (checked) UpdateCoordinator.enqueueForegroundCheck(activity)
            }
        }
        root.v<SwitchCompat>(R.id.switchWifiOnlyUpdates).apply {
            isChecked = prefs.isWifiOnlyUpdates()
            setOnCheckedChangeListener { _, checked ->
                prefs.setWifiOnlyUpdates(checked)
                UpdateCoordinator.schedule(activity)
                if (!checked) UpdateCoordinator.enqueueForegroundCheck(activity)
            }
        }
        root.v<SwitchCompat>(R.id.switchDarkMode).apply {
            isChecked = prefs.isDarkMode()
            setOnCheckedChangeListener { _, checked ->
                prefs.setDarkMode(checked)
                AppCompatDelegate.setDefaultNightMode(if (checked)
                    AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        root.v<SwitchCompat>(R.id.switchNotifications).apply {
            isChecked = prefs.isNotificationsEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.setNotificationsEnabled(checked)
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    scope.launch { runCatching {
                        if (checked) ClassMateAuthApi.registerDeviceToken(token)
                        else ClassMateAuthApi.unregisterDeviceToken(token)
                    } }
                }
            }
        }
        if(account.optString("role") in setOf("admin","teacher") || account.optBoolean("is_cr")) {
            val logout=root.v<View>(R.id.btnLogout)
            val parent=logout.parent as android.view.ViewGroup
            val manage=com.google.android.material.button.MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="Manage"; isAllCaps=false; cornerRadius=dp(16); setOnClickListener { onManage() }
            }
            parent.addView(manage,parent.indexOfChild(logout),android.widget.LinearLayout.LayoutParams(-1,dp(56)).apply { bottomMargin=dp(12) })
        }
        root.v<View>(R.id.btnLogout).setOnClickListener { onSignOut() }
        root.v<View>(R.id.cardPersonalInfo).setOnClickListener { onEditProfile() }
        root.v<View>(R.id.btnEditPersonal).setOnClickListener { onEditProfile() }
    }

    private fun showProfileDetails(root: View): Unit = launch(root) {
        val account = profile()
        val departmentId = account.optString("department_id")
        val department = if (departmentId.isBlank() || departmentId == "null") "Global"
            else ClassMateAuthApi.rows("departments", "select=name&id=eq.$departmentId")
                .optJSONObject(0)?.optString("name") ?: "Unknown"
        val assignedBatch = account.optString("batch_id")
        val batch = if (assignedBatch.isBlank() || assignedBatch == "null") "None"
            else ClassMateAuthApi.rows("batches",
                "select=batch_number,academic_session&id=eq.$assignedBatch")
                .optJSONObject(0)?.let {
                    "${it.optInt("batch_number")} · session ${it.optInt("academic_session")}" } ?: "Unknown"
        val semesters = if (batchId().isBlank()) JSONArray() else ClassMateAuthApi.rows("semesters",
            "select=semester_number&batch_id=eq.${batchId()}&status=eq.active&limit=1")
        if (!root.isAttachedToWindow) return@launch
        val details = listOf(
            "Email: ${account.optString("email")}",
            "Role: ${account.optString("role")}",
            "Department: $department",
            "Assigned batch: $batch",
            "Current semester: ${semesters.optJSONObject(0)?.optInt("semester_number") ?: "None"}",
            "CR: ${if (account.optBoolean("is_cr")) "Yes" else "No"}",
            "Verification: ${account.optString("verification_status")}",
        ).joinToString("\n")
        AlertDialog.Builder(activity).setTitle("Profile details").setMessage(details)
            .setPositiveButton("Close", null)
            .apply {
                if (account.optString("role") in setOf("admin", "teacher"))
                    setNeutralButton("Switch batch") { _, _ -> onSwitchBatch() }
            }.show()
    }
}
