package com.shuaib.classmate.activities







import android.content.ClipData



import android.content.ClipboardManager



import android.content.Context



import android.content.Intent



import androidx.browser.customtabs.CustomTabsIntent



import androidx.core.view.doOnAttach



import com.shuaib.classmate.services.ClassMateNoticeReminderScheduler



import java.time.Instant



import java.time.ZonedDateTime



import android.net.Uri



import android.view.LayoutInflater



import android.view.View



import android.view.ViewGroup



import android.widget.EditText



import android.widget.ImageView



import android.widget.LinearLayout



import android.widget.TextView



import android.widget.Toast



import com.google.android.material.button.MaterialButton



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



import com.shuaib.classmate.BuildConfig



import com.shuaib.classmate.update.UpdateActionActivity



import com.shuaib.classmate.update.UpdateCoordinator



import kotlinx.coroutines.CoroutineScope



import kotlinx.coroutines.Dispatchers



import kotlinx.coroutines.async



import kotlinx.coroutines.coroutineScope



import kotlinx.coroutines.launch



import kotlinx.coroutines.withContext



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



    private val onEnableNotifications: () -> Unit,



    private val onOpenAi: () -> Unit,



    private val onNoticeRead: () -> Unit = {},



) {



    private val appPrefs by lazy { AppPreferences(activity) }



    private var selectedDay = LocalDate.now().dayOfWeek.value % 7



    private var lastTimetableSetupDate = LocalDate.now()



    private var busMode = false



    private var calendarMode = false



    private var attendanceMode = false



    private val calendarData = ClassMateCalendarData(activity) { profile().optString("id") }



    private fun busDayKind() = calendarData.kindFor(selectedScheduleDate())



    private var timetableRequest = 0



    private var navShown = true



    private var noticeHeaderCollapsed = false



    private var noticeScrollTravel = 0



    private var noticeRequest = 0



    private var noticeSyncedAt = 0L



    private val noticeResourcePattern=Regex("(?i)\\bresource\\s*:")



    private val noticeCancellationPattern=Regex("(?i)\\b(cancelled|canceled|cancellation)\\b")



    private val noticeDateFormatter=DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())



    private var noticeBatch = ""



    private var noticeFeed = emptyList<JSONObject>()



    private val noticePageSize=10



    private var noticeHasMore=true



    private var noticeLoadingOlder=false



    private var noticeOlderError=false



    private var noticeOfflineTail=emptyList<JSONObject>()



    private val noticeStates = mutableMapOf<String, JSONObject>()



    private val noticeAuthors = mutableMapOf<String, JSONObject>()



    private val noticeReadCounts = mutableMapOf<String, JSONObject>()



    private val noticeReaderPreviews = mutableMapOf<String, List<JSONObject>>()



    private val noticeTranslations=mutableMapOf<String,JSONObject>()



    private val translatedNotices=mutableSetOf<String>()



    private val translatingNoticeIds = mutableSetOf<String>()



    private val expandedNoticeIds=mutableSetOf<String>()



    private val noticeReadSent = mutableSetOf<String>()



    private var noticeVisibleIds = emptyList<String>()



    private var noticeHeaderOffset = 0



    private val noticeBusy = mutableSetOf<String>()



    private val ownGoogleAvatar by lazy {



        GoogleSignIn.getLastSignedInAccount(activity)?.photoUrl?.toString()



            ?.takeIf { it.startsWith("https://") }



    }



    private var readerDialog: AlertDialog? = null



    private var liveOpenSessionsMap = mutableMapOf<String, JSONObject>()



    private var attendancePollerJob: kotlinx.coroutines.Job? = null



    private var attendanceEnabledCache: Pair<String, Boolean>? = null



    private var noticePollerJob: kotlinx.coroutines.Job? = null

    private fun startNoticePoller(root: View) {

        if (noticePollerJob?.isActive == true) return

        val currentBatch = batchId()

        noticePollerJob = scope.launch {

            while (active(root) && currentBatch == batchId()) {

                kotlinx.coroutines.delay(12000L)

                if (active(root) && currentBatch == batchId() && ClassMateAcademicCache.online(activity)) {

                    val refresh = root.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)

                    if (refresh?.isRefreshing != true) {

                        loadNotices(root, preserveOlder = true)

                    }

                }

            }

        }

    }

    private fun stopNoticePoller() {

        noticePollerJob?.cancel()

        noticePollerJob = null

    }



    private fun isAttendanceBatchAllowed(): Boolean {



        val currentBatch = batchId()



        if (currentBatch.isBlank()) return false



        val cached = attendanceEnabledCache



        if (cached != null && cached.first == currentBatch) {



            return cached.second



        }



        return currentBatch.equals("de0b3379-54a0-4275-a526-5970295c787e", ignoreCase = true)



    }



    private fun syncAttendanceFeatureStatus(root: View) {



        val currentBatch = batchId()



        if (currentBatch.isBlank()) return



        scope.launch {



            try {



                val res = ClassMateAuthApi.rpcText("is_attendance_feature_enabled", JSONObject().put("target_batch", currentBatch)).trim()



                val enabled = res.equals("true", ignoreCase = true)



                attendanceEnabledCache = Pair(currentBatch, enabled)



            } catch (_: Exception) {}



        }



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



    private var libraryLoadingRoot: View? = null



    fun invalidateLibrary() { librarySnapshot = null }



    fun invalidateNotices() { noticeSyncedAt=0L; noticeTranslations.clear(); translatedNotices.clear(); expandedNoticeIds.clear(); noticeRequest++; noticeFeed=emptyList(); noticeBatch=""; noticeHasMore=true; noticeLoadingOlder=false; noticeOfflineTail=emptyList() }



    fun onNoticeCreated(createdNotice: JSONObject) {



        val id = createdNotice.optString("id")



        if (id.isBlank()) return



        noticeFeed = (listOf(createdNotice) + noticeFeed).distinctBy { it.optString("id") }



        saveNoticeCache()



        tabRoots[R.id.nav_notices]?.let { root ->



            if (active(root)) {



                renderNoticeFeed(root)



                loadNotices(root, preserveOlder = true)



            }



        }



    }



    private val inflater get() = LayoutInflater.from(activity)



    // One instance per signed-in batch. Retain only the four academic roots.



    private val tabRoots = mutableMapOf<Int, View>()



    private val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")



    private fun selectedScheduleDate(): LocalDate {



        val today = LocalDate.now()



        val todayIndex = today.dayOfWeek.value % 7



        return today.plusDays(((selectedDay - todayIndex + 7) % 7).toLong())



    }







    fun onResume() {



        tabRoots[R.id.nav_timetable]?.let { root ->



            if (LocalDate.now() != lastTimetableSetupDate) {



                lastTimetableSetupDate = LocalDate.now()



                selectedDay = LocalDate.now().dayOfWeek.value % 7



                if (root.isAttachedToWindow) {



                    setupTimetable(root)



                }



            } else if (root.isAttachedToWindow && !busMode && !calendarMode) {



                scope.launch {



                    checkActiveAttendanceSessions(root, batchId())



                }



            }



        }



        tabRoots[R.id.nav_notices]?.let { root ->



            if (root.isAttachedToWindow) {



                startNoticePoller(root)



                if (ClassMateAcademicCache.online(activity)) {



                    loadNotices(root, preserveOlder = true)



                }



            }



        }



    }







    fun render(tab: Int, host: LinearLayout) {



        tabRoots[tab]?.let { root ->



            navShown=true



            (root.parent as? ViewGroup)?.removeView(root)



            host.addView(root,LinearLayout.LayoutParams(-1,-1))



            root.doOnAttach {



                if (tab == R.id.nav_transit) {
                    resumeTransitWebView()
                } else {
                    pauseTransitWebView()
                }

                when(tab) {



                    R.id.nav_timetable -> {



                        stopNoticePoller()



                        if (LocalDate.now() != lastTimetableSetupDate) {



                            lastTimetableSetupDate = LocalDate.now()



                            selectedDay = LocalDate.now().dayOfWeek.value % 7



                            setupTimetable(root)



                        } else if(calendarMode) {



                            (root.getTag(R.id.btnToggleCalendar) as? ClassMateCalendarUi)?.show()



                        } else {



                            loadTimetable(root, force = false)



                        }



                    }



                    R.id.nav_notices -> {



                        startNoticePoller(root)



                        if(noticeFeed.isEmpty() || noticeBatch!=batchId() || android.os.SystemClock.elapsedRealtime()-noticeSyncedAt>10_000) loadNotices(root,preserveOlder=true)



                    }



                    R.id.nav_pdf -> {



                        stopNoticePoller()



                        loadLibrary(root)



                    }



                    R.id.nav_transit -> {



                        stopNoticePoller()



                    }



                    else -> {



                        stopNoticePoller()



                        setupProfile(root)



                        refreshProfile(root, isPullToRefresh = false)



                    }



                }



            }



            return



        }



        val layout = when (tab) {



            R.id.nav_timetable -> R.layout.fragment_timetable



            R.id.nav_notices -> R.layout.fragment_notice



            R.id.nav_pdf -> R.layout.fragment_pdf_library



            R.id.nav_transit -> R.layout.fragment_transit



            else -> R.layout.classmate_profile



        }



        val root = inflater.inflate(layout, host, false)



        tabRoots[tab]=root



        host.addView(root, LinearLayout.LayoutParams(-1, -1))



        navShown = true



        if(tab==R.id.nav_notices) { noticeHeaderCollapsed = false; noticeScrollTravel = 0 }



        // Cold offline loads contain no suspending network call. Wait for attachment



        // so visibility guards do not discard the cached result before the first draw.



        root.doOnAttach {



            when (tab) {



                R.id.nav_timetable -> setupTimetable(root)



                R.id.nav_notices -> setupNotices(root)



                R.id.nav_pdf -> setupLibrary(root)



                R.id.nav_transit -> setupTransit(root)



                else -> {



                    setupProfile(root)



                    refreshProfile(root, isPullToRefresh = false)



                }



            }



            bindFloatingNavigation(tab, root)



        }



    }







    private fun bindFloatingNavigation(tab: Int, root: View) {



        var navigationTravel = 0



        val threshold = (16 * activity.resources.displayMetrics.density).toInt()



        fun react(delta: Int, atTop: Boolean) {



            if (atTop || delta < -2) {



                navigationTravel = 0



                setNavigationShown(true)



                return



            }



            if (delta <= 0) return



            navigationTravel = (navigationTravel + delta).coerceIn(0, threshold * 3)



            if (navigationTravel >= (threshold * 2)) setNavigationShown(false)



        }



        when (tab) {



            R.id.nav_notices -> root.v<RecyclerView>(R.id.rvNotices)



                .addOnScrollListener(object : RecyclerView.OnScrollListener() {



                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {



                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {



                            markVisibleNoticesRead(root, recyclerView)



                            setNavigationShown(true)



                        }



                    }



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



                        val last=(recyclerView.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: -1



                        if(dy>0 && recyclerView.scrollState!=RecyclerView.SCROLL_STATE_IDLE && last>=noticeHeaderOffset+noticeVisibleIds.size-3) loadOlderNotices(root)



                    }



                })



            R.id.nav_timetable, R.id.nav_pdf -> root.v<androidx.core.widget.NestedScrollView>(



                R.id.nestedScrollView).setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView,



                    _: Int, scrollY: Int, _: Int, oldY: Int -> react(scrollY - oldY, scrollY == 0) }



            R.id.nav_transit -> {



                setNavigationShown(true)



            }



            R.id.nav_profile -> {



                setNavigationShown(true)



                root.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollProfile)?.setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView,



                    _: Int, scrollY: Int, _: Int, oldY: Int -> react(scrollY - oldY, scrollY == 0) }



            }



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







    internal fun setNavigationShown(show: Boolean) {



        if (show) {



            navShown = true



            (activity as? ClassMateAuthActivity)?.composeNavVisible = true



            val nav = activity.findViewById<View>(R.id.classmate_home_nav) ?: return



            nav.animate().cancel()



            nav.animate().translationY(0f).alpha(1f).setDuration(180).start()



            return



        }



        if (navShown == show) return



        navShown = show



        (activity as? ClassMateAuthActivity)?.composeNavVisible = show



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



    private fun parseLocalTime(value: String?): LocalTime? {



        if (value.isNullOrBlank()) return null



        return runCatching {



            val trimmed = value.trim()



            val parts = trimmed.split(":")



            if (parts.size >= 2) {



                val h = parts[0].toIntOrNull() ?: return null



                val m = parts[1].take(2).toIntOrNull() ?: return null



                LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59))



            } else {



                LocalTime.parse(trimmed.take(8))



            }



        }.getOrNull()



    }



    private fun hour(value: String): String = parseLocalTime(value)?.format(DateTimeFormatter.ofPattern("hh:mm a")) ?: value.take(5)







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

            setHasFixedSize(true)
            setItemViewCacheSize(15)

            if (layoutManager == null) {
                layoutManager = LinearLayoutManager(activity)
            }



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



                setStroke((activity.resources.displayMetrics.density).toInt().coerceAtLeast(1), activity.getColor(R.color.cm_border_glass))



            }



            card.v<TextView>(R.id.tvDayShort).setTextColor(activity.getColor(if (selected) android.R.color.white else if (closure != null) R.color.cm_notice_cancel_text else R.color.cm_text_disabled))



            card.v<TextView>(R.id.tvDayDate).setTextColor(activity.getColor(if (selected) android.R.color.white else if (closure != null) R.color.cm_notice_cancel_text else R.color.cm_text_primary))



            card.v<View>(R.id.vDayIndicator).apply {



                visibility = if (selected || closure != null) View.VISIBLE else View.INVISIBLE



                backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(if (closure != null && !selected) R.color.cm_error else android.R.color.white))



            }



            card.contentDescription = "${days[date.dayOfWeek.value % 7]}, $date${closure?.let { ", $it. No classes" }.orEmpty()}"



        }



    }







    @android.annotation.SuppressLint("SetJavaScriptEnabled")

    private fun setupTransit(root: View) {

        val webView = root.findViewById<android.webkit.WebView>(R.id.transit_webview) ?: return

        val loadingView = root.findViewById<View>(R.id.transit_loading_view)



        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            databaseEnabled = true

            setSupportZoom(false)

            builtInZoomControls = false

            displayZoomControls = false

            loadsImagesAutomatically = true

            allowFileAccess = true

            allowContentAccess = true

            setGeolocationEnabled(true)

            mediaPlaybackRequiresUserGesture = false

            cacheMode = if (ClassMateAcademicCache.online(activity)) android.webkit.WebSettings.LOAD_DEFAULT else android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK

            userAgentString = "${userAgentString} ClassMate-Android"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                offscreenPreRaster = true
            }
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))



        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {

            override fun handleOnBackPressed() {

                webView.evaluateJavascript("if (window.__classmateTransitGoBack) { window.__classmateTransitGoBack(); } else if (window.history.length > 1) { window.history.back(); }", null)

            }

        }

        activity.onBackPressedDispatcher.addCallback(activity, backCallback)



        val bridge = com.shuaib.classmate.transit.ClassMateTransitBridge(activity)

        bridge.onBackStateChanged = { canGoBack ->

            activity.runOnUiThread {

                backCallback.isEnabled = canGoBack

            }

        }

        webView.addJavascriptInterface(bridge, "ClassMateNativeBridge")



        webView.webChromeClient = object : android.webkit.WebChromeClient() {

            override fun onGeolocationPermissionsShowPrompt(

                origin: String?,

                callback: android.webkit.GeolocationPermissions.Callback?

            ) {

                callback?.invoke(origin, true, false)

            }

            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 70 && loadingView?.visibility == View.VISIBLE) {
                    loadingView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                        loadingView?.visibility = View.GONE
                    }?.start()
                }
            }
        }



        webView.webViewClient = object : android.webkit.WebViewClient() {

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {

                super.onPageFinished(view, url)

                loadingView?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {

                    loadingView.visibility = View.GONE

                }?.start()

            }



            override fun onReceivedError(

                view: android.webkit.WebView?,

                request: android.webkit.WebResourceRequest?,

                error: android.webkit.WebResourceError?

            ) {

                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {

                    view?.loadUrl("file:///android_asset/transit/index.html")

                }

            }

        }



        val transitUrl = "https://classmatebd.vercel.app/transit-preview?app=android&view=map"

        webView.loadUrl(transitUrl)

    }



    private fun setupTimetable(root: View) {



        lastTimetableSetupDate = LocalDate.now()



        val name = profile().optString("full_name").substringBefore(' ').ifBlank { "Student" }



        text(root, R.id.tvUserName, name)



        val hour = LocalTime.now().hour



        val greetings = when (hour) {



            in 0..4 -> listOf("Your campus, even after hours.")



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



            card.setOnClickListener {



                if (selectedDay != index) {



                    com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



                    selectedDay = index



                    updateDayPills(root)



                    loadTimetable(root)



                }



            }



            selector.addView(card)



        }



        updateDayPills(root)



        val routine = root.v<TextView>(R.id.btnToggleRoutine)



        val bus = root.v<TextView>(R.id.btnToggleBus)



        val calendar = root.v<TextView>(R.id.btnToggleCalendar)



        val calendarUi = (root.getTag(R.id.btnToggleCalendar) as? ClassMateCalendarUi)



            ?: ClassMateCalendarUi(activity, scope, calendarData) { profile().optString("role") == "admin" }.also { ui ->



                root.setTag(R.id.btnToggleCalendar, ui)



                (root as LinearLayout).addView(ui.scroll, LinearLayout.LayoutParams(-1, 0, 1f))



            }



        fun updateToggle() {



            val special = calendarMode || busMode



            text(root, R.id.tvUserName, if (calendarMode) "Academic calendar" else if (busMode) "Campus buses" else name)



            text(root, R.id.tvGreeting, if (calendarMode) "Your university, month by month" else if (busMode) "Student transport" else greetings[LocalDate.now().dayOfYear % greetings.size])



            root.v<View>(R.id.tvGreetingWave).visibility = if (special) View.GONE else View.VISIBLE



            root.v<View>(R.id.busSummary).visibility = View.GONE



            root.v<View>(R.id.daySelector).visibility = if (calendarMode) View.GONE else View.VISIBLE



            root.v<View>(R.id.swipeRefresh).visibility = if (calendarMode) View.GONE else View.VISIBLE



            calendarUi.scroll.visibility = if (calendarMode) View.VISIBLE else View.GONE



            val isRoutine = !busMode && !calendarMode



            routine.setBackgroundResource(if (isRoutine) R.drawable.bg_toggle_item_selected else android.R.color.transparent)



            routine.setTextColor(activity.getColor(if (isRoutine) android.R.color.white else R.color.cm_text_secondary))



            bus.setBackgroundResource(if (busMode && !calendarMode) R.drawable.bg_toggle_item_selected else android.R.color.transparent)



            bus.setTextColor(activity.getColor(if (busMode && !calendarMode) android.R.color.white else R.color.cm_text_secondary))



            calendar.setBackgroundResource(if (calendarMode) R.drawable.bg_toggle_item_selected else android.R.color.transparent)



            calendar.setTextColor(activity.getColor(if (calendarMode) android.R.color.white else R.color.cm_text_secondary))



            val timetable = com.shuaib.classmate.databinding.FragmentTimetableBinding.bind(root)



            timetable.tvAddPeriod.text = if (busMode) "Add Bus" else "Add Period"



            timetable.btnAddPeriod.apply {



                val canEdit = if (busMode) canEditBus()



                    else profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr")



                visibility = if (canEdit && !calendarMode) View.VISIBLE else View.GONE



                setOnClickListener { if (busMode) onAddBus(null,busDayKind()) else onAddPeriod(null, selectedDay) }



            }



            if (calendarMode) { stopAttendanceSessionsPoller(); timetableRequest++; calendarUi.show() }



            else loadTimetable(root)



        }



        calendarUi.onOpenTimetable = { targetDate ->



            calendarMode = false



            busMode = false



            selectedDay = targetDate.dayOfWeek.value % 7



            updateToggle()



        }



        routine.setOnClickListener {



            if (busMode || calendarMode) com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



            calendarUi.selectedDate()?.let { d -> selectedDay = d.dayOfWeek.value % 7 }



            busMode = false



            calendarMode = false



            updateToggle()



        }



        bus.setOnClickListener { if(!busMode || calendarMode) com.shuaib.classmate.ui.ClassMateHaptics.selection(it); stopAttendanceSessionsPoller(); busMode = true; calendarMode = false; updateToggle() }



        calendar.setOnClickListener { if(!calendarMode) com.shuaib.classmate.ui.ClassMateHaptics.selection(it); stopAttendanceSessionsPoller(); calendarMode = true; busMode = false; updateToggle() }



        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadTimetable(root, true) }



        updateToggle()



    }







    private fun stopAttendanceSessionsPoller() {



        attendancePollerJob?.cancel()



        attendancePollerJob = null



    }







    private suspend fun checkActiveAttendanceSessions(root: View, batch: String) {



        runCatching {



            val rpcRes = ClassMateAuthApi.rpcText("active_attendance_sessions", JSONObject().put("target_batch", batch))



            val arr = JSONArray(rpcRes)



            val latestMap = mutableMapOf<String, JSONObject>()



            for (i in 0 until arr.length()) {



                val o = arr.getJSONObject(i)



                val slotId = o.optString("routine_slot_id")



                if (slotId.isNotBlank()) {



                    latestMap[slotId] = JSONObject().apply {



                        put("id", o.optString("session_id"))



                        put("routine_slot_id", slotId)



                        put("semester_course_id", o.optString("semester_course_id"))



                        put("status", o.optString("status"))



                        put("present_count", o.optInt("present_count", 0))



                    }



                }



            }



            val hasChanged = latestMap.keys != liveOpenSessionsMap.keys ||



                latestMap.values.any { cur ->



                    val prev = liveOpenSessionsMap[cur.optString("routine_slot_id")]



                    prev?.optString("id") != cur.optString("id") || prev?.optString("status") != cur.optString("status")



                }



            if (hasChanged) {



                withContext(Dispatchers.Main) {



                    liveOpenSessionsMap.clear()



                    liveOpenSessionsMap.putAll(latestMap)



                    if (active(root) && !busMode && !calendarMode && !attendanceMode) {



                        root.findViewById<RecyclerView>(R.id.rvPeriods)?.adapter?.notifyDataSetChanged()



                    }



                }



            }



        }



    }







    private fun startAttendanceSessionsPoller(root: View, batch: String) {



        if (attendancePollerJob?.isActive == true) return



        attendancePollerJob = scope.launch {



            // Immediate check upon starting poller



            checkActiveAttendanceSessions(root, batch)



            while (active(root) && batch == batchId() && !busMode && !calendarMode && !attendanceMode) {



                kotlinx.coroutines.delay(6000L)



                checkActiveAttendanceSessions(root, batch)



            }



        }



    }







    private fun canEditBus(): Boolean {



        val account = profile()



        return account.optString("role") == "admin" || (account.optBoolean("is_cr") &&



            account.optString("verification_status") == "active" &&



            (account.isNull("cr_valid_until") || runCatching {



                Instant.parse(account.optString("cr_valid_until")).isAfter(Instant.now())



            }.getOrDefault(false)))



    }







    private fun updateBusSummary(root: View, entries: List<JSONObject>, date: LocalDate, kind: String) {



        val today = LocalDate.now(); val now = LocalTime.now().withSecond(0).withNano(0)



        fun times(key: String) = entries.mapNotNull { runCatching { LocalTime.parse(it.optString(key).take(8)) }.getOrNull() }



        val campusTimes=times("departure_time"); val cityTimes=times("city_departure_time")



        val campusNext=ClassMateBusPresentation.next(campusTimes,date,today,now)



        val cityNext=ClassMateBusPresentation.next(cityTimes,date,today,now)



        val past=date.isBefore(today)



        val finished=date==today && campusNext==null && cityNext==null && (campusTimes.isNotEmpty() || cityTimes.isNotEmpty())



        val empty=campusTimes.isEmpty() && cityTimes.isEmpty()



        val compact=past || finished || empty



        root.v<View>(R.id.busSummaryTimes).visibility=if(compact) View.GONE else View.VISIBLE



        root.v<View>(R.id.tvBusSummaryStatus).visibility=if(compact) View.VISIBLE else View.GONE



        text(root,R.id.tvBusSummaryStatus,when { past -> "View the selected day’s timetable below."; finished -> "All departures for today are complete."; else -> "No departure times are listed for this day." })



        text(root, R.id.tvBusSummaryTitle, when { past -> "Past schedule"; finished -> "Service has ended"; empty -> "No departures listed"; date==today -> "Next departures"; else -> "First departures · ${date.format(DateTimeFormatter.ofPattern("d MMM"))}" })



        fun departure(time: LocalTime?, available: Boolean, timeId: Int, hintId: Int) {



            text(root,timeId,time?.let { hour(it.toString()) } ?: if(available) "No more buses" else "Not listed")



            root.v<TextView>(timeId).textSize=if(time==null) 16f else 24f



            val hint=when {



                time==null -> if(available) "For this day" else "No scheduled time"



                date!=today -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))



                else -> {



                    val minutes=java.time.temporal.ChronoUnit.MINUTES.between(now,time)



                    if(minutes==0L) "Departs now" else if(minutes<60) "In $minutes min" else "In ${minutes/60}h ${minutes%60}m"



                }



            }



            text(root,hintId,hint)



        }



        departure(campusNext,campusTimes.isNotEmpty(),R.id.tvNextCampus,R.id.tvNextCampusHint)



        departure(cityNext,cityTimes.isNotEmpty(),R.id.tvNextCity,R.id.tvNextCityHint)



        text(root, R.id.tvBusService, if (kind == "closed") "HOLIDAY SERVICE" else "REGULAR SERVICE")



    }







    private fun loadTimetable(root: View, force: Boolean = false): Unit = launch(root) {



        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)



        if (LocalDate.now() != lastTimetableSetupDate) {



            lastTimetableSetupDate = LocalDate.now()



            selectedDay = LocalDate.now().dayOfWeek.value % 7



            setupTimetable(root)



            return@launch



        }



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



            bindHolidayLottie(root, closure, effectiveDate)



            return@launch



        }



        text(root, R.id.tvNoClassesTitle, if(!requestedBusMode && profile().optString("role")=="teacher") {



            if(effectiveDate==LocalDate.now()) "You have no classes to take today" else "You have no classes to take on this day"



        } else "No classes scheduled")



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



                    val selectedCatalog=rows(JSONArray(ClassMateAuthApi.rpcText("batch_timetable_catalog", JSONObject().put("target_batch", selectedBatch))))



                    val visibleIds=selectedCatalog.map {it.optString("offering_id")}.toSet()



                    val offerings = (if (semester == null) emptyList() else rows(ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester"))).filter {profile().optString("role")!="teacher" || it.optString("id") in visibleIds}



                    val ids = offerings.map { it.getString("id") }



                    val bundle = coroutineScope {



                        val courses = async { selectedCatalog.associateBy { it.getString("id") } }



                        val routines = async { if (ids.isEmpty()) JSONArray() else ClassMateAuthApi.rows("routine_slots", "select=id,semester_course_id,day_of_week,start_time,end_time,room,type&semester_course_id=in.(${ids.joinToString(",")})&order=start_time") }



                        val teachers = async { if (ids.isEmpty()) JSONArray() else JSONArray(ClassMateAuthApi.rpcText("timetable_details", JSONObject().put("target_batch", selectedBatch).put("target_date", LocalDate.now().toString()).put("target_course_ids", JSONArray(ids)))) }



                        val changes = async { JSONArray(ClassMateAuthApi.rpcText("timetable_cancellations",JSONObject().put("target_batch",selectedBatch).put("target_start",LocalDate.now().toString()).put("target_end",LocalDate.now().plusDays(7).toString()))) }



                        val assigned = async { when {



                            profile().optString("role") == "admin" || profile().optBoolean("is_cr") -> ids



                            profile().optString("role") == "teacher" -> rows(ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.$accountId&active=eq.true")).map { it.getString("semester_course_id") }



                            else -> emptyList()



                        } }



                        val openAttendance = async {



                            runCatching {



                                val rpcRes = ClassMateAuthApi.rpcText("active_attendance_sessions", JSONObject().put("target_batch", selectedBatch))



                                val arr = JSONArray(rpcRes)



                                val res = JSONArray()



                                for (i in 0 until arr.length()) {



                                    val o = arr.getJSONObject(i)



                                    res.put(JSONObject().apply {



                                        put("id", o.optString("session_id"))



                                        put("routine_slot_id", o.optString("routine_slot_id"))



                                        put("semester_course_id", o.optString("semester_course_id"))



                                        put("status", o.optString("status"))



                                        put("present_count", o.optInt("present_count", 0))



                                    })



                                }



                                res



                            }.getOrElse {



                                runCatching { ClassMateAuthApi.rows("attendance_sessions", "status=eq.open") }.getOrDefault(JSONArray())



                            }



                        }



                        val names = JSONObject(); val codes = JSONObject(); val catalog = courses.await()



                        offerings.forEach {



                            val cid = it.getString("course_id")



                            val cat = catalog[cid]



                            names.put(it.getString("id"), cat?.optString("course_title") ?: "Course")



                            codes.put(it.getString("id"), cat?.optString("course_code") ?: "")



                        }



                        JSONObject().put("entries", routines.await()).put("names", names).put("codes", codes).put("details", teachers.await()).put("changes", changes.await()).put("editable", JSONArray(assigned.await())).put("attendance", openAttendance.await()).put("saved_at", System.currentTimeMillis())



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



            val codeJson = data.optJSONObject("codes") ?: JSONObject()



            val codes = codeJson.keys().asSequence().associateWith { codeJson.optString(it) }



            val cancellations = rows(data.optJSONArray("changes") ?: JSONArray()).filter { it.optString("effective_date") == effectiveDate.toString() }.map { it.optString("semester_course_id") }.toSet()



            val details = rows(data.optJSONArray("details") ?: JSONArray()).associate { detail ->



                detail.getString("semester_course_id") to JSONObject(detail.toString()).put("cancelled", detail.getString("semester_course_id") in cancellations)



            }



            val editableCourses = data.optJSONArray("editable")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() } ?: emptySet()



            val openSessionsMap = rows(data.optJSONArray("attendance") ?: JSONArray()).associateBy { it.optString("routine_slot_id") }



            liveOpenSessionsMap.clear()



            liveOpenSessionsMap.putAll(openSessionsMap)



            if (!active(root) || request != timetableRequest || day != selectedDay ||



                calendarMode || requestedBusMode != busMode || (requestedBusMode && requestedBusKind!=busDayKind()) || selectedBatch != batchId()) return@launch



            root.v<View>(R.id.scheduleHeader).visibility = View.VISIBLE



            text(root, R.id.tvScheduleLabel, if(requestedBusMode) "${days[day]} · all schedules" else "${days[day].uppercase()}'S SCHEDULE")



            text(root, R.id.tvPeriodCount, "${entries.size} ${if (requestedBusMode) { if (entries.size == 1) "schedule" else "schedules" } else if (entries.size == 1) "class" else "classes"}")



            root.v<TextView>(R.id.tvScheduleLabel).letterSpacing = if (requestedBusMode) 0f else 0.12f



            val summaryEntries = entries.filter { it.optString("schedule_kind") in setOf("office_open", "closed") && !it.isNull("city_departure_time") }



            root.v<View>(R.id.busSummary).visibility = if (requestedBusMode && summaryEntries.isNotEmpty()) View.VISIBLE else View.GONE



            if (requestedBusMode && summaryEntries.isNotEmpty()) {



                updateBusSummary(root, summaryEntries, effectiveDate, requestedBusKind)



                if (effectiveDate == LocalDate.now()) {



                    val tick = object : Runnable {



                        override fun run() {



                            if (!active(root) || request != timetableRequest || !busMode || calendarMode) return



                            if (activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) updateBusSummary(root, summaryEntries, effectiveDate, requestedBusKind)



                            root.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)



                        }



                    }



                    root.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000)



                }



            }



            val empty = entries.isEmpty()



            root.v<View>(R.id.emptyState).visibility = if (empty) View.VISIBLE else View.GONE



            root.v<View>(R.id.rvPeriods).visibility = if (empty) View.GONE else View.VISIBLE



            if (empty) {



                if (requestedBusMode) {



                    bindBusEmptyState(root)



                } else {



                    bindHolidayLottie(root, calendarData.classClosure(effectiveDate), effectiveDate)



                }



            } else {



                root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieNoClasses)?.let {



                    it.visibility = View.GONE



                    it.cancelAnimation()



                }



            }



            if (requestedBusMode) recycler(root, R.id.rvPeriods, R.layout.item_student_bus_schedule, entries) { card, item ->



                val paired=item.optString("schedule_kind") in setOf("office_open","closed") && !item.isNull("city_departure_time")



                card.v<View>(R.id.tvBusScheduleName).visibility = if (paired) View.GONE else View.VISIBLE



                text(card,R.id.tvBusScheduleName,item.optString("route_name"))



                text(card,R.id.tvCampusDepartureLabel,if(paired) "Campus → City" else "${item.optString("origin")} → ${item.optString("destination")}")



                text(card,R.id.tvCampusDeparture,hour(item.optString("departure_time")))



                card.v<View>(R.id.cityDepartureColumn).visibility=if(paired) View.VISIBLE else View.GONE



                if(paired) text(card,R.id.tvCityDeparture,hour(item.optString("city_departure_time")))



                if (canEditBus()) {



                    card.setOnClickListener { com.shuaib.classmate.ui.ClassMateHaptics.selection(it); onAddBus(item,requestedBusKind) }



                    card.setOnLongClickListener { com.shuaib.classmate.ui.ClassMateHaptics.hold(it); onAddBus(item,requestedBusKind); true }



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



                val isToday = effectiveDate == LocalDate.now()



                val isLive = isToday && !cancelled && runCatching {



                    val now = LocalTime.now()



                    val start = parseLocalTime(item.optString("start_time"))



                    val end = parseLocalTime(item.optString("end_time"))



                    start != null && end != null && !now.isBefore(start) && now.isBefore(end)



                }.getOrDefault(false)



                val isLabSession = item.optString("type").equals("lab", true) ||



                    (names[courseId] ?: "").trim().endsWith("lab", ignoreCase = true) ||



                    (names[courseId] ?: "").trim().endsWith("labs", ignoreCase = true)







                if (cancelled) {



                    text(card, R.id.tvTypeBadge, "CANCELLED")



                    card.v<com.google.android.material.card.MaterialCardView>(R.id.cardRoot).apply {



                        setCardBackgroundColor(activity.getColor(R.color.cm_period_cancel_bg))



                        strokeColor = activity.getColor(R.color.cm_period_cancel_border)



                    }



                    card.v<View>(R.id.vStatusIndicator).backgroundTintList =



                        android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_notice_cancel))



                    card.v<TextView>(R.id.tvTypeBadge).apply {



                        setBackgroundResource(R.drawable.bg_badge_cancelled)



                        setTextColor(activity.getColor(R.color.cm_notice_cancel_text))



                    }



                } else if (isLive) {



                    text(card, R.id.tvTypeBadge, "LIVE NOW")



                    card.v<com.google.android.material.card.MaterialCardView>(R.id.cardRoot).apply {



                        setCardBackgroundColor(activity.getColor(R.color.cm_card))



                        strokeColor = activity.getColor(R.color.cm_border_glass)



                    }



                    card.v<View>(R.id.vStatusIndicator).backgroundTintList =



                        android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_primary))



                    card.v<TextView>(R.id.tvTypeBadge).apply {



                        setBackgroundResource(R.drawable.bg_badge_green)



                        setTextColor(activity.getColor(R.color.cm_file_lab_text))



                    }



                } else {



                    text(card, R.id.tvTypeBadge, if (isLabSession) "LAB" else "CLASS")



                    card.v<com.google.android.material.card.MaterialCardView>(R.id.cardRoot).apply {



                        setCardBackgroundColor(activity.getColor(R.color.cm_card))



                        strokeColor = activity.getColor(R.color.cm_border_glass)



                    }



                    card.v<View>(R.id.vStatusIndicator).backgroundTintList =



                        android.content.res.ColorStateList.valueOf(activity.getColor(if (isLabSession) R.color.cm_file_lab_text else R.color.cm_primary))



                    card.v<TextView>(R.id.tvTypeBadge).apply {



                        if (isLabSession) {



                            setBackgroundResource(R.drawable.bg_badge_green)



                            setTextColor(activity.getColor(R.color.cm_file_lab_text))



                        } else {



                            setBackgroundResource(R.drawable.bg_badge_blue)



                            setTextColor(activity.getColor(R.color.cm_primary))



                        }



                    }



                }



                val minutes = runCatching {



                    val start = parseLocalTime(item.optString("start_time"))



                    val end = parseLocalTime(item.optString("end_time"))



                    if (start != null && end != null) java.time.Duration.between(start, end).toMinutes().coerceAtLeast(0) else 0L



                }.getOrDefault(0L)



                text(card, R.id.tvDuration, "$minutes min")



                if (item.optString("semester_course_id") in editableCourses) {



                    card.setOnLongClickListener { com.shuaib.classmate.ui.ClassMateHaptics.hold(it); onAddPeriod(item, day); true }



                    card.setOnClickListener { com.shuaib.classmate.ui.ClassMateHaptics.selection(it); onAddPeriod(item, day) }



                }







                val slotId = item.optString("id")



                val slotSession = liveOpenSessionsMap[slotId]



                val hasOpenSession = slotSession != null







                val role = profile().optString("role")



                val isTeacherViewer = (role == "teacher" && courseId in editableCourses)



                val isAdminViewer = role == "admin"



                val isStudentViewer = role == "student" || role.isBlank()



                val batchAttendanceAllowed = isAttendanceBatchAllowed()



                val isAttendanceEnabled = (detail?.optBoolean("attendance_enabled") == true || isTeacherViewer || isAdminViewer) && batchAttendanceAllowed







                val strip = card.findViewById<View>(R.id.layoutAttendanceStrip)



                if (strip != null) {



                    strip.visibility = View.GONE



                }



            }



            if (!requestedBusMode && !calendarMode && !attendanceMode && isAttendanceBatchAllowed()) {



                startAttendanceSessionsPoller(root, selectedBatch)



            } else {



                stopAttendanceSessionsPoller()



            }



            if (!requestedBusMode && effectiveDate == LocalDate.now() && entries.isNotEmpty()) {



                val routineTick = object : Runnable {



                    override fun run() {



                        if (!active(root) || request != timetableRequest || busMode || calendarMode) return



                        if (activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {



                            root.v<RecyclerView>(R.id.rvPeriods).adapter?.notifyDataSetChanged()



                        }



                        root.postDelayed(this, 15_000L)



                    }



                }



                root.postDelayed(routineTick, 15_000L)



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



                    root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieNoClasses)?.let {



                        it.visibility = View.GONE



                        it.cancelAnimation()



                    }



                    root.findViewById<TextView>(R.id.tvEmptyEmoji)?.let {



                        it.visibility = View.VISIBLE



                        it.text = "📡"



                    }



                }



            }



        }



    }







    private fun bindHolidayLottie(root: View, closure: String?, date: LocalDate) {



        val lottieView = root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieNoClasses) ?: return



        val tvEmoji = root.findViewById<TextView>(R.id.tvEmptyEmoji) ?: return



        val textToMatch = (closure ?: "").lowercase()



        val dayOfWeek = date.dayOfWeek



        val isWeekend = dayOfWeek == java.time.DayOfWeek.THURSDAY || dayOfWeek == java.time.DayOfWeek.FRIDAY







        val (animName, fallbackEmoji) = when {



            listOf("eid", "ramadan", "romjan", "fitr", "azha", "adho", "barat", "meraj", "qadr", "ashura", "hijri", "muharram", "fateha", "milad", "ঈদ", "রমজান", "মিরাজ", "বরাত", "কদর", "আশুরা", "ফাতেহা", "মিলাদুন্নবী", "সোম্বা").any { textToMatch.contains(it) } ->



                "eid" to "🌙"



            listOf("puja", "pooja", "durga", "saraswati", "kali", "diwali", "deepavali", "janmashtami", "dashami", "navami", "bijoya", "shivratri", "purnima", "buddha", "পূজা", "দুর্গা", "সরস্বতী", "জন্মাষ্টমী", "লক্ষ্মী", "বুদ্ধপূর্ণিমা").any { textToMatch.contains(it) } ->



                "puja" to "🪔"



            listOf("vacation", "break", "recess", "summer", "winter", "autumn", "spring", "অবকাশ", "ছুটি").any { textToMatch.contains(it) } ->



                "vacation" to "🏖️"



            listOf("independence", "victory", "language", "ekushey", "21st february", "21 february", "mourning", "revolution", "bangabandhu", "shaheed", "national", "may day", "labor", "martyrs", "uprising", "স্বাধীনতা", "বিজয়", "মাতৃভাষা", "শহিদ", "গণঅভ্যুত্থান", "ভাসানী", "বুদ্ধিজীবী").any { textToMatch.contains(it) } ->



                "national" to "🌺"



            listOf("weekend", "weekly", "বৃহস্পতিবার", "শুক্রবার").any { textToMatch.contains(it) } || isWeekend ->



                "weekend" to "☕"



            else ->



                "celebration" to "🎉"



        }







        tvEmoji.text = fallbackEmoji



        val url = "https://classmatebd.vercel.app/animations/$animName.json"







        if (lottieView.tag == animName && lottieView.isAnimating) {



            lottieView.visibility = View.VISIBLE



            tvEmoji.visibility = View.GONE



            return



        }







        lottieView.tag = animName



        lottieView.visibility = View.GONE



        tvEmoji.visibility = View.VISIBLE







        lottieView.setFailureListener {



            lottieView.visibility = View.GONE



            tvEmoji.visibility = View.VISIBLE



        }



        lottieView.removeAllLottieOnCompositionLoadedListener()



        lottieView.addLottieOnCompositionLoadedListener {



            if (lottieView.tag == animName) {



                lottieView.visibility = View.VISIBLE



                tvEmoji.visibility = View.GONE



                lottieView.playAnimation()



            }



        }



        lottieView.setAnimationFromUrl(url)



    }







    private fun bindBusEmptyState(root: View) {



        val lottieView = root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieNoClasses)



        val tvEmoji = root.findViewById<TextView>(R.id.tvEmptyEmoji)



        lottieView?.visibility = View.GONE



        lottieView?.cancelAnimation()



        lottieView?.tag = null



        tvEmoji?.visibility = View.VISIBLE



        tvEmoji?.text = "🚌"



        text(root, R.id.tvNoClassesTitle, "No bus schedules")



        text(root, R.id.tvNoClassesSubtitle, "No trips scheduled for this date")



    }







    var currentNoticeRoot: View? = null



    fun openNoticeSearch(query: String? = null) {



        currentNoticeRoot?.let { root ->



            root.findViewById<View>(R.id.headerTitleBlock)?.visibility = View.GONE



            root.findViewById<View>(R.id.btnSearch)?.visibility = View.GONE



            root.findViewById<View>(R.id.searchContainer)?.visibility = View.VISIBLE



            val input = root.findViewById<android.widget.EditText>(R.id.etNoticeSearch)



            if (!query.isNullOrBlank()) {



                input?.setText(query)



                input?.setSelection(query.length)



            } else {



                input?.text?.clear()



            }



            input?.requestFocus()



            input?.post {



                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)



                    ?.showSoftInput(input, 0)



            }



        }



    }







    private fun setupNotices(root: View) {



        currentNoticeRoot = root



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



        var searchRender:Runnable?=null



        root.v<EditText>(R.id.etNoticeSearch).addTextChangedListener(object : android.text.TextWatcher {



            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}



            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {



                searchRender?.let { root.removeCallbacks(it) }



                searchRender=Runnable { if(active(root)) renderNoticeFeed(root) }



                root.postDelayed(searchRender!!,180)



            }



            override fun afterTextChanged(s: android.text.Editable?) {}



        })



        root.v<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener { loadNotices(root) }



        if (noticeBatch != batchId() || noticeFeed.isEmpty()) restoreNoticeCache()



        if (noticeBatch == batchId() && noticeFeed.isNotEmpty()) renderNoticeFeed(root)



        if (ClassMateAcademicCache.online(activity)) loadNotices(root)



        startNoticePoller(root)



    }







    private fun loadNotices(root: View,preserveOlder: Boolean=false): Unit = launch(root) {



        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)



        val selectedBatch = batchId()



        if (!ClassMateAcademicCache.online(activity)) { refresh.isRefreshing = false; renderNoticeFeed(root); return@launch }



        val request = ++noticeRequest



        noticeLoadingOlder=false; noticeOlderError=false



        val countToFetch = if (preserveOlder && noticeFeed.isNotEmpty()) maxOf(noticePageSize, minOf(noticeFeed.size, 50)) else noticePageSize



        val older=if(preserveOlder && noticeBatch==selectedBatch) noticeFeed.drop(countToFetch)+noticeOfflineTail else emptyList()



        val previousHasMore=noticeHasMore



        val shimmer = root.v<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerView)



        if (noticeFeed.isEmpty() || noticeBatch != selectedBatch) {



            shimmer.visibility = View.VISIBLE



            shimmer.startShimmer()



        }



        try {



                val visible = rows(ClassMateAuthApi.rows("notices",



                "select=id,title,body,published_at,semester_course_id,author_id,resource_id,class_change_id" +



                    "&batch_id=eq.$selectedBatch${teacherNoticeFilter()}&order=published_at.desc,id.desc&limit=$countToFetch"))



            if (!active(root) || request != noticeRequest || selectedBatch != batchId()) return@launch



            noticeBatch = selectedBatch



            noticeFeed=(visible+if(visible.isNotEmpty()) older else emptyList()).distinctBy { it.optString("id") }



                .sortedWith(compareByDescending<JSONObject> { it.optString("published_at") }.thenByDescending { it.optString("id") })



            noticeOfflineTail=emptyList(); noticeHasMore=if(older.isNotEmpty() && visible.isNotEmpty()) previousHasMore else visible.size==countToFetch



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



            noticeSyncedAt=android.os.SystemClock.elapsedRealtime()



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







    private fun loadOlderNotices(root: View) {



        if(!active(root) || noticeLoadingOlder || !noticeHasMore || noticeFeed.isEmpty()) return



        val online=ClassMateAcademicCache.online(activity)



        if(!online && noticeOfflineTail.isEmpty()) return



        val request=noticeRequest; val batch=batchId(); val cursor=noticeFeed.last()



        noticeLoadingOlder=true; noticeOlderError=false; renderNoticeFeed(root)



        launch(root) {



            try {



                val page=if(!online) noticeOfflineTail.take(noticePageSize) else {



                    val timestamp=java.net.URLEncoder.encode(cursor.getString("published_at"),"UTF-8")



                    val id=cursor.getString("id")



                    rows(ClassMateAuthApi.rows("notices","select=id,title,body,published_at,semester_course_id,author_id,resource_id,class_change_id"+



                        "&batch_id=eq.$batch${teacherNoticeFilter()}&or=(published_at.lt.$timestamp,and(published_at.eq.$timestamp,id.lt.$id))&order=published_at.desc,id.desc&limit=$noticePageSize"))



                }



                if(!active(root) || request!=noticeRequest || batch!=batchId()) return@launch



                noticeFeed=(noticeFeed+page).distinctBy { it.optString("id") }



                if(!online) noticeOfflineTail=noticeOfflineTail.drop(page.size)



                noticeHasMore=if(online) page.size==noticePageSize else noticeOfflineTail.isNotEmpty()



                saveNoticeCache(); renderNoticeFeed(root)



                if(online && page.isNotEmpty()) {



                    val details=ClassMateAuthApi.rpc("notice_feed_details",JSONObject().put("target_ids",JSONArray(page.map { it.getString("id") })))



                    if(!active(root) || request!=noticeRequest || batch!=batchId()) return@launch



                    rows(details.optJSONArray("engagement") ?: JSONArray()).forEach { noticeStates[it.getString("notice_id")]=it }



                    rows(details.optJSONArray("authors") ?: JSONArray()).forEach { noticeAuthors[it.getString("notice_id")]=it }



                    rows(details.optJSONArray("reads") ?: JSONArray()).forEach { noticeReadCounts[it.getString("notice_id")]=it; if(it.optBoolean("read_by_me")) noticeReadSent.add(it.getString("notice_id")) }



                    rows(details.optJSONArray("previews") ?: JSONArray()).groupBy { it.getString("notice_id") }.forEach { (id,readers) -> noticeReaderPreviews[id]=readers }



                    saveNoticeCache()



                }



            } catch(e: Exception) {



                if(request==noticeRequest && active(root)) noticeOlderError=true



            } finally {



                if(request==noticeRequest) { noticeLoadingOlder=false; if(active(root)) renderNoticeFeed(root) }



            }



        }



    }







    private fun saveNoticeCache() {



        fun mapJson(map: Map<String, JSONObject>) = JSONObject().apply { map.forEach { (id, value) -> put(id, value) } }



        val previews = JSONObject().apply { noticeReaderPreviews.forEach { (id, value) -> put(id, JSONArray(value)) } }



        ClassMateAcademicCache.save(activity, profile().optString("id"), batchId(), "notices", JSONObject()



            .put("feed", JSONArray((noticeFeed+noticeOfflineTail).distinctBy { it.optString("id") })).put("has_more",noticeHasMore).put("states", mapJson(noticeStates)).put("authors", mapJson(noticeAuthors))



            .put("reads", mapJson(noticeReadCounts)).put("previews", previews))



    }



    private fun restoreNoticeCache() {



        noticeFeed = emptyList(); noticeStates.clear(); noticeAuthors.clear(); noticeReadCounts.clear(); noticeReaderPreviews.clear()



        noticeOfflineTail=emptyList(); noticeHasMore=false; noticeLoadingOlder=false; noticeOlderError=false



        noticeReadSent.clear(); translatingNoticeIds.clear()



        noticeBatch = batchId()



        val data = ClassMateAcademicCache.read(activity, profile().optString("id"), batchId(), "notices") ?: return



        val cached=rows(data.optJSONArray("feed") ?: JSONArray())



        noticeBatch = batchId(); noticeFeed = cached.take(noticePageSize); noticeOfflineTail=cached.drop(noticePageSize)



        noticeHasMore=noticeOfflineTail.isNotEmpty() || (ClassMateAcademicCache.online(activity) && data.optBoolean("has_more",cached.size>=noticePageSize))



        fun restore(name: String, map: MutableMap<String, JSONObject>) { val json = data.optJSONObject(name) ?: return; json.keys().forEach { id -> json.optJSONObject(id)?.let { map[id] = it } } }



        restore("states", noticeStates); restore("authors", noticeAuthors); restore("reads", noticeReadCounts)



        data.optJSONObject("previews")?.let { json -> json.keys().forEach { id -> noticeReaderPreviews[id] = rows(json.optJSONArray(id) ?: JSONArray()) } }



    }







    private fun teacherNoticeFilter() = if(profile().optString("role")=="teacher") "&author_id=eq.${profile().optString("id")}" else ""







    private fun renderNoticeFeed(root: View) {



        if (!root.isAttachedToWindow) return



        val search = root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()



        text(root, R.id.tvNoticeSubtitle, "${noticeFeed.size}${if(noticeHasMore) "+" else ""} updates · ${if (ClassMateAcademicCache.online(activity)) "Pull to refresh" else "Offline · Last synced"}")



        val filtered = noticeFeed.filter { (profile().optString("role")!="teacher" || it.optString("author_id")==profile().optString("id")) && (search.isBlank() ||



            it.optString("title").contains(search, true) || it.optString("body").contains(search, true)) }



            .sortedWith(compareByDescending<JSONObject> {



                noticeStates[it.optString("id")]?.optBoolean("is_pinned") == true



            }.thenByDescending { it.optString("published_at") })



        if(profile().optString("role")=="teacher") {



            text(root,R.id.tvEmptyTitle,"You haven’t posted any notices")



            text(root,R.id.tvEmptySubtitle,"Try posting your first course notice!")



        }



        noticeVisibleIds = filtered.map { it.optString("id") }



        noticeHeaderOffset = if (filtered.isEmpty()) 0 else 1



        root.v<View>(R.id.emptyNoticeState).visibility = if (filtered.isEmpty() && !noticeHasMore && !noticeLoadingOlder) View.VISIBLE else View.GONE



        val list = root.v<RecyclerView>(R.id.rvNotices)

        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)

        if (list.layoutManager == null) list.layoutManager = LinearLayoutManager(activity)



        val bindNotice:(View,JSONObject)->Unit = { card, item ->



                val noticeId = item.getString("id")



                val state = noticeStates[noticeId]



                val resourceId = item.optString("resource_id").takeUnless {



                    it.isBlank() || it == "null" }



                val title = item.optString("title")



                val classChangeId = item.optString("class_change_id").takeUnless {



                    it.isBlank() || it == "null" }



                val resourceNotice = resourceId != null || title.contains(noticeResourcePattern)



                val cancellation = !resourceNotice && (classChangeId != null || title.contains(noticeCancellationPattern))



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



                card.v<android.widget.ImageView>(R.id.ivNoticeIllustration).imageTintList = null



                card.v<View>(R.id.noticeAccent).backgroundTintList =



                    android.content.res.ColorStateList.valueOf(activity.getColor(accent))



                val source = title + "\n" + item.optString("body")



                if (noticeTranslations[noticeId]?.optString("source") != source) {



                    noticeTranslations.remove(noticeId)



                    translatedNotices.remove(noticeId)



                }



                val cached = noticeTranslations[noticeId]



                val currentTitle = if (noticeId in translatedNotices && cached != null) cached.optString("title") else title



                val currentBody = if (noticeId in translatedNotices && cached != null) cached.optString("body") else item.optString("body")







                val isExpanded = noticeId in expandedNoticeIds



                val toggleExpand = {



                    if (noticeId in expandedNoticeIds) {



                        expandedNoticeIds.remove(noticeId)



                    } else {



                        expandedNoticeIds.add(noticeId)



                    }



                    val transitionGroup = (card as? ViewGroup) ?: (list as? ViewGroup)



                    if (transitionGroup != null) {



                        android.transition.TransitionManager.beginDelayedTransition(transitionGroup)



                    }



                    renderNoticeFeed(root)



                }







                val tvTitle = card.v<TextView>(R.id.tvTitle)



                tvTitle.maxLines = if (isExpanded) Int.MAX_VALUE else 3



                tvTitle.text = ClassMateNoticeText.styled(tvTitle, currentTitle, search)



                tvTitle.setTextIsSelectable(true)



                tvTitle.movementMethod = ClassMateNoticeText.newMovementMethod()



                tvTitle.setOnClickListener(null)



                tvTitle.setOnLongClickListener(null)







                val tvPreview = card.v<TextView>(R.id.tvPreview)



                tvPreview.visibility = if (currentBody.isBlank()) View.GONE else View.VISIBLE



                tvPreview.setOnClickListener(null)



                tvPreview.setOnLongClickListener(null)



                ClassMateNoticeText.bind(



                    view = tvPreview,



                    body = currentBody,



                    query = search,



                    isExpanded = isExpanded,



                    onToggleExpand = toggleExpand



                )



                text(card, R.id.tvMeta, noticeDate(item.optString("published_at")))



                val isTranslating = noticeId in translatingNoticeIds



                val translation = card.v<TextView>(R.id.btnTranslate)



                translation.isEnabled = !isTranslating



                translation.text = if (isTranslating) "Translating…" else if (noticeId in translatedNotices) "Original" else "Translate"



                translation.setOnClickListener {



                    if (isTranslating) return@setOnClickListener



                    if (noticeId in translatedNotices) {



                        translatedNotices.remove(noticeId)



                        renderNoticeFeed(root)



                    } else if (cached != null) {



                        translatedNotices.add(noticeId)



                        renderNoticeFeed(root)



                    } else {



                        translatingNoticeIds.add(noticeId)



                        renderNoticeFeed(root)



                        scope.launch {



                            try {



                                val result = ClassMateAuthApi.ai(JSONObject().put("mode", "translate").put("request_id", java.util.UUID.randomUUID().toString()).put("batch_id", batchId()).put("notice_id", noticeId))



                                if (!active(root)) return@launch



                                noticeTranslations[noticeId] = result.getJSONObject("result").put("source", source)



                                translatedNotices.add(noticeId)



                            } catch (e: Exception) {



                                if (active(root)) {



                                    Toast.makeText(activity, e.message ?: "Translation failed", Toast.LENGTH_LONG).show()



                                }



                            } finally {



                                translatingNoticeIds.remove(noticeId)



                                if (active(root)) renderNoticeFeed(root)



                            }



                        }



                    }



                }



                text(card, R.id.tvLikeCount, state?.optLong("like_count")?.toString() ?: "0")



                text(card, R.id.tvCommentCount, state?.optLong("comment_count")?.toString() ?: "0")



                val authorId = item.optString("author_id")



                val rawReadCount = noticeReadCounts[noticeId]?.optLong("read_count") ?: 0L



                val authorRead = (noticeReaderPreviews[noticeId].orEmpty()).any { it.optString("profile_id") == authorId } ||



                    (noticeReadCounts[noticeId]?.optBoolean("read_by_me") == true && authorId == profile().optString("id"))



                val effectiveSeen = (rawReadCount - (if (authorRead) 1 else 0)).coerceAtLeast(0)



                text(card, R.id.tvReadCount, seenLabel(effectiveSeen))



                bindSeenAvatar(card, noticeId, authorId)



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



                card.findViewById<View>(R.id.btnShare)?.apply {



                    visibility = View.VISIBLE



                    setOnClickListener {



                        it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)



                        shareNotice(item)



                    }



                }



                if (noticeId.equals(highlightedNoticeId, ignoreCase = true)) {



                    card.post { highlightNoticeCard(card) }



                }



                card.findViewById<View>(R.id.imagePreviewContainer)?.visibility = View.GONE



                card.v<View>(R.id.fileAttachmentContainer).visibility =



                    if (resourceId == null) View.GONE else View.VISIBLE



                if (resourceId != null) {



                    text(card, R.id.tvFileName, item.optString("title").removePrefix("Resource: "))



                    text(card, R.id.tvFileSize, "Tap to open protected file")



                    val ext = title.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 } ?: "FILE"



                    text(card, R.id.tvFileExtension, ext)



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



                card.v<View>(R.id.btnReminder).apply {



                    visibility = View.VISIBLE



                    setOnClickListener { showReminderOptions(root, card, item) }



                }



                val reminderAt = state?.optString("reminder_at")



                val reminderPending = runCatching {



                    reminderAt?.let(Instant::parse)?.isAfter(Instant.now()) == true



                }.getOrDefault(false)



                setReminderAppearance(card, reminderPending)



                val cardRoot = card.v<View>(R.id.cardRoot)



                cardRoot.setOnClickListener(null)



                cardRoot.isClickable = false



                cardRoot.isFocusable = false



                cardRoot.setOnLongClickListener(null)



            }



        val bindPaging:(View)->Unit = { footer ->



            val shimmer=footer.v<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerOlderNotices)



            shimmer.visibility=if(noticeLoadingOlder) View.VISIBLE else View.GONE



            if(noticeLoadingOlder) shimmer.startShimmer() else shimmer.stopShimmer()



            footer.v<android.widget.Button>(R.id.btnNoticePageRetry).apply {



                visibility=if(noticeLoadingOlder) View.GONE else View.VISIBLE



                text=if(noticeOlderError) "Try loading older notices again" else "Load older notices"



                setOnClickListener { loadOlderNotices(root) }



            }



        }



        val rows=buildList {



            filtered.forEach { item ->



                val id=item.getString("id")



                val signature=listOf(item.toString(),noticeStates[id]?.toString(),noticeReadCounts[id]?.toString(),



                    noticeReaderPreviews[id]?.joinToString { it.toString() },noticeTranslations[id]?.toString(),



                    id in translatedNotices,id in translatingNoticeIds,id in expandedNoticeIds,search,profile().optString("role"),profile().optBoolean("is_cr")).joinToString("\n")



                add(ClassMateNoticeRow("notice:$id",R.layout.item_notice_modern,signature) { bindNotice(it,item) })



            }



            if(noticeHasMore || noticeLoadingOlder) add(ClassMateNoticeRow("paging",R.layout.item_notice_paging,



                "$noticeLoadingOlder:$noticeOlderError",bindPaging))



        }



        val adapter=(list.adapter as? ClassMateNoticeRows) ?: ClassMateNoticeRows().also { list.adapter=it }



        adapter.submitList(rows) {



            list.post { if(active(root) && list.scrollState==RecyclerView.SCROLL_STATE_IDLE) markVisibleNoticesRead(root,list) }



        }



    }







    private fun noticeDate(value: String): String = runCatching {



        noticeDateFormatter.format(Instant.parse(value))



    }.getOrElse { value.replace('T', ' ').take(16) }







    private fun compactCount(count: Long): String = when {



        count >= 1_000_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000_000.0)}m"



        count >= 1_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000.0)}k"



        else -> count.toString()



    }



    private fun seenLabel(count: Long) = "${compactCount(count)} seen"







    private fun showNoticeContent(item: JSONObject, query: String = "") {



        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_notice_detail, null, false)



        val tvNoticeDetailTitle = dialogView.findViewById<TextView>(R.id.tvNoticeDetailTitle)



        val tvNoticeDetailBody = dialogView.findViewById<TextView>(R.id.tvNoticeDetailBody)



        val btnCloseNotice = dialogView.findViewById<MaterialButton>(R.id.btnCloseNotice)



        val btnCopyNotice = dialogView.findViewById<MaterialButton>(R.id.btnCopyNotice)



        val fileAttachmentCard = dialogView.findViewById<View>(R.id.fileAttachmentCard)



        val btnOpenResource = dialogView.findViewById<MaterialButton>(R.id.btnOpenResource)







        val title = item.optString("title").trim()



        val body = item.optString("body").trim()



        val resourceId = item.optString("resource_id").takeIf { it.isNotBlank() && it != "null" }







        tvNoticeDetailTitle.text = ClassMateNoticeText.styled(tvNoticeDetailTitle, title, query)



        tvNoticeDetailTitle.setTextIsSelectable(true)



        tvNoticeDetailTitle.movementMethod = ClassMateNoticeText.newMovementMethod()







        tvNoticeDetailBody.text = ClassMateNoticeText.styled(tvNoticeDetailBody, body, query)



        tvNoticeDetailBody.setTextIsSelectable(true)



        tvNoticeDetailBody.movementMethod = ClassMateNoticeText.newMovementMethod()



        tvNoticeDetailBody.setLinkTextColor(activity.getColor(R.color.cm_primary))







        if (resourceId != null) {



            fileAttachmentCard.visibility = View.VISIBLE



            btnOpenResource.setOnClickListener {



                openFile(JSONObject().put("id", resourceId))



            }



        } else {



            fileAttachmentCard.visibility = View.GONE



        }







        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)



            .setBackground(noticeDialogSurface())



            .setView(dialogView)



            .create()







        btnCloseNotice.setOnClickListener { dialog.dismiss() }







        btnCopyNotice.setOnClickListener {



            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager



            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ClassMate notice", "$title\n\n$body"))



            if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(activity, "Notice copied", Toast.LENGTH_SHORT).show()



            btnCopyNotice.text = "Copied!"



            btnCopyNotice.postDelayed({ btnCopyNotice.text = "Copy" }, 2000)



        }







        dialog.show()







        // Set wide layout width (compact & wider layout)



        dialog.window?.let { window ->



            val displayMetrics = activity.resources.displayMetrics



            val maxAllowedWidth = (720 * displayMetrics.density).toInt()



            val targetWidth = (displayMetrics.widthPixels * 0.96f).toInt().coerceAtMost(maxAllowedWidth)



            window.setLayout(targetWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)



            window.setGravity(android.view.Gravity.CENTER)



        }



    }







    private fun bindSeenAvatar(card: View, noticeId: String, authorId: String = "") {



        val group = card.v<android.widget.FrameLayout>(R.id.seenAvatars)



        val adminReceiptsOff = profile().optString("role") == "admin" && !appPrefs.isNoticeReadReceiptsEnabled()



        val readers = noticeReaderPreviews[noticeId].orEmpty()



            .filterNot { it.optString("profile_id") == authorId || (adminReceiptsOff && it.optString("profile_id") == profile().optString("id")) }



            .take(if (activity.resources.configuration.screenWidthDp < 360) 3 else 4)



        val fingerprint=readers.joinToString { it.toString() }



        if(group.tag==fingerprint) return



        group.tag=fingerprint



        group.removeAllViews()



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



        if (list.height == 0) {



            list.post { if (active(root) && list.height > 0) markVisibleNoticesRead(root, list) }



            return



        }



        val manager = list.layoutManager as? LinearLayoutManager ?: return



        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)



        val last = manager.findLastVisibleItemPosition().coerceAtMost((list.adapter?.itemCount ?: 0)-1)



        if (last < first) return



        val newIds = (first..last).mapNotNull { position ->



            val child = manager.findViewByPosition(position) ?: return@mapNotNull null



            if (child.bottom <= list.paddingTop || child.top >= list.height ||



                (minOf(child.bottom, list.height) - maxOf(child.top, list.paddingTop)) <



                child.height / 2) return@mapNotNull null



            (list.adapter as? ClassMateNoticeRows)?.noticeId(position)?.takeIf { noticeReadSent.add(it) }



        }



        if (newIds.isEmpty()) return







        val myProfileId = profile().optString("id")



        val isAdminReceiptsOff = profile().optString("role") == "admin" && !appPrefs.isNoticeReadReceiptsEnabled()







        // Optimistically apply read state immediately to UI and cache so badge and seen counts update with 0ms lag



        newIds.forEach { id ->



            if (!isAdminReceiptsOff) {



                val previews = noticeReaderPreviews[id].orEmpty()



                if (previews.none { it.optString("profile_id") == myProfileId }) {



                    noticeReaderPreviews[id] = (listOf(JSONObject().put("profile_id", myProfileId)



                        .put("reader_name", profile().optString("full_name")).put("avatar_url", ownGoogleAvatar ?: "")) + previews).take(4)



                }



            }



            val count = noticeReadCounts.getOrPut(id) { JSONObject() }



            if (!count.optBoolean("read_by_me")) {



                count.put("read_by_me", true)



                if (!isAdminReceiptsOff) {



                    count.put("read_count", count.optLong("read_count") + 1)



                }



            }



        }



        saveNoticeCache()



        onNoticeRead()







        for (position in first..last) {



            val id = (list.adapter as? ClassMateNoticeRows)?.noticeId(position)



            val view = manager.findViewByPosition(position)



            if (id in newIds && view != null) {



                text(view, R.id.tvReadCount,



                    seenLabel(noticeReadCounts[id]?.optLong("read_count") ?: 0))



                id?.let { bindSeenAvatar(view, it) }



            }



        }







        val requestedBatch=batchId()



        val requestedProfile=profile().optString("id")



        launch(root) {



            runCatching {



                ClassMateAuthApi.rpcText("mark_notices_read", JSONObject()



                    .put("target_ids", JSONArray(newIds)))



            }.onFailure {



                if (active(root) && requestedBatch == batchId() && requestedProfile == profile().optString("id")) {



                    noticeReadSent.removeAll(newIds.toSet())



                }



            }



        }



    }







    private fun showNoticeReadReceipts(root: View, notice: JSONObject) {



        val noticeId = notice.optString("id")



        val currentUserId = profile().optString("id")



        val isAdmin = profile().optString("role") == "admin"



        val receiptsEnabled = appPrefs.isNoticeReadReceiptsEnabled()



        val totalReadCount = noticeReadCounts[noticeId]?.optLong("read_count") ?: 0L







        ClassMateMessageInfo.show(



            activity = activity,



            notice = notice,



            currentUserId = currentUserId,



            isAdmin = isAdmin,



            receiptsEnabled = receiptsEnabled,



            initialReadCount = totalReadCount



        )



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



        val heart = card.v<android.widget.ImageView>(R.id.ivLikeIcon)



        heart.setImageResource(



            if (enabled) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)



        heart.imageTintList =



            android.content.res.ColorStateList.valueOf(activity.getColor(



                if (enabled) R.color.cm_notice_like else R.color.cm_text_muted))



        (heart.parent as? ViewGroup)?.clipChildren = false



        (heart.parent as? ViewGroup)?.clipToPadding = false



        val cx = if (heart.width > 0) heart.width / 2f else 10f * activity.resources.displayMetrics.density



        val cy = if (heart.height > 0) heart.height / 2f else 10f * activity.resources.displayMetrics.density



        heart.pivotX = cx



        heart.pivotY = cy



        if (enabled) {



            heart.animate().cancel()



            heart.scaleX = 0.2f



            heart.scaleY = 0.2f



            heart.alpha = 0.8f



            heart.animate()



                .scaleX(1.35f)



                .scaleY(1.35f)



                .alpha(1f)



                .setDuration(160)



                .setInterpolator(android.view.animation.OvershootInterpolator(3.5f))



                .withEndAction {



                    heart.animate()



                        .scaleX(1.0f)



                        .scaleY(1.0f)



                        .setDuration(120)



                        .setInterpolator(android.view.animation.DecelerateInterpolator())



                        .start()



                }.start()



        } else {



            heart.animate().cancel()



            heart.scaleX = 0.8f



            heart.scaleY = 0.8f



            heart.animate()



                .scaleX(1.0f)



                .scaleY(1.0f)



                .setDuration(150)



                .start()



        }



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







    private fun showNoticeComments(root: View, notice: JSONObject) {



        ClassMateComments.show(activity,notice,changed={ if(active(root)) loadNotices(root) })



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







    private var currentLibraryRoot: View? = null



    fun triggerRfidAttendanceDialog() {



        showRfidAttendanceDialog(currentLibraryRoot ?: activity.window.decorView)



    }







    private fun showRfidAttendanceDialog(root: View) {



        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_rfid_attendance, null, false)



        val coursesView = dialogView.findViewById<LinearLayout>(R.id.layoutCoursesView)



        val tableView = dialogView.findViewById<LinearLayout>(R.id.layoutTableView)



        val coursesContainer = dialogView.findViewById<LinearLayout>(R.id.coursesContainer)



        val btnBack = dialogView.findViewById<View>(R.id.btnBackToCourses)



        val tvTableTitle = dialogView.findViewById<TextView>(R.id.tvTableCourseTitle)



        val tvTableCode = dialogView.findViewById<TextView>(R.id.tvTableCourseCode)



        val tableContainer = dialogView.findViewById<LinearLayout>(R.id.tableRowsContainer)



        val btnCloseAttendance = dialogView.findViewById<View>(R.id.btnCloseAttendanceSheet)



        val btnCloseTable = dialogView.findViewById<View>(R.id.btnCloseTableSheet)







        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)



        bottomSheet.setContentView(dialogView)







        val parentView = dialogView.parent as? View



        parentView?.setBackgroundResource(android.R.color.transparent)







        val density = activity.resources.displayMetrics.density



        val displayMetrics = activity.resources.displayMetrics



        val screenHeight = displayMetrics.heightPixels







        val behavior = bottomSheet.behavior



        behavior.isFitToContents = true



        behavior.skipCollapsed = true



        behavior.maxHeight = (screenHeight * 0.88f).toInt()



        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED







        val padHorizontal = (20 * density).toInt()



        val padTop = (12 * density).toInt()



        val basePadBottom = (24 * density).toInt()







        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(dialogView) { v, insets ->



            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())



            v.setPadding(padHorizontal, padTop, padHorizontal, basePadBottom + navBars.bottom)



            insets



        }



        androidx.core.view.ViewCompat.requestApplyInsets(dialogView)







        btnCloseAttendance?.setOnClickListener {



            com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



            bottomSheet.dismiss()



        }



        btnCloseTable?.setOnClickListener {



            com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



            bottomSheet.dismiss()



        }







        val padH = (12 * density).toInt()



        val padV = (10 * density).toInt()







        fun showCoursesView() {



            tableView.visibility = View.GONE



            coursesView.visibility = View.VISIBLE



            coursesView.alpha = 0f



            coursesView.animate().alpha(1f).setDuration(150).start()



            parentView?.post {



                parentView.requestLayout()



                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED



            }



        }







        fun showTableView() {



            coursesView.visibility = View.GONE



            tableView.visibility = View.VISIBLE



            tableView.alpha = 0f



            tableView.animate().alpha(1f).setDuration(150).start()



            parentView?.post {



                parentView.requestLayout()



                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED



            }



        }







        fun renderTable(course: JSONObject) {



            val courseTitle = course.optString("course_title").ifBlank { "Course Attendance" }



            val courseCode = course.optString("course_code").ifBlank { "Daily Log" }







            tvTableTitle.text = courseTitle



            tvTableCode.text = "$courseCode · Daily Attendance Log"



            tableContainer.removeAllViews()







            showTableView()







            val headerRow = LinearLayout(activity).apply {



                orientation = LinearLayout.HORIZONTAL



                setPadding(padH, padV, padH, padV)



                setBackgroundColor(activity.getColor(R.color.cm_surface_glass))



            }



            val headers = listOf("Date" to 2.2f, "Class" to 1.5f, "Status" to 1.8f, "Time" to 2.3f)



            headers.forEach { (text, weight) ->



                val tv = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)



                    this.text = text



                    textSize = 10.5f



                    setTextColor(activity.getColor(R.color.cm_text_secondary))



                    typeface = android.graphics.Typeface.DEFAULT_BOLD



                }



                headerRow.addView(tv)



            }



            tableContainer.addView(headerRow)







            val history = course.optJSONArray("history") ?: JSONArray()



            if (history.length() == 0) {



                val tvEmpty = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                        topMargin = (20 * density).toInt()



                        bottomMargin = (20 * density).toInt()



                    }



                    text = "No individual records found for this course yet."



                    gravity = android.view.Gravity.CENTER



                    textSize = 12.5f



                    setTextColor(activity.getColor(R.color.cm_text_muted))



                }



                tableContainer.addView(tvEmpty)



            } else {



                for (i in 0 until history.length()) {



                    val record = history.getJSONObject(i)



                    val dateStr = record.optString("date")



                    val timeStr = record.optString("time")



                    val rawRoom = record.optString("room").trim()



                    val displayRoom = when {



                        rawRoom.isEmpty() || rawRoom.equals("null", ignoreCase = true) -> "Station Reader"



                        rawRoom.contains("room", ignoreCase = true) || rawRoom.contains("station", ignoreCase = true) || rawRoom.contains("lab", ignoreCase = true) -> rawRoom



                        else -> "Room $rawRoom"



                    }



                    val isPresent = record.optString("status").equals("present", ignoreCase = true)



                    val classNum = "Class #${history.length() - i}"







                    val row = LinearLayout(activity).apply {



                        orientation = LinearLayout.HORIZONTAL



                        setPadding(padH, (8 * density).toInt(), padH, (8 * density).toInt())



                        gravity = android.view.Gravity.CENTER_VERTICAL



                    }



                    val tvDate = TextView(activity).apply {



                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f)



                        text = dateStr



                        textSize = 11.5f



                        setTextColor(activity.getColor(R.color.cm_text_primary))



                    }



                    val tvClass = TextView(activity).apply {



                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)



                        text = classNum



                        textSize = 11.5f



                        setTextColor(activity.getColor(R.color.cm_text_secondary))



                    }



                    val tvStatus = TextView(activity).apply {



                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f)



                        text = if (isPresent) "Present" else "Absent"



                        textSize = 11f



                        typeface = android.graphics.Typeface.DEFAULT_BOLD



                        setTextColor(if (isPresent) activity.getColor(R.color.cm_success) else activity.getColor(R.color.cm_error))



                    }



                    val tvTime = TextView(activity).apply {



                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.3f)



                        text = if (timeStr.isNotBlank()) "$timeStr\n$displayRoom" else "—"



                        textSize = 10.5f



                        setTextColor(activity.getColor(R.color.cm_text_muted))



                    }



                    row.addView(tvDate)



                    row.addView(tvClass)



                    row.addView(tvStatus)



                    row.addView(tvTime)



                    tableContainer.addView(row)



                }



            }



        }







        btnBack.setOnClickListener {



            com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



            showCoursesView()



        }







        bottomSheet.setOnKeyListener { _, keyCode, event ->



            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {



                if (tableView.visibility == View.VISIBLE) {



                    com.shuaib.classmate.ui.ClassMateHaptics.selection(dialogView)



                    showCoursesView()



                    true



                } else {



                    false



                }



            } else {



                false



            }



        }







        bottomSheet.onBackPressedDispatcher.addCallback(bottomSheet, object : androidx.activity.OnBackPressedCallback(true) {



            override fun handleOnBackPressed() {



                if (tableView.visibility == View.VISIBLE) {



                    com.shuaib.classmate.ui.ClassMateHaptics.selection(dialogView)



                    showCoursesView()



                } else {



                    isEnabled = false



                    bottomSheet.dismiss()



                }



            }



        })







        coursesContainer.removeAllViews()



        val progress = android.widget.ProgressBar(activity).apply {



            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                gravity = android.view.Gravity.CENTER_HORIZONTAL



                topMargin = (20 * density).toInt()



            }



        }



        coursesContainer.addView(progress)







        fun populateCourseCards(coursesList: List<JSONObject>) {



            coursesContainer.removeAllViews()



            if (coursesList.isEmpty()) {



                val tvEmpty = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                        topMargin = (24 * density).toInt()



                        bottomMargin = (24 * density).toInt()



                    }



                    text = "No attendance sessions recorded yet for your courses."



                    gravity = android.view.Gravity.CENTER



                    textSize = 13f



                    setTextColor(activity.getColor(R.color.cm_text_muted))



                }



                coursesContainer.addView(tvEmpty)



                parentView?.post {



                    parentView.requestLayout()



                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED



                }



                return



            }







            coursesList.forEach { course ->



                val code = course.optString("course_code")



                val title = course.optString("course_title")



                val type = course.optString("course_type").uppercase()



                val attended = course.optInt("classes_attended")



                val held = course.optInt("classes_held")



                val percentage = course.optDouble("percentage")







                val card = LinearLayout(activity).apply {



                    orientation = LinearLayout.VERTICAL



                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                        topMargin = (10 * density).toInt()



                    }



                    setBackgroundResource(R.drawable.bg_library_card)



                    setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())



                    isClickable = true



                    isFocusable = true



                    foreground = android.util.TypedValue().let { tv ->



                        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)



                        activity.getDrawable(tv.resourceId)



                    }



                    setOnClickListener {



                        com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



                        renderTable(course)



                    }



                }







                val topRow = LinearLayout(activity).apply {



                    orientation = LinearLayout.HORIZONTAL



                    gravity = android.view.Gravity.CENTER_VERTICAL



                }



                val tvType = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)



                    text = type



                    textSize = 10.5f



                    typeface = android.graphics.Typeface.DEFAULT_BOLD



                    setTextColor(activity.getColor(R.color.cm_text_secondary))



                }



                val tvStats = TextView(activity).apply {



                    text = "$attended/$held ($percentage%)"



                    textSize = 11f



                    typeface = android.graphics.Typeface.DEFAULT_BOLD



                    setTextColor(activity.getColor(if (percentage >= 75.0) R.color.cm_success else R.color.cm_warning))



                }



                topRow.addView(tvType)



                topRow.addView(tvStats)



                card.addView(topRow)







                val tvTitle = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                        topMargin = (6 * density).toInt()



                    }



                    text = title



                    textSize = 13.5f



                    typeface = android.graphics.Typeface.DEFAULT_BOLD



                    setTextColor(activity.getColor(R.color.cm_text_primary))



                }



                card.addView(tvTitle)







                val bottomRow = LinearLayout(activity).apply {



                    orientation = LinearLayout.HORIZONTAL



                    gravity = android.view.Gravity.CENTER_VERTICAL



                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {



                        topMargin = (6 * density).toInt()



                    }



                }



                val tvCode = TextView(activity).apply {



                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)



                    text = code



                    textSize = 11.5f



                    setTextColor(activity.getColor(R.color.cm_text_secondary))



                }



                val tvLog = TextView(activity).apply {



                    text = "View Daily Log ›"



                    textSize = 11.5f



                    typeface = android.graphics.Typeface.DEFAULT_BOLD



                    setTextColor(activity.getColor(R.color.cm_success))



                }



                bottomRow.addView(tvCode)



                bottomRow.addView(tvLog)



                card.addView(bottomRow)







                coursesContainer.addView(card)



            }



            parentView?.post {



                parentView.requestLayout()



                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED



            }



        }







        launch(dialogView) {



            try {



                val params = JSONObject().put("target_batch", batchId())



                val res = ClassMateAuthApi.rpc("student_my_attendance", params)



                val arr = res?.optJSONArray("courses") ?: JSONArray()



                val activeList = mutableListOf<JSONObject>()



                for (i in 0 until arr.length()) {



                    val c = arr.getJSONObject(i)



                    if (c.optInt("classes_held", 0) > 0) {



                        activeList.add(c)



                    }



                }



                if (active(dialogView)) {



                    populateCourseCards(activeList)



                    root.findViewById<TextView>(R.id.tvAttendanceHeroSub)?.text =



                        "${activeList.size} ${if (activeList.size == 1) "Course" else "Courses"}"



                }



            } catch (_: Exception) {



                if (active(dialogView)) {



                    populateCourseCards(emptyList())



                }



            }



        }







        bottomSheet.show()



    }







    private fun setupLibrary(root: View) {



        currentLibraryRoot = root



        val canUpload = profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr")



        root.v<View>(R.id.btnUploadPdf).apply {



            visibility = if (canUpload) View.VISIBLE else View.GONE



            setOnClickListener { onUpload() }



        }



        root.v<View>(R.id.cardAttendanceHero).setOnClickListener {



            showRfidAttendanceDialog(root)



        }



        root.v<View>(R.id.btnLibrarySearch).setOnClickListener {



            root.v<View>(R.id.titleColumn).visibility = View.GONE



            root.v<View>(R.id.btnLibrarySearch).visibility = View.GONE



            root.v<View>(R.id.btnUploadPdf).visibility = View.GONE



            root.v<View>(R.id.librarySearchContainer).visibility = View.VISIBLE



            val input = root.v<EditText>(R.id.etLibrarySearch)



            input.requestFocus()



            input.post {



                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as



                    android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)



            }



        }



        root.v<View>(R.id.btnCloseLibrarySearch).setOnClickListener {



            root.v<EditText>(R.id.etLibrarySearch).text.clear()



            root.v<View>(R.id.librarySearchContainer).visibility = View.GONE



            root.v<View>(R.id.titleColumn).visibility = View.VISIBLE



            root.v<View>(R.id.btnLibrarySearch).visibility = View.VISIBLE



            root.v<View>(R.id.btnUploadPdf).visibility = if (canUpload) View.VISIBLE else View.GONE



            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as



                android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)



            loadLibrary(root)



        }



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



        listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab).forEach { (value, id) ->



            root.v<View>(id).setOnClickListener {



                libraryCategory = value



                updateLibrarySegment(root, true)



                if (librarySnapshot?.batchId == batchId()) loadLibrary(root)



            }



        }



        root.v<View>(R.id.btnCategorySyllabus).visibility = View.GONE



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



        val width = (track.width - track.paddingLeft - track.paddingRight) / 2



        if (width <= 0) return



        indicator.layoutParams = indicator.layoutParams.apply { this.width = width }



        val index = if (libraryCategory == "lab") 1 else 0



        val target = (index * width).toFloat()



        indicator.animate().cancel()



        if (animate) indicator.animate().translationX(target).setDuration(180).start()



        else indicator.translationX = target



        listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab).forEach { (value, id) ->



            root.v<TextView>(id).setTextColor(activity.getColor(



                if (libraryCategory == value) R.color.cm_text_inverse else R.color.cm_text_secondary))



        }



    }







    private fun loadLibrary(root: View): Unit = launch(root) {



        if (librarySnapshot == null && libraryLoadingRoot === root) return@launch



        if (librarySnapshot == null) libraryLoadingRoot = root



        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)



        val shimmer = root.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmerView)



        val scroll = root.v<View>(R.id.nestedScrollView)



        val isFirst = librarySnapshot == null && !refresh.isRefreshing



        if (isFirst) {



            shimmer?.visibility = View.VISIBLE



            shimmer?.startShimmer()



            scroll.visibility = View.INVISIBLE



        }



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



                        "select=id,title,file_type,category,size_bytes,created_at,semester_course_id,link_url" +



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



                (query.isBlank() || file.optString("title").contains(query, true) || courseNames[file.optString("semester_course_id")].orEmpty().contains(query, true))



            }



            updateLibraryFilterChips(root)



            text(root, R.id.tvLibrarySubtitle,



                "${files.size} ${if (files.size == 1) "file" else "files"} this semester")



            root.v<View>(R.id.tvRecentEmpty).visibility = if (visibleFiles.isEmpty()) View.VISIBLE else View.GONE



            recycler(root, R.id.rvRecent, R.layout.item_recent_pdf, if (query.isBlank()) visibleFiles.take(3) else visibleFiles) { card, file ->



                bindLibraryFile(root, card, file, favoriteIds, courseNames)



            }



            val selected = offerings.mapNotNull { offering ->



                courses[offering.optString("course_id")]?.let { course -> offering to course }



            }.filter { it.second.optString("course_type") == selectedCategory && (query.isBlank() ||



                it.second.optString("course_title").contains(query, true) || it.second.optString("course_code").contains(query, true) ||



                visibleFiles.any { file -> file.optString("semester_course_id") == it.first.optString("id") }) }



            val items = selected.map { pair -> JSONObject(pair.second.toString())



                .put("offering_id", pair.first.getString("id")) }



            root.v<View>(R.id.tvNoSubjectResults).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE



            text(root, R.id.tvLibrarySummary, "${items.size} courses")



            listOf(R.id.rvRegular, R.id.rvLab, R.id.rvOther).forEach { root.v<View>(it).visibility = View.GONE }



            val listId = if (selectedCategory == "lab") R.id.rvLab else R.id.rvRegular



            root.v<View>(listId).visibility = View.VISIBLE



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



        } finally {



            refresh.isRefreshing = false



            if (isFirst && root.isAttachedToWindow) {



                shimmer?.stopShimmer()



                shimmer?.visibility = View.GONE



                scroll.visibility = View.VISIBLE



            }



            if (libraryLoadingRoot === root) libraryLoadingRoot = null



        }



    }







    private fun bindLibraryFile(root: View, card: View, file: JSONObject, favorites: Set<String>, courseNames: Map<String, String> = emptyMap()) {



        val fileId = file.getString("id")



        card.v<TextView>(R.id.tvTitle).text = ClassMateNoticeText.styled(card.v(R.id.tvTitle), file.optString("title"), root.v<EditText>(R.id.etLibrarySearch).text.toString().trim())



        val courseName = courseNames[file.optString("semester_course_id")].orEmpty()



        val tvSub = card.v<TextView>(R.id.tvSubject)



        if (courseName.isNotBlank()) {



            tvSub.visibility = View.VISIBLE



            tvSub.text = courseName



        } else {



            tvSub.visibility = View.GONE



        }



        val rawLink = file.optString("link_url").trim()



        val linkUrl = if (rawLink.isBlank() || rawLink.equals("null", ignoreCase = true)) "" else rawLink



        val isDrive = com.shuaib.classmate.utils.FileVisuals.isGoogleDrive(linkUrl)



        val isLink = file.optString("file_type") == "link" || linkUrl.isNotBlank()



        val metaText = when {



            isDrive -> "Google Drive"



            isLink -> "External Link"



            file.optLong("size_bytes") > 0 -> "${file.optLong("size_bytes") / 1024} KB"



            else -> "File"



        }



        text(card, R.id.tvFileMeta, metaText)



        val tempPdf = com.shuaib.classmate.models.PdfFile(



            id = fileId,



            title = file.optString("title"),



            fileType = file.optString("file_type").ifBlank { if (isLink) "link" else "pdf" },



            materialType = file.optString("category"),



            sizeBytes = file.optLong("size_bytes"),



            driveUrl = linkUrl



        )



        val visuals = com.shuaib.classmate.utils.FileVisuals.getVisuals(tempPdf)



        com.shuaib.classmate.utils.FileVisuals.applyVisuals(



            card.findViewById<android.widget.ImageView>(R.id.ivFileIcon),



            card.findViewById<View>(R.id.iconContainer),



            visuals



        )



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



    private var highlightedNoticeId: String? = null







    private fun highlightNoticeCard(card: View) {



        val cardView = card.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardRoot)



            ?: card as? com.google.android.material.card.MaterialCardView ?: return



        cardView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(350)



            .setInterpolator(android.view.animation.DecelerateInterpolator())



            .withEndAction {



                cardView.animate().scaleX(1f).scaleY(1f).setDuration(350)



                    .setInterpolator(android.view.animation.DecelerateInterpolator())



                    .start()



            }.start()







        val primaryColor = activity.getColor(R.color.cm_primary)



        val originalStrokeColor = cardView.strokeColorStateList?.defaultColor ?: android.graphics.Color.TRANSPARENT



        val originalStrokeWidth = cardView.strokeWidth







        android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {



            duration = 2000



            addUpdateListener { animator ->



                val fraction = animator.animatedValue as Float



                val blended = androidx.core.graphics.ColorUtils.blendARGB(originalStrokeColor, primaryColor, fraction)



                cardView.setStrokeColor(android.content.res.ColorStateList.valueOf(blended))



                cardView.strokeWidth = (originalStrokeWidth.coerceAtLeast(2) * (1f + fraction)).toInt()



            }



            addListener(object : android.animation.AnimatorListenerAdapter() {



                override fun onAnimationEnd(animation: android.animation.Animator) {



                    cardView.setStrokeColor(android.content.res.ColorStateList.valueOf(originalStrokeColor))



                    cardView.strokeWidth = originalStrokeWidth



                    highlightedNoticeId = null



                }



            })



            start()



        }



    }







    private fun shareNotice(item: JSONObject) {



        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return



        val title = item.optString("title").takeIf { it.isNotBlank() } ?: "Notice"



        val shareUrl = "https://classmatebd.vercel.app/n/$id"



        val shareText = "$title\n$shareUrl"



        val sendIntent = Intent().apply {



            action = Intent.ACTION_SEND



            putExtra(Intent.EXTRA_TEXT, shareText)



            putExtra(Intent.EXTRA_SUBJECT, title)



            setType("text/plain")



        }



        val chooser = Intent.createChooser(sendIntent, "Share Notice")



        activity.startActivity(chooser)



    }







    fun openDirectNotice(noticeId: String) = launch(activity.window.decorView) {



        val cleanId = noticeId.trim().lowercase()



        if (cleanId.isBlank()) return@launch







        val authAct = activity as? ClassMateAuthActivity



        authAct?.selectHomeTab(R.id.nav_notices)







        expandedNoticeIds.add(cleanId)



        highlightedNoticeId = cleanId







        val existing = noticeFeed.any { it.optString("id").equals(cleanId, ignoreCase = true) }



        if (!existing) {



            val fetched = runCatching {



                ClassMateAuthApi.rpc("get_public_notice", JSONObject().put("target_identifier", cleanId))



            }.getOrNull()



            val data = fetched?.optJSONObject("data") ?: fetched



            if (data != null && data.has("id")) {



                noticeFeed = (listOf(data) + noticeFeed).distinctBy { it.optString("id") }



            }



        }







        val root = tabRoots[R.id.nav_notices]



        if (root != null) {



            renderNoticeFeed(root)



            val rv = root.findViewById<RecyclerView>(R.id.rvNotices)



            val adapter = rv?.adapter as? ClassMateNoticeRows



            val targetPos = adapter?.currentList?.indexOfFirst { it.key == "notice:$cleanId" } ?: -1



            if (targetPos >= 0 && rv != null) {



                rv.postDelayed({



                    rv.smoothScrollToPosition(targetPos)



                }, 200)



            }



        }



    }







    fun openDirectResource(resourceId: String) = launch(activity.window.decorView) {



        val meta = runCatching {



            ClassMateAuthApi.rpc("get_public_resource", JSONObject().put("target_identifier", resourceId))



        }.getOrNull()



        val data = meta?.optJSONObject("data") ?: meta



        val courseTitle = data?.optString("course_title").orEmpty()



        val semesterCourseId = data?.optString("semester_course_id").orEmpty()



        val courseCode = data?.optString("course_code").orEmpty()



        val fileBatchId = data?.optString("batch_id").orEmpty().ifBlank { "de0b3379-54a0-4275-a526-5970295c787e" }



        val displayTitle = if (courseTitle.isNotBlank()) {



            if (courseCode.isNotBlank()) "$courseTitle ($courseCode)" else courseTitle



        } else {



            "Course Files"



        }



        val canManage = profile().optString("role") in setOf("admin", "teacher") || profile().optBoolean("is_cr")



        val intent = Intent(activity, ClassMateCourseFilesActivity::class.java).apply {



            putExtra("course_id", semesterCourseId)



            putExtra("courseId", semesterCourseId)



            putExtra("course_name", displayTitle)



            putExtra("courseName", displayTitle)



            putExtra("batch_id", fileBatchId)



            putExtra("can_manage", canManage)



            putExtra("canManage", canManage)



            putExtra("role", profile().optString("role"))



            putExtra("is_cr", profile().optBoolean("is_cr"))



        }



        activity.startActivity(intent)



    }







    fun openFile(file: JSONObject) = launch(activity.window.decorView) {



        val fileId = file.optString("id").trim()



        var rawLink = file.optString("link_url").trim()



        if ((rawLink.isBlank() || rawLink.equals("null", ignoreCase = true)) && fileId.isNotBlank()) {



            val meta = runCatching {



                ClassMateAuthApi.rpc("get_public_resource", JSONObject().put("target_identifier", fileId))



            }.getOrNull()



            val data = meta?.optJSONObject("data") ?: meta



            rawLink = data?.optString("link_url").orEmpty().trim()



        }



        val linkUrl = if (rawLink.isBlank() || rawLink.equals("null", ignoreCase = true)) "" else rawLink



        if (linkUrl.isNotBlank()) {



            val validUrl = if (linkUrl.startsWith("http://", true) || linkUrl.startsWith("https://", true)) linkUrl else "https://$linkUrl"



            val uri = Uri.parse(validUrl)



            val opened = runCatching {



                CustomTabsIntent.Builder()



                    .setShowTitle(true)



                    .build()



                    .apply {



                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)



                    }



                    .launchUrl(activity, uri)



                true



            }.getOrElse { false }



            if (!opened) {



                runCatching {



                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {



                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)



                    })



                }.onFailure {



                    Toast.makeText(activity, "Unable to open link: ${it.message}", Toast.LENGTH_SHORT).show()



                }



            }



            return@launch



        }



        val url = ClassMateAuthApi.signedResource(file.getString("id"))



        if (url.isNotBlank()) {



            val uri = Uri.parse(url)



            val opened = runCatching {



                CustomTabsIntent.Builder()



                    .setShowTitle(true)



                    .build()



                    .apply {



                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)



                    }



                    .launchUrl(activity, uri)



                true



            }.getOrElse { false }



            if (!opened) {



                runCatching {



                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {



                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)



                    })



                }.onFailure {



                    Toast.makeText(activity, "Unable to open file: ${it.message}", Toast.LENGTH_SHORT).show()



                }



            }



        }



    }







    private fun setupProfile(root: View) {



        val account = profile()



        val role = account.optString("role")



        val isCr = account.optBoolean("is_cr")



        val isAdmin = role == "admin" || account.optBoolean("is_superadmin")



        val isTeacher = role == "teacher"



        val prefs = appPrefs







        root.findViewById<SwipeRefreshLayout>(R.id.swipeProfile)?.apply {



            setColorSchemeResources(R.color.cm_primary)



            setOnRefreshListener {



                refreshProfile(root, isPullToRefresh = true)



            }



        }







        // Top-Right Profile Launcher Button



        val photo = account.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }



            ?: ownGoogleAvatar



        val avatarView = root.findViewById<ImageView>(R.id.ivHeaderProfileAvatar)



        if (avatarView != null) {



            if (!photo.isNullOrBlank()) {



                com.bumptech.glide.Glide.with(activity)



                    .load(photo)



                    .placeholder(R.drawable.ic_default_avatar)



                    .into(avatarView)



            } else {



                avatarView.setImageResource(R.drawable.ic_default_avatar)



            }



        }



        root.findViewById<View>(R.id.btnHeaderProfile)?.setOnClickListener {



            val intent = Intent(activity, ClassMateProfileActivity::class.java)



            activity.startActivity(intent)



        }







        val effectiveBatch = batchLabel().trim().ifBlank {



            ClassMateAcademicCache.home(activity)?.optString("label")?.trim().orEmpty()



        }







        // ACCOUNT & MANAGEMENT Section



        root.v<View>(R.id.btnEditPersonal).setOnClickListener { onEditProfile() }







        val cachedServerOwner = account.optBoolean("is_server_owner")



        val initialCanSwitch = cachedServerOwner || isTeacher || isAdmin



        val initialCanManage = initialCanSwitch || isCr







        root.v<View>(R.id.btnSwitchBatch).apply {



            text(root, R.id.tvSwitchBatchTitle, if (initialCanSwitch) "Switch Active Batch" else "Your Academic Batch")



            text(root, R.id.tvSwitchBatchSub, if (initialCanSwitch) "Active workspace and notice scope" else "Your enrolled academic batch")



            text(root, R.id.tvSwitchBatchBadge, effectiveBatch.ifBlank { "MBSTU" })



            root.v<View>(R.id.ivSwitchBatchChevron).visibility = if (initialCanSwitch) View.VISIBLE else View.GONE



            if (initialCanSwitch) {



                isClickable = true



                isFocusable = true



                val typedValue = android.util.TypedValue()



                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)



                setBackgroundResource(typedValue.resourceId)



                setOnClickListener { onSwitchBatch() }



            } else {



                setOnClickListener(null)



                isClickable = false



                isFocusable = false



                background = null



            }



        }







        val showBatchOption = batchId().isNotBlank() && account.optString("verification_status") == "active"

        root.v<View>(R.id.cardProfileBatch).apply {

            visibility = if (showBatchOption) View.VISIBLE else View.GONE

            root.v<View>(R.id.dividerProfileBatch).visibility = visibility



            text(root, R.id.tvBatchDirectoryTitle, if (isTeacher) "Students Directory" else "Batch & Classmates")

            text(root, R.id.tvBatchDirectorySub, if (isTeacher) "Department student roster and profiles" else "Classmates, contacts, and Batch Fund")



            if (showBatchOption) {

                setOnClickListener {

                    com.shuaib.classmate.ui.ClassMateHaptics.selection(it)

                    val intent = Intent(activity, ClassMateBatchActivity::class.java).apply {

                        putExtra("EXTRA_BATCH_ID", batchId())

                        putExtra("EXTRA_BATCH_LABEL", effectiveBatch)

                        putExtra("EXTRA_ROLE", account.optString("role"))

                    }

                    activity.startActivity(intent)

                }

            } else {

                setOnClickListener(null)

            }

        }



        root.v<View>(R.id.cardProfileManage).apply {



            visibility = if (initialCanManage) View.VISIBLE else View.GONE



            root.v<View>(R.id.dividerProfileManage).visibility = visibility



            text(root, R.id.tvBatchAdminTitle, if (isTeacher) "Teaching Tools" else "Batch Administration")



            text(root, R.id.tvBatchAdminSub, if (isTeacher) "Assigned courses and teaching resources" else "Manage timetable, members and batch settings")



            if (initialCanManage) {



                setOnClickListener { com.shuaib.classmate.ui.ClassMateHaptics.selection(it); onManage() }



            } else {



                setOnClickListener(null)



            }



        }







        val initialCanManageAttendance = isAdmin || cachedServerOwner



        root.v<View>(R.id.cardProfileAttendance).apply {



            visibility = if (initialCanManageAttendance) View.VISIBLE else View.GONE



            root.v<View>(R.id.dividerProfileAttendance).visibility = visibility



            if (initialCanManageAttendance) {



                setOnClickListener {



                    com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



                    val intent = Intent(activity, ClassMateAdminAttendanceActivity::class.java).apply {



                        putExtra("EXTRA_BATCH_ID", batchId())



                        putExtra("EXTRA_BATCH_LABEL", effectiveBatch)



                    }



                    activity.startActivity(intent)



                }



            } else {



                setOnClickListener(null)



            }



        }















        // Verify global owner status and batch management permissions with server RPC



        scope.launch {



            try {



                val isServerOwner = ClassMateAuthApi.rpcText("is_owner", JSONObject()).trim() == "true"



                account.put("is_server_owner", isServerOwner)



                if (active(root)) {



                    val canSwitch = isServerOwner || isTeacher || isAdmin



                    val canManage = isServerOwner || isTeacher || isCr || isAdmin







                    text(root, R.id.tvSwitchBatchTitle, if (canSwitch) "Switch Active Batch" else "Your Academic Batch")



                    text(root, R.id.tvSwitchBatchSub, if (canSwitch) "Active workspace and notice scope" else "Your enrolled academic batch")



                    root.v<View>(R.id.ivSwitchBatchChevron).visibility = if (canSwitch) View.VISIBLE else View.GONE



                    root.v<View>(R.id.btnSwitchBatch).apply {



                        if (canSwitch) {



                            isClickable = true



                            isFocusable = true



                            val typedValue = android.util.TypedValue()



                            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)



                            setBackgroundResource(typedValue.resourceId)



                            setOnClickListener { onSwitchBatch() }



                        } else {



                            setOnClickListener(null)



                            isClickable = false



                            isFocusable = false



                            background = null



                        }



                    }







                    root.v<View>(R.id.cardProfileManage).apply {



                        visibility = if (canManage) View.VISIBLE else View.GONE



                        root.v<View>(R.id.dividerProfileManage).visibility = visibility



                        text(root, R.id.tvBatchAdminTitle, if (isTeacher) "Teaching Tools" else "Batch Administration")



                        text(root, R.id.tvBatchAdminSub, if (isTeacher) "Assigned courses and teaching resources" else "Manage timetable, members and batch settings")



                        if (canManage) {



                            setOnClickListener { com.shuaib.classmate.ui.ClassMateHaptics.selection(it); onManage() }



                        } else {



                            setOnClickListener(null)



                        }



                    }







                    val canManageAttendance = isAdmin || isServerOwner



                    root.v<View>(R.id.cardProfileAttendance).apply {



                        visibility = if (canManageAttendance) View.VISIBLE else View.GONE



                        root.v<View>(R.id.dividerProfileAttendance).visibility = visibility



                        if (canManageAttendance) {



                            setOnClickListener {



                                com.shuaib.classmate.ui.ClassMateHaptics.selection(it)



                                val intent = Intent(activity, ClassMateAdminAttendanceActivity::class.java).apply {



                                    putExtra("EXTRA_BATCH_ID", batchId())



                                    putExtra("EXTRA_BATCH_LABEL", effectiveBatch)



                                }



                                activity.startActivity(intent)



                            }



                        } else {



                            setOnClickListener(null)



                        }



                    }











                }



            } catch (_: Exception) {}



        }







        // PREFERENCES & APPEARANCE Section



        val btnLight = root.v<View>(R.id.btnThemeLight)



        val btnDark = root.v<View>(R.id.btnThemeDark)



        val btnSystem = root.v<View>(R.id.btnThemeSystem)



        val tvSub = root.v<TextView>(R.id.tvThemeAppearanceSub)







        fun updateThemeControls(mode: String) {



            val isLight = mode == "light"



            val isDark = mode == "dark"



            val isSys = mode == "system"







            btnLight.setBackgroundResource(if (isLight) R.drawable.bg_segmented_item_active else android.R.color.transparent)



            root.v<ImageView>(R.id.ivThemeLightIcon).imageTintList = android.content.res.ColorStateList.valueOf(



                activity.getColor(if (isLight) R.color.cm_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeLightText).setTextColor(



                activity.getColor(if (isLight) R.color.cm_text_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeLightText).typeface = if (isLight) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT







            btnDark.setBackgroundResource(if (isDark) R.drawable.bg_segmented_item_active else android.R.color.transparent)



            root.v<ImageView>(R.id.ivThemeDarkIcon).imageTintList = android.content.res.ColorStateList.valueOf(



                activity.getColor(if (isDark) R.color.cm_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeDarkText).setTextColor(



                activity.getColor(if (isDark) R.color.cm_text_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeDarkText).typeface = if (isDark) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT







            btnSystem.setBackgroundResource(if (isSys) R.drawable.bg_segmented_item_active else android.R.color.transparent)



            root.v<ImageView>(R.id.ivThemeSystemIcon).imageTintList = android.content.res.ColorStateList.valueOf(



                activity.getColor(if (isSys) R.color.cm_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeSystemText).setTextColor(



                activity.getColor(if (isSys) R.color.cm_text_primary else R.color.cm_text_secondary))



            root.v<TextView>(R.id.tvThemeSystemText).typeface = if (isSys) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT







            tvSub.text = when (mode) {



                "light" -> "Clean light palette"



                "dark" -> "Comfortable dark palette"



                else -> "Following device system settings"



            }







            root.findViewById<ImageView>(R.id.ivThemeIcon)?.setImageResource(



                when (mode) {



                    "light" -> R.drawable.ic_sun



                    "dark" -> R.drawable.ic_dark_mode



                    else -> R.drawable.ic_monitor



                }



            )



        }







        updateThemeControls(prefs.getThemeMode())







        btnLight.setOnClickListener {



            prefs.setThemeMode("light")



            updateThemeControls("light")



            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)



        }



        btnDark.setOnClickListener {



            prefs.setThemeMode("dark")



            updateThemeControls("dark")



            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)



        }



        btnSystem.setOnClickListener {



            prefs.setThemeMode("system")



            updateThemeControls("system")



            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)



        }







        // Notice Read Receipts



        root.v<View>(R.id.rowReadReceipts).apply {



            visibility = if (isAdmin) View.VISIBLE else View.GONE



            root.v<View>(R.id.dividerReadReceipts).visibility = visibility



            if (isAdmin) {



                val switch = root.v<SwitchCompat>(R.id.switchReadReceipts)



                val status = root.v<TextView>(R.id.tvReadReceiptsStatus)



                switch.isChecked = prefs.isNoticeReadReceiptsEnabled()



                status.text = if (switch.isChecked) "Recording your reads on notices" else "Silent mode — your reads are not recorded"



                scope.launch {



                    runCatching {



                        val prefsJson = ClassMateAuthApi.rpc("owner_preferences", JSONObject())



                        if (active(root) && prefsJson.has("read_receipts_enabled")) {



                            val enabled = prefsJson.optBoolean("read_receipts_enabled", true)



                            prefs.setNoticeReadReceiptsEnabled(enabled)



                            switch.isChecked = enabled



                            status.text = if (enabled) "Recording your reads on notices" else "Silent mode — your reads are not recorded"



                        }



                    }



                }



                switch.setOnCheckedChangeListener { _, checked ->



                    prefs.setNoticeReadReceiptsEnabled(checked)



                    status.text = if (checked) "Recording your reads on notices" else "Silent mode — your reads are not recorded"



                    scope.launch {



                        runCatching {



                            ClassMateAuthApi.rpc("save_owner_preferences", JSONObject().put("target_read_receipts", checked))



                        }



                    }



                }



            }



        }







        // NOTIFICATIONS & CONNECTIVITY Section



        root.v<SwitchCompat>(R.id.switchNotifications).apply {



            (parent as? View)?.visibility = if (isTeacher) View.GONE else View.VISIBLE



            val channelBlocked = android.os.Build.VERSION.SDK_INT >= 26 && activity.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("classmate_notifications")?.importance == android.app.NotificationManager.IMPORTANCE_NONE



            val isNotifActive = prefs.isNotificationsEnabled() && androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled() && !channelBlocked



            isChecked = isNotifActive



            val pill = root.v<TextView>(R.id.tvPushStatusPill)



            pill.text = if (isNotifActive) "ACTIVE" else "DISABLED"



            pill.setTextColor(activity.getColor(if (isNotifActive) R.color.cm_success else R.color.cm_text_muted))



            pill.setBackgroundResource(if (isNotifActive) R.drawable.bg_status_pill_active else R.drawable.bg_status_pill_inactive)



            if (prefs.isNotificationsEnabled() && !isChecked) {



                root.v<TextView>(R.id.tvNotificationStatus).text = "Blocked in Android settings. Tap to enable."



            }



            setOnCheckedChangeListener { _, checked ->



                if (checked) {



                    onEnableNotifications()



                    return@setOnCheckedChangeListener



                }



                prefs.setNotificationsEnabled(checked)



                pill.text = "DISABLED"



                pill.setTextColor(activity.getColor(R.color.cm_text_muted))



                pill.setBackgroundResource(R.drawable.bg_status_pill_inactive)



                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->



                    scope.launch {



                        runCatching {



                            if (checked) ClassMateAuthApi.registerDeviceToken(token)



                            else ClassMateAuthApi.unregisterDeviceToken(token)



                        }



                    }



                }



            }



        }



        root.v<View>(R.id.tvNotificationStatus).setOnClickListener { onEnableNotifications() }







        // UTILITIES & STORAGE Section



        val r2StorageRow = root.findViewById<View>(R.id.layoutR2Storage)



        val r2StorageDivider = root.findViewById<View>(R.id.dividerR2Storage)



        if (r2StorageRow != null) {



            val canViewR2 = initialCanManage || isAdmin || isCr || isTeacher



            r2StorageRow.visibility = if (canViewR2) View.VISIBLE else View.GONE



            r2StorageDivider?.visibility = if (canViewR2) View.VISIBLE else View.GONE



            if (canViewR2) {



                r2StorageRow.setOnClickListener {



                    com.shuaib.classmate.ui.R2StorageDialog.show(activity)



                }



            }



        }







        fun calcStorageBytes(): Long {



            var total = 0L



            activity.cacheDir.listFiles()?.forEach { total += it.length() }



            return total



        }



        fun refreshStorageText() {



            val mb = calcStorageBytes().toDouble() / (1024 * 1024)



            val formattedMb = String.format(java.util.Locale.US, "%.1f", maxOf(mb, 0.0))



            text(root, R.id.tvCacheStorageSubtitle, "Local timetable, notices, and offline resources ($formattedMb MB cached)")



        }



        refreshStorageText()







        root.v<View>(R.id.btnClearCache).setOnClickListener {



            AlertDialog.Builder(activity).setTitle("Clear offline cache")



                .setMessage("Remove temporary timetable, notices, and cached resources from this device?")



                .setPositiveButton("Clear") { _, _ ->



                    ClassMateAcademicCache.clear(activity)



                    noticeFeed = emptyList(); noticeBatch = ""; noticeStates.clear(); noticeAuthors.clear(); noticeReadCounts.clear(); noticeReaderPreviews.clear()



                    activity.cacheDir.listFiles()?.forEach { file -> file.deleteRecursively() }



                    Toast.makeText(activity, "Offline cache cleared", Toast.LENGTH_SHORT).show()



                    refreshStorageText()



                }.setNegativeButton("Cancel", null).show()



        }







        // SYSTEM & UPDATES Section



        text(root, R.id.tvAppVersion, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")



        root.v<View>(R.id.layoutCheckUpdates).setOnClickListener {



            activity.startActivity(Intent(activity, UpdateActionActivity::class.java)



                .setAction(UpdateActionActivity.ACTION_RETRY))



        }







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







        root.v<View>(R.id.cardAboutDeveloper).setOnClickListener { ClassMateFeatureUi.developer(activity) }







        // Sign Out Button



        root.v<View>(R.id.btnLogout).setOnClickListener { onSignOut() }



    }







    private fun refreshProfile(root: View, isPullToRefresh: Boolean = false) {



        val swipe = root.findViewById<SwipeRefreshLayout>(R.id.swipeProfile)



        if (isPullToRefresh) {



            swipe?.isRefreshing = true



        }



        val authActivity = activity as? ClassMateAuthActivity



        if (authActivity != null) {



            authActivity.refreshProfileFromScreen { _ ->



                if (root.isAttachedToWindow) {



                    swipe?.isRefreshing = false



                    setupProfile(root)



                }



            }



        } else {



            swipe?.isRefreshing = false



        }



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



                    "${it.optInt("batch_number")} · session ${ClassMateAcademicSession.format(it.optInt("academic_session"))}" } ?: "Unknown"



        val semesters = if (batchId().isBlank()) JSONArray() else ClassMateAuthApi.rows("semesters",



            "select=semester_number&batch_id=eq.${batchId()}&status=eq.active&limit=1")



        if (!root.isAttachedToWindow) return@launch



        val isTeacher = account.optString("role") == "teacher"



        val details = if (isTeacher) {



            listOf(



                "Email: ${account.optString("email")}",



                "Role: Teacher",



                "Verification: ${account.optString("verification_status")}",



            ).joinToString("\n")



        } else {



            listOf(



                "Email: ${account.optString("email")}",



                "Role: ${account.optString("role")}",



                "Department: $department",



                "Assigned batch: $batch",



                "Current semester: ${semesters.optJSONObject(0)?.optInt("semester_number") ?: "None"}",



                "CR: ${if (account.optBoolean("is_cr")) "Yes" else "No"}",



                "Verification: ${account.optString("verification_status")}",



            ).joinToString("\n")



        }



        AlertDialog.Builder(activity).setTitle("Profile details").setMessage(details)



            .setPositiveButton("Close", null)



            .apply {



                if (account.optString("role") in setOf("admin", "teacher"))



                    setNeutralButton("Switch batch") { _, _ -> onSwitchBatch() }



            }.show()



    }





    fun pauseTransitWebView() {
        tabRoots[R.id.nav_transit]?.findViewById<android.webkit.WebView>(R.id.transit_webview)?.apply {
            onPause()
            pauseTimers()
        }
    }

    fun resumeTransitWebView() {
        tabRoots[R.id.nav_transit]?.findViewById<android.webkit.WebView>(R.id.transit_webview)?.apply {
            onResume()
            resumeTimers()
        }
    }

    fun onPause() {
        stopNoticePoller()
        pauseTransitWebView()
    }

}
