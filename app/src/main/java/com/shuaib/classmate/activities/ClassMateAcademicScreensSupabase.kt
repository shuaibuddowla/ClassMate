package com.shuaib.classmate.activities

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.shuaib.classmate.services.ClassMateAutoMuteScheduler
import com.shuaib.classmate.services.ClassMateNoticeReminderScheduler
import java.time.Instant
import java.time.ZonedDateTime
import com.shuaib.classmate.services.ShakeToTorchService
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
    private val onSignOut: () -> Unit,
    private val onSwitchBatch: () -> Unit,
    private val onAddPeriod: (JSONObject?, Int) -> Unit,
    private val onOpenCourse: (String, String, Boolean) -> Unit,
) {
    private var selectedDay = LocalDate.now().dayOfWeek.value % 7
    private var busMode = false
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
    private val noticeReadSent = mutableSetOf<String>()
    private var noticeVisibleIds = emptyList<String>()
    private var noticeHeaderOffset = 0
    private val noticeBusy = mutableSetOf<String>()
    private val ownGoogleAvatar by lazy {
        GoogleSignIn.getLastSignedInAccount(activity)?.photoUrl?.toString()
            ?.takeIf { it.startsWith("https://") }
    }
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
    fun invalidateLibrary() { librarySnapshot = null }
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
            else -> R.layout.fragment_profile
        }
        val root = inflater.inflate(layout, host, false)
        host.addView(root, LinearLayout.LayoutParams(-1, -1))
        when (tab) {
            R.id.nav_timetable -> setupTimetable(root)
            R.id.nav_notices -> setupNotices(root)
            R.id.nav_pdf -> setupLibrary(root)
            else -> setupProfile(root)
        }
        navShown = true
        noticeHeaderCollapsed = false
        noticeScrollTravel = 0
        bindFloatingNavigation(tab, root)
    }

    private fun bindFloatingNavigation(tab: Int, root: View) {
        fun react(delta: Int, atTop: Boolean) {
            if (atTop || delta < -6) setNavigationShown(true)
            else if (delta > 6) setNavigationShown(false)
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
                                (noticeScrollTravel + dy).coerceIn(-40, 40) else dy
                            if (noticeScrollTravel >= 18) setNoticeHeaderCollapsed(root, true)
                            if (noticeScrollTravel <= -18) setNoticeHeaderCollapsed(root, false)
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
    }

    private fun setNoticeHeaderCollapsed(root: View, collapsed: Boolean) {
        if (noticeHeaderCollapsed == collapsed || !root.isAttachedToWindow) return
        noticeHeaderCollapsed = collapsed
        val title = root.v<TextView>(R.id.tvNoticeTitle)
        val density = activity.resources.displayMetrics.density
        title.pivotX = 0f
        title.pivotY = 0f
        title.animate().cancel()
        title.animate().scaleX(if (collapsed) 0.75f else 1f)
            .scaleY(if (collapsed) 0.75f else 1f)
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

    private fun setupTimetable(root: View) {
        val name = profile().optString("full_name").substringBefore(' ').ifBlank { "Student" }
        text(root, R.id.tvUserName, name)
        text(root, R.id.tvGreeting, when (LocalTime.now().hour) {
            in 5..11 -> "Good Morning,"
            in 12..16 -> "Good Afternoon,"
            in 17..20 -> "Good Evening,"
            else -> "Good Night,"
        })
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
            if (index == selectedDay) {
                card.setBackgroundResource(R.drawable.bg_day_card_selected)
                card.v<View>(R.id.vDayIndicator).visibility = View.VISIBLE
                card.v<TextView>(R.id.tvDayShort).setTextColor(android.graphics.Color.WHITE)
                card.v<TextView>(R.id.tvDayDate).setTextColor(android.graphics.Color.WHITE)
            }
            card.setOnClickListener { selectedDay = index; setupTimetable(root) }
            selector.addView(card)
        }
        val routine = root.v<TextView>(R.id.btnToggleRoutine)
        val bus = root.v<TextView>(R.id.btnToggleBus)
        fun updateToggle() {
            routine.setBackgroundResource(if (busMode) android.R.color.transparent else R.drawable.bg_toggle_item_selected)
            bus.setBackgroundResource(if (busMode) R.drawable.bg_toggle_item_selected else android.R.color.transparent)
            routine.setTextColor(activity.getColor(if (busMode) R.color.cm_text_secondary else android.R.color.white))
            bus.setTextColor(activity.getColor(if (busMode) android.R.color.white else R.color.cm_text_secondary))
            loadTimetable(root)
        }
        routine.setOnClickListener { busMode = false; updateToggle() }
        bus.setOnClickListener { busMode = true; updateToggle() }
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadTimetable(root) }
        updateToggle()
    }

    private fun loadTimetable(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val shimmer = root.v<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerView)
        val day = selectedDay
        val effectiveDate = selectedScheduleDate()
        val selectedBatch = batchId()
        val requestedBusMode = busMode
        val request = ++timetableRequest
        var completed = false
        shimmer.visibility = View.VISIBLE
        shimmer.startShimmer()
        root.v<View>(R.id.rvPeriods).visibility = View.GONE
        root.v<View>(R.id.emptyState).visibility = View.GONE
        root.v<View>(R.id.scheduleHeader).visibility = View.GONE
        try {
            val entries: List<JSONObject>
            val names: Map<String, String>
            var details = emptyMap<String, JSONObject>()
            var editableCourses = emptySet<String>()
            if (requestedBusMode) {
                names = emptyMap()
                entries = rows(ClassMateAuthApi.rows("bus_schedules",
                    "select=id,route_name,departure_time,origin,destination,weekdays&active=eq.true&order=departure_time"))
                    .filter { item ->
                        val weekdays = item.optJSONArray("weekdays")
                        weekdays == null || (0 until weekdays.length()).any { weekdays.optInt(it) == day }
                    }
            } else {
                val semesters = ClassMateAuthApi.rows("semesters",
                    "select=id&batch_id=eq.$selectedBatch&status=eq.active&limit=1")
                val semesterId = semesters.optJSONObject(0)?.optString("id")
                val offerings = if (semesterId == null) emptyList() else rows(
                    ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semesterId"))
                val courseIds = offerings.map { it.getString("id") }
                editableCourses = when {
                    profile().optString("role") == "admin" || profile().optBoolean("is_cr") ->
                        courseIds.toSet()
                    profile().optString("role") == "teacher" -> {
                        val assigned = ClassMateAuthApi.rows("teacher_course_assignments",
                            "select=semester_course_id&teacher_id=eq.${profile().optString("id")}&active=eq.true")
                        rows(assigned).map { it.getString("semester_course_id") }.toSet()
                    }
                    else -> emptySet()
                }
                val courses = rows(ClassMateAuthApi.rows("courses", "select=id,course_code,course_title"))
                    .associateBy { it.getString("id") }
                names = offerings.associate { offering ->
                    val course = courses[offering.getString("course_id")]
                    offering.getString("id") to (course?.optString("course_title") ?: "Course")
                }
                entries = if (courseIds.isEmpty()) emptyList() else rows(ClassMateAuthApi.rows(
                    "routine_slots", "select=id,semester_course_id,day_of_week,start_time,end_time,room,type" +
                        "&semester_course_id=in.(${courseIds.joinToString(",")})&day_of_week=eq.$day&order=start_time"))
                if (courseIds.isNotEmpty()) {
                    details = rows(JSONArray(ClassMateAuthApi.rpcText("timetable_details", JSONObject()
                        .put("target_batch", selectedBatch)
                        .put("target_date", effectiveDate.toString())
                        .put("target_course_ids", JSONArray(courseIds)))))
                        .associateBy { it.getString("semester_course_id") }
                }
            }
            if (!active(root) || request != timetableRequest || day != selectedDay ||
                requestedBusMode != busMode || selectedBatch != batchId()) return@launch
            root.v<View>(R.id.scheduleHeader).visibility = View.VISIBLE
            text(root, R.id.tvScheduleLabel, "${days[day].uppercase()}'S ${if (requestedBusMode) "BUS SCHEDULE" else "SCHEDULE"}")
            text(root, R.id.tvPeriodCount, "${entries.size} ${if (requestedBusMode) "buses" else "classes"}")
            root.v<View>(R.id.emptyState).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            root.v<View>(R.id.rvPeriods).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            if (requestedBusMode) recycler(root, R.id.rvPeriods, R.layout.item_bus_schedule, entries) { card, item ->
                text(card, R.id.tvBusName, item.optString("route_name"))
                text(card, R.id.tvRoute, "${item.optString("origin")} → ${item.optString("destination")}")
                text(card, R.id.tvDepartureTime, hour(item.optString("departure_time")))
            } else recycler(root, R.id.rvPeriods, R.layout.item_period, entries) { card, item ->
                val courseId = item.optString("semester_course_id")
                val detail = details[courseId]
                val cancelled = detail?.optBoolean("cancelled") == true
                text(card, R.id.tvSubject, names[courseId] ?: "Class")
                text(card, R.id.tvStartTime, hour(item.optString("start_time")))
                text(card, R.id.tvEndTime, hour(item.optString("end_time")))
                val teacherName = detail?.optString("teacher_name").orEmpty()
                text(card, R.id.tvTeacher, teacherName)
                card.v<View>(R.id.layoutTeacherInfo).visibility =
                    if (teacherName.isBlank()) View.GONE else View.VISIBLE
                text(card, R.id.tvRoom, item.optString("room"))
                card.v<View>(R.id.layoutRoomInfo).visibility =
                    if (item.optString("room").isBlank()) View.GONE else View.VISIBLE
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
                card.v<android.widget.ImageView>(R.id.ivSubjectIcon)
                    .setColorFilter(activity.getColor(R.color.cm_primary_light))
                if (item.optString("semester_course_id") in editableCourses) {
                    card.setOnLongClickListener { onAddPeriod(item, day); true }
                    card.setOnClickListener { onAddPeriod(item, day) }
                }
            }
            completed = true
        } finally {
            refresh.isRefreshing = false
            if (request == timetableRequest && root.isAttachedToWindow) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
                if (!completed) {
                    root.v<View>(R.id.emptyState).visibility = View.VISIBLE
                    text(root, R.id.tvNoClassesTitle, "Could not load schedule")
                    text(root, R.id.tvNoClassesSubtitle, "Pull to try again")
                }
            }
        }
    }

    private fun setupNotices(root: View) {
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
            root.v<View>(R.id.searchContainer).visibility = View.VISIBLE
            root.v<EditText>(R.id.etNoticeSearch).requestFocus()
        }
        root.v<View>(R.id.btnCloseSearch).setOnClickListener {
            root.v<EditText>(R.id.etNoticeSearch).text.clear()
            root.v<View>(R.id.searchContainer).visibility = View.GONE
            root.v<View>(R.id.headerTitleBlock).visibility = View.VISIBLE
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
        if (noticeBatch == batchId() && noticeFeed.isNotEmpty()) renderNoticeFeed(root)
        loadNotices(root)
    }

    private fun loadNotices(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
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
            renderNoticeFeed(root)
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            val (engagement, authors, reads) = if (visible.isEmpty())
                Triple(emptyList<JSONObject>(), emptyList(), emptyList())
            else coroutineScope {
                val ids = JSONArray(visible.map { it.getString("id") })
                val reactions = async { rows(JSONArray(ClassMateAuthApi.rpcText(
                    "notice_engagement", JSONObject().put("target_ids", ids)))) }
                val authorInfo = async { rows(JSONArray(ClassMateAuthApi.rpcText(
                    "notice_author_details", JSONObject().put("target_ids", ids)))) }
                val readInfo = async { rows(JSONArray(ClassMateAuthApi.rpcText(
                    "notice_read_counts", JSONObject().put("target_ids", ids)))) }
                Triple(reactions.await(), authorInfo.await(), readInfo.await())
            }
            if (!active(root) || request != noticeRequest || selectedBatch != batchId()) return@launch
            engagement.forEach { noticeStates[it.getString("notice_id")] = it }
            authors.forEach { noticeAuthors[it.getString("notice_id")] = it }
            reads.forEach {
                noticeReadCounts[it.getString("notice_id")] = it
                if (it.optBoolean("read_by_me")) noticeReadSent.add(it.getString("notice_id"))
            }
            renderNoticeFeed(root)
        } finally {
            refresh.isRefreshing = false
            if (request == noticeRequest && root.isAttachedToWindow) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
            }
        }
    }

    private fun renderNoticeFeed(root: View) {
        if (!root.isAttachedToWindow) return
        val search = root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()
        text(root, R.id.tvNoticeSubtitle, "${noticeFeed.size} updates · Pull to refresh")
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
                card.v<View>(R.id.noticeAccent).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(activity.getColor(accent))
                text(card, R.id.tvTitle, item.optString("title"))
                text(card, R.id.tvPreview, item.optString("body"))
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
                    AlertDialog.Builder(activity).setTitle(item.optString("title"))
                        .setMessage(item.optString("body")).setPositiveButton("Close", null).show()
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

    private fun seenLabel(count: Long) = "$count seen"

    private fun bindSeenAvatar(card: View, noticeId: String) {
        val showOwnPhoto = noticeReadCounts[noticeId]?.optBoolean("read_by_me") == true &&
            ownGoogleAvatar != null
        card.v<View>(R.id.seenAvatars).visibility = if (showOwnPhoto) View.VISIBLE else View.GONE
        if (showOwnPhoto) {
            com.bumptech.glide.Glide.with(card).load(ownGoogleAvatar)
                .into(card.v<android.widget.ImageView>(R.id.ivSeenMeAvatar))
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
                    val count = noticeReadCounts.getOrPut(id) { JSONObject() }
                    if (!count.optBoolean("read_by_me")) {
                        count.put("read_by_me", true)
                        count.put("read_count", count.optLong("read_count") + 1)
                    }
                }
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
        val count = noticeReadCounts[notice.optString("id")]?.optLong("read_count") ?: 0
        if (profile().optString("role") != "admin" &&
            notice.optString("author_id") != profile().optString("id")) {
            Toast.makeText(activity, "$count readers", Toast.LENGTH_SHORT).show()
            return
        }
        launch(root) {
            val readers = rows(JSONArray(ClassMateAuthApi.rpcText("notice_readers", JSONObject()
                .put("target_notice", notice.getString("id")))))
            if (!root.isAttachedToWindow) return@launch
            AlertDialog.Builder(activity).setTitle("Read by ${readers.size}")
                .setMessage(readers.joinToString("\n") {
                    "${it.optString("reader_name")} · ${it.optString("read_at").take(16).replace('T', ' ')}"
                }.ifBlank { "No one has read this notice yet" })
                .setPositiveButton("Close", null).show()
        }
    }

    private fun setNoticeReaction(root: View, card: View, noticeId: String) {
        if (!noticeBusy.add(noticeId)) return
        val state = noticeStates.getOrPut(noticeId) { JSONObject() }
        val ownKey = "is_liked"
        val wasEnabled = state.optBoolean(ownKey)
        val enabled = !wasEnabled
        val oldCount = state.optLong("like_count")
        state.put(ownKey, enabled)
        state.put("like_count", (oldCount + if (enabled) 1 else -1).coerceAtLeast(0))
        text(card, R.id.tvLikeCount, state.optLong("like_count").toString())
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
            }.onFailure {
                    state.put(ownKey, wasEnabled)
                    state.put("like_count", oldCount)
                    renderNoticeFeed(root)
                    Toast.makeText(activity, "Could not update notice", Toast.LENGTH_SHORT).show()
                }
            noticeBusy.remove(noticeId)
        }
    }

    private fun showNoticeActions(root: View, notice: JSONObject) {
        AlertDialog.Builder(activity).setTitle(notice.optString("title"))
            .setItems(arrayOf("Edit notice", "Delete notice")) { _, option ->
                if (option == 0) editNotice(root, notice) else deleteNotice(root, notice)
            }.show()
    }

    private fun editNotice(root: View, notice: JSONObject) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
        }
        val titleField = EditText(activity).apply {
            hint = "Title"
            setSingleLine(true)
            setText(notice.optString("title"))
        }
        val bodyField = EditText(activity).apply {
            hint = "Notice"
            minLines = 3
            setText(notice.optString("body"))
        }
        panel.addView(titleField)
        panel.addView(bodyField)
        AlertDialog.Builder(activity).setTitle("Edit notice").setView(panel)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = titleField.text.toString().trim()
                        if (title.isBlank()) {
                            titleField.error = "Enter a title"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        launch(root) {
                            val updated = ClassMateAuthApi.rpc("edit_notice", JSONObject()
                                .put("target_id", notice.getString("id"))
                                .put("target_title", title)
                                .put("target_body", bodyField.text.toString().trim()))
                            val index = noticeFeed.indexOfFirst { it.optString("id") == notice.optString("id") }
                            if (index >= 0) noticeFeed = noticeFeed.toMutableList().apply { set(index, updated) }
                            renderNoticeFeed(root)
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun deleteNotice(root: View, notice: JSONObject) {
        AlertDialog.Builder(activity).setTitle("Delete notice?")
            .setMessage("This notice and its comments will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                launch(root) {
                    val id = notice.getString("id")
                    ClassMateAuthApi.rpcText("delete_notice", JSONObject().put("target_id", id))
                    noticeFeed = noticeFeed.filterNot { it.optString("id") == id }
                    noticeStates.remove(id)
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
                    android.R.color.white else R.color.cm_text_primary))
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
                if (libraryCategory == value) R.color.cm_text_primary else R.color.cm_text_disabled))
        }
    }

    private fun loadLibrary(root: View): Unit = launch(root) {
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
                    val coursesJob = async { rows(ClassMateAuthApi.rows("courses",
                        "select=id,course_code,course_title,course_type"))
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
            val courses = snapshot.courses
            val files = snapshot.files
            val favoriteIds = snapshot.favoriteIds
            if (!active(root) || selectedBatch != batchId()) return@launch
            val selectedFilter = libraryFilter
            val selectedCategory = libraryCategory
            val visibleFiles = files.filter { file -> when (selectedFilter) {
                "All" -> true
                "Starred" -> file.optString("id") in favoriteIds
                else -> file.optString("category") == selectedFilter.lowercase()
            } }
            updateLibraryFilterChips(root)
            text(root, R.id.tvLibrarySubtitle,
                "${files.size} ${if (files.size == 1) "file" else "files"} this semester")
            root.v<View>(R.id.tvRecentEmpty).visibility = if (visibleFiles.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvRecent, R.layout.item_recent_pdf, visibleFiles.take(3)) { card, file ->
                bindLibraryFile(root, card, file, favoriteIds)
            }
            val selected = offerings.mapNotNull { offering ->
                courses[offering.optString("course_id")]?.let { course -> offering to course }
            }.filter { it.second.optString("course_type") == selectedCategory }
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
                text(card, R.id.tvSubjectName, course.optString("course_title"))
                text(card, R.id.tvSubjectCode, course.optString("course_code"))
                val count = visibleFiles.count { it.optString("semester_course_id") == course.optString("offering_id") }
                text(card, R.id.tvPdfCount, if (count == 0) "No files" else "$count files")
                card.setOnClickListener {
                    onOpenCourse(course.optString("offering_id"), course.optString("course_title"),
                        profile().optString("role") in setOf("admin", "teacher") ||
                            profile().optBoolean("is_cr"))
                }
            }
        } finally { refresh.isRefreshing = false }
    }

    private fun bindLibraryFile(root: View, card: View, file: JSONObject, favorites: Set<String>) {
        val fileId = file.getString("id")
        text(card, R.id.tvTitle, file.optString("title"))
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
            setOnClickListener {
                AlertDialog.Builder(activity).setTitle("Permanently delete ${file.optString("title")}?")
                    .setMessage("This removes the protected file, library entry, and linked notice. This cannot be undone.")
                    .setPositiveButton("Delete permanently") { _, _ -> launch(root) {
                        ClassMateAuthApi.deleteResource(fileId)
                        librarySnapshot?.let { snapshot ->
                            librarySnapshot = snapshot.copy(files = snapshot.files.filterNot {
                                it.optString("id") == fileId })
                        }
                        if (active(root)) loadLibrary(root)
                    } }.setNegativeButton("Cancel", null).show()
            }
        }
        card.setOnClickListener { openFile(file) }
    }

    private fun searchLibrary(root: View) {
        val field = EditText(activity).apply { hint = "Search notes, slides, past questions" }
        AlertDialog.Builder(activity).setTitle("Search library").setView(field)
            .setPositiveButton("Search") { _, _ ->
                val query = field.text.toString().trim()
                chooseFile(librarySnapshot?.files.orEmpty().filter {
                    it.optString("title").contains(query, true) })
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showAllFiles() = chooseFile(librarySnapshot?.files.orEmpty())
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
        val account = profile()
        text(root, R.id.tvAppVersion, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        root.v<View>(R.id.layoutCheckUpdates).setOnClickListener {
            activity.startActivity(Intent(activity, UpdateActionActivity::class.java)
                .setAction(UpdateActionActivity.ACTION_RETRY))
        }
        text(root, R.id.tvProfileName, account.optString("full_name").ifBlank { "ClassMate user" })
        text(root, R.id.tvRoleBadge, if (account.optString("role") == "admin") "ADMIN"
            else account.optString("role").uppercase())
        root.v<TextView>(R.id.tvRoleBadge).setBackgroundResource(R.drawable.bg_role_badge)
        text(root, R.id.tvUserSubInfo, listOf(account.optString("student_id").takeUnless { it == "null" },
            account.optString("email")).filterNotNull().joinToString(" · "))
        listOf(R.id.cardSeeFriends, R.id.savedResourcesTagSection, R.id.adminSection,
            R.id.fabEditPhoto, R.id.cardAiSettings, R.id.layoutAddWidget)
            .forEach { root.v<View>(it).visibility = View.GONE }
        root.v<View>(R.id.layoutDeleteOfflineCache).setOnClickListener {
            AlertDialog.Builder(activity).setTitle("Clear offline cache")
                .setMessage("Remove temporary downloads from this device?")
                .setPositiveButton("Clear") { _, _ ->
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
        root.v<SwitchCompat>(R.id.switchAutoMute).apply {
            isChecked = prefs.isAutoMuteEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.setAutoMuteEnabled(checked)
                if (checked) scope.launch { runCatching { ClassMateAutoMuteScheduler.schedule(activity, batchId()) }
                    .onFailure { Toast.makeText(activity, it.message ?: "Auto-mute setup failed", Toast.LENGTH_LONG).show() } }
                else ClassMateAutoMuteScheduler.cancel(activity)
            }
        }
        root.v<SwitchCompat>(R.id.switchShakeToTorch).apply {
            isChecked = prefs.isShakeToTorchEnabled()
            setOnCheckedChangeListener { _, checked ->
                if (checked && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    isChecked = false
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 4011)
                    return@setOnCheckedChangeListener
                }
                prefs.setShakeToTorchEnabled(checked)
                if (checked) ShakeToTorchService.start(activity) else ShakeToTorchService.stop(activity)
            }
        }
        root.v<View>(R.id.btnLogout).setOnClickListener { onSignOut() }
        root.v<View>(R.id.cardPersonalInfo).setOnClickListener { showProfileDetails(root) }
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

