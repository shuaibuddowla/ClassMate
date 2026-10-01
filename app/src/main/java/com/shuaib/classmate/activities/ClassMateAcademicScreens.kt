package com.shuaib.classmate.activities

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.shuaib.classmate.services.ClassMateAutoMuteScheduler
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
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.messaging.FirebaseMessaging
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Binds the original four ClassMate layouts to the Supabase Auth/RLS backend. */
internal class ClassMateAcademicScreens(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val batchId: () -> String,
    private val batchLabel: () -> String,
    private val profile: () -> JSONObject,
    private val onPostNotice: () -> Unit,
    private val onUpload: () -> Unit,
    private val onSignOut: () -> Unit,
    private val onSwitchBatch: () -> Unit,
    private val onAddPeriod: () -> Unit,
) {
    private var selectedDay = LocalDate.now().dayOfWeek.value % 7
    private var busMode = false
    private var libraryCategory = "theory"
    private var libraryFilter = "All"
    private val inflater get() = LayoutInflater.from(activity)
    private val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

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
        root.v<View>(R.id.shimmerView).visibility = View.GONE
        root.v<View>(R.id.btnAddPeriod).apply {
            visibility = if (profile().optString("role") == "admin") View.VISIBLE else View.GONE
            setOnClickListener { onAddPeriod() }
        }
        val selector = root.v<LinearLayout>(R.id.daySelector)
        selector.removeAllViews()
        days.forEachIndexed { index, day ->
            val card = inflater.inflate(R.layout.item_day_card, selector, false)
            text(card, R.id.tvDayShort, day.take(3))
            text(card, R.id.tvDayDate, LocalDate.now().minusDays(
                (LocalDate.now().dayOfWeek.value % 7 - index).toLong()).dayOfMonth.toString())
            if (index == selectedDay) {
                card.setBackgroundResource(R.drawable.bg_day_card_selected)
                card.v<View>(R.id.vDayIndicator).visibility = View.VISIBLE
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

    private fun loadTimetable(root: View) = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val day = selectedDay
        val selectedBatch = batchId()
        try {
            val entries: List<JSONObject>
            val names: Map<String, String>
            if (busMode) {
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
                val courses = rows(ClassMateAuthApi.rows("courses", "select=id,course_code,course_title"))
                    .associateBy { it.getString("id") }
                names = offerings.associate { offering ->
                    val course = courses[offering.getString("course_id")]
                    offering.getString("id") to (course?.optString("course_title") ?: "Course")
                }
                entries = if (courseIds.isEmpty()) emptyList() else rows(ClassMateAuthApi.rows(
                    "routine_slots", "select=id,semester_course_id,start_time,end_time,room,type" +
                        "&semester_course_id=in.(${courseIds.joinToString(",")})&day_of_week=eq.$day&order=start_time"))
            }
            if (!active(root) || day != selectedDay || selectedBatch != batchId()) return@launch
            root.v<View>(R.id.scheduleHeader).visibility = View.VISIBLE
            text(root, R.id.tvScheduleLabel, "${days[day].uppercase()}'S ${if (busMode) "BUS SCHEDULE" else "SCHEDULE"}")
            text(root, R.id.tvPeriodCount, "${entries.size} ${if (busMode) "buses" else "classes"}")
            root.v<View>(R.id.emptyState).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            root.v<View>(R.id.rvPeriods).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            if (busMode) recycler(root, R.id.rvPeriods, R.layout.item_bus_schedule, entries) { card, item ->
                text(card, R.id.tvBusName, item.optString("route_name"))
                text(card, R.id.tvRoute, "${item.optString("origin")} → ${item.optString("destination")}")
                text(card, R.id.tvDepartureTime, hour(item.optString("departure_time")))
            } else recycler(root, R.id.rvPeriods, R.layout.item_period, entries) { card, item ->
                text(card, R.id.tvSubject, names[item.optString("semester_course_id")] ?: "Class")
                text(card, R.id.tvStartTime, hour(item.optString("start_time")))
                text(card, R.id.tvEndTime, hour(item.optString("end_time")))
                text(card, R.id.tvTeacher, item.optString("room").ifBlank { "Class routine" })
                text(card, R.id.tvRoom, item.optString("room"))
                text(card, R.id.tvTypeBadge, item.optString("type").uppercase())
                val minutes = runCatching {
                    java.time.Duration.between(LocalTime.parse(item.getString("start_time")),
                        LocalTime.parse(item.getString("end_time"))).toMinutes()
                }.getOrDefault(0)
                text(card, R.id.tvDuration, "$minutes min")
            }
        } finally { refresh.isRefreshing = false }
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
            loadNotices(root)
        }
        root.v<EditText>(R.id.etNoticeSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { loadNotices(root) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadNotices(root) }
        loadNotices(root)
    }

    private fun loadNotices(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
        val search = root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()
        try {
            val visible = rows(ClassMateAuthApi.rows("notices",
                "select=id,title,body,published_at,semester_course_id,author_id" +
                    "&batch_id=eq.$selectedBatch&order=published_at.desc&limit=100"))
            val engagement = if (visible.isEmpty()) emptyMap() else rows(JSONArray(
                ClassMateAuthApi.rpcText("notice_engagement", JSONObject().put("target_ids",
                    JSONArray(visible.map { it.getString("id") }))))).associateBy { it.getString("notice_id") }
            if (!active(root) || selectedBatch != batchId() ||
                search != root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()) return@launch
            text(root, R.id.tvNoticeSubtitle, "${visible.size} updates · Pull to refresh")
            val filtered = visible.filter { search.isBlank() ||
                it.optString("title").contains(search, true) || it.optString("body").contains(search, true) }
                .sortedWith(compareByDescending<JSONObject> {
                    engagement[it.optString("id")]?.optBoolean("is_pinned") == true
                }.thenByDescending { it.optString("published_at") })
            root.v<View>(R.id.emptyNoticeState).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvNotices, R.layout.item_notice_modern, filtered) { card, item ->
                val noticeId = item.getString("id")
                val state = engagement[noticeId]
                text(card, R.id.tvTitle, item.optString("title"))
                text(card, R.id.tvPreview, item.optString("body"))
                text(card, R.id.tvSubject, if (item.isNull("semester_course_id")) "Announcement" else "Course")
                text(card, R.id.tvAuthorName, if (item.optString("author_id") == profile().optString("id"))
                    profile().optString("full_name") else "ClassMate")
                text(card, R.id.tvMeta, item.optString("published_at").replace('T', ' ').take(16))
                text(card, R.id.tvLikeCount, state?.optLong("like_count")?.toString() ?: "0")
                text(card, R.id.tvCommentCount, state?.optLong("comment_count")?.toString() ?: "0")
                card.v<android.widget.ImageView>(R.id.ivLikeIcon).setImageResource(
                    if (state?.optBoolean("is_liked") == true) R.drawable.ic_heart_filled
                    else R.drawable.ic_heart_outline)
                card.v<android.widget.ImageView>(R.id.btnPin).setImageResource(
                    if (state?.optBoolean("is_pinned") == true) R.drawable.ic_pin_filled
                    else R.drawable.ic_pin_outline)
                card.v<View>(R.id.btnLike).setOnClickListener {
                    setNoticeReaction(root, noticeId, "liked", state?.optBoolean("is_liked") != true)
                }
                card.v<View>(R.id.btnPin).setOnClickListener {
                    setNoticeReaction(root, noticeId, "pinned", state?.optBoolean("is_pinned") != true)
                }
                card.v<View>(R.id.btnComment).setOnClickListener { showNoticeComments(root, item) }
                listOf(R.id.btnOptions, R.id.btnReminder, R.id.imagePreviewContainer,
                    R.id.fileAttachmentContainer)
                    .forEach { card.findViewById<View>(it)?.visibility = View.GONE }
                card.v<View>(R.id.btnLike).visibility = View.VISIBLE
                card.v<View>(R.id.btnComment).visibility = View.VISIBLE
                card.v<View>(R.id.btnPin).visibility = View.VISIBLE
                card.v<View>(R.id.cardRoot).setOnClickListener {
                    AlertDialog.Builder(activity).setTitle(item.optString("title"))
                        .setMessage(item.optString("body")).setPositiveButton("Close", null).show()
                }
            }
        } finally { refresh.isRefreshing = false }
    }

    private fun setNoticeReaction(root: View, noticeId: String, field: String, enabled: Boolean): Unit =
        launch(root) {
            val profileId = profile().getString("id")
            val current = ClassMateAuthApi.rows("notice_reactions",
                "select=liked,pinned,reminder_at&profile_id=eq.$profileId&notice_id=eq.$noticeId")
                .optJSONObject(0)
            val row = JSONObject().put("profile_id", profileId).put("notice_id", noticeId)
                .put("liked", current?.optBoolean("liked") ?: false)
                .put("pinned", current?.optBoolean("pinned") ?: false)
            current?.optString("reminder_at")?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { row.put("reminder_at", it) }
            row.put(field, enabled)
            ClassMateAuthApi.upsert("notice_reactions", row)
            if (active(root)) loadNotices(root)
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
            root.v<View>(id).setOnClickListener { libraryFilter = value; loadLibrary(root) }
        }
        listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab,
            "other" to R.id.btnCategorySyllabus).forEach { (value, id) ->
            root.v<View>(id).setOnClickListener { libraryCategory = value; loadLibrary(root) }
        }
        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadLibrary(root) }
        loadLibrary(root)
    }

    private fun loadLibrary(root: View) = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        try {
            val semesters = ClassMateAuthApi.rows("semesters",
                "select=id&batch_id=eq.${batchId()}&status=eq.active&limit=1")
            val semester = semesters.optJSONObject(0)?.optString("id")
            val offerings = if (semester == null) emptyList() else rows(ClassMateAuthApi.rows(
                "semester_courses", "select=id,course_id&semester_id=eq.$semester"))
            val courses = rows(ClassMateAuthApi.rows("courses", "select=id,course_code,course_title,course_type"))
                .associateBy { it.getString("id") }
            val files = rows(ClassMateAuthApi.rows("file_metadata",
                "select=id,title,file_type,size_bytes,created_at,semester_course_id" +
                    "&batch_id=eq.${batchId()}&status=eq.active&order=created_at.desc&limit=100"))
            if (!active(root)) return@launch
            text(root, R.id.tvLibrarySubtitle, "${files.size} files this semester")
            root.v<View>(R.id.tvRecentEmpty).visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvRecent, R.layout.item_recent_pdf, files.take(3)) { card, file ->
                text(card, R.id.tvTitle, file.optString("title"))
                text(card, R.id.tvSubject, "Academic file")
                text(card, R.id.tvFileMeta, "${file.optLong("size_bytes") / 1024} KB")
                card.v<View>(R.id.btnFavorite).visibility = View.GONE
                card.setOnClickListener { openFile(file) }
            }
            val selected = offerings.mapNotNull { offering ->
                courses[offering.optString("course_id")]?.let { course -> offering to course }
            }.filter { it.second.optString("course_type") == libraryCategory }
            val items = selected.map { pair -> JSONObject(pair.second.toString())
                .put("offering_id", pair.first.getString("id")) }
            text(root, R.id.tvLibrarySummary, "${items.size} courses")
            listOf(R.id.rvRegular, R.id.rvLab, R.id.rvOther).forEach { root.v<View>(it).visibility = View.GONE }
            val listId = when (libraryCategory) {
                "lab" -> R.id.rvLab
                "other" -> R.id.rvOther
                else -> R.id.rvRegular
            }
            root.v<View>(listId).visibility = View.VISIBLE
            recycler(root, listId, R.layout.item_subject_card, items) { card, course ->
                text(card, R.id.tvSubjectName, course.optString("course_title"))
                text(card, R.id.tvSubjectCode, course.optString("course_code"))
                val count = files.count { it.optString("semester_course_id") == course.optString("offering_id") }
                text(card, R.id.tvPdfCount, if (count == 0) "No files" else "$count files")
                card.setOnClickListener {
                    val matching = files.filter { it.optString("semester_course_id") == course.optString("offering_id") }
                    if (matching.isEmpty()) Toast.makeText(activity, "No files yet", Toast.LENGTH_SHORT).show()
                    else chooseFile(matching)
                }
            }
        } finally { refresh.isRefreshing = false }
    }

    private fun searchLibrary(root: View) {
        val field = EditText(activity).apply { hint = "Search notes, slides, past questions" }
        AlertDialog.Builder(activity).setTitle("Search library").setView(field)
            .setPositiveButton("Search") { _, _ ->
                launch(root) {
                    val query = field.text.toString().trim()
                    val files = rows(ClassMateAuthApi.rows("file_metadata",
                        "select=id,title,size_bytes&batch_id=eq.${batchId()}&status=eq.active&limit=100"))
                        .filter { it.optString("title").contains(query, true) }
                    chooseFile(files)
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showAllFiles() = launch(activity.window.decorView) {
        chooseFile(rows(ClassMateAuthApi.rows("file_metadata",
            "select=id,title,size_bytes&batch_id=eq.${batchId()}&status=eq.active&limit=100")))
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
        val account = profile()
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
