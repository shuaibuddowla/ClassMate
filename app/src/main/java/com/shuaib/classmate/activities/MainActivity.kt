// C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/MainActivity.kt
package com.shuaib.classmate.activities

import android.Manifest
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.ChatNotificationHelper
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.databinding.ActivityMainBinding
import com.shuaib.classmate.fragments.FriendsFragment
import com.shuaib.classmate.fragments.NoticeFragment
import com.shuaib.classmate.fragments.PdfLibraryFragment
import com.shuaib.classmate.fragments.ProfileFragment
import com.shuaib.classmate.fragments.TimetableFragment
import com.shuaib.classmate.models.User
import com.shuaib.classmate.notices.NoticeReminderManager
import com.shuaib.classmate.ui.BottomNavAnimator
import com.shuaib.classmate.utils.HapticHelper
import com.shuaib.classmate.utils.NoticeReadTracker
import com.shuaib.classmate.utils.NotificationRouter
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.WidgetUpdater
import com.shuaib.classmate.utils.AppUpdateManager
import com.shuaib.classmate.workers.TimetableResetWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import android.hardware.Sensor
import android.hardware.SensorManager
import android.content.Context
import com.shuaib.classmate.utils.ShakeDetector
import com.shuaib.classmate.utils.TorchManager
import com.shuaib.classmate.utils.AppPreferences

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var navController: NavController
    private lateinit var appPrefs: AppPreferences

    private val mainTabs = listOf(
        R.id.nav_timetable,
        R.id.nav_notices,
        R.id.nav_chat,
        R.id.nav_pdf,
        R.id.nav_profile
    )
    private var isAdmin = false
    var isViewingRoutineInTimetable = true
    private var noticeBadgeListener: ListenerRegistration? = null
    private val startupHandler = Handler(Looper.getMainLooper())
    private var firstResumeHandled = false
    private var updatingBottomSelection = false
    private var bottomSystemInset = 0
    private var statusBarHeight = 0
    private var bottomChromeAllowed = true
    private var bottomChromeHiddenByScroll = false
    private var activeScrollRoot: View? = null
    private var childParentTabId: Int? = null
    private var resettingChildBackStack = false
    private val attachedRecyclerScrollListeners = mutableMapOf<RecyclerView, RecyclerView.OnScrollListener>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            showPermissionRationaleDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        applySystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBars()
        binding.starfield.isVisible = ThemeColors.isDark(this)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        appPrefs = AppPreferences(this)
        if (appPrefs.isShakeToTorchEnabled()) {
            val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasCameraPermission) {
                com.shuaib.classmate.services.ShakeToTorchService.start(this)
            }
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        if (navHostFragment != null) {
            navController = navHostFragment.navController
            setupChildNavigationOverlay()
        } else {
            Log.e("MainActivity", "NavHostFragment not found!")
            finish()
            return
        }

        setupViewPager()
        applyRootInsets()
        applyBottomNavInsets()

        binding.fabGlobalAdd.setOnClickListener {
            val currentTabId = if (binding.mainViewPager.isVisible) {
                mainTabs.getOrNull(binding.mainViewPager.currentItem)
            } else {
                navController.currentDestination?.id
            }

            if (currentTabId == R.id.nav_timetable) {
                if (isViewingRoutineInTimetable) {
                    startActivity(Intent(this, TimetableManagementActivity::class.java))
                } else {
                    startActivity(Intent(this, BusScheduleManagementActivity::class.java))
                }
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (updatingBottomSelection) return@setOnItemSelectedListener true
            val index = mainTabs.indexOf(item.itemId)
            if (index != -1) {
                showMainTab(item.itemId)
            }
            true
        }

        handleNotificationIntent(intent)
        observeChatUnreadBadge()
        deferStartupWork()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.navHostFragment.isVisible) {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        binding.navHostFragment.isVisible = false
                        binding.mainViewPager.isVisible = true
                        showMainTab(childParentTabId ?: R.id.nav_timetable)
                    }
                } else {
                    val currentTabId = mainTabs.getOrNull(binding.mainViewPager.currentItem)
                    if (currentTabId != R.id.nav_timetable) {
                        showMainTab(R.id.nav_timetable)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    fun setMainPageSwipeEnabled(enabled: Boolean) {
        binding.mainViewPager.isUserInputEnabled = enabled
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.mainViewPager.adapter = adapter
        binding.mainViewPager.offscreenPageLimit = 3 // Keep notices and chat alive

        binding.mainViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tabId = mainTabs[position]
                if (!updatingBottomSelection) {
                    HapticHelper.lightPop(binding.bottomNav)
                }
                updatingBottomSelection = true
                binding.bottomNav.selectedItemId = tabId
                updatingBottomSelection = false

                updateBottomNavItemStates(tabId)
                updateBottomChromeVisibility(tabId)
                setMainPageSwipeEnabled(true)

                if (tabId == R.id.nav_notices) {
                    NoticeReadTracker.markReadNow(this@MainActivity)
                    updateNoticeBadge(0)
                } else {
                    refreshUnreadNoticeBadge()
                }
                applySystemBars()
            }
        })
    }

    private fun setupChildNavigationOverlay() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val destinationId = destination.id
            if (resettingChildBackStack) return@addOnDestinationChangedListener
            if (destinationId in MAIN_TAB_DESTINATIONS) {
                val tabId = childParentTabId ?: destinationId
                childParentTabId = null
                if (binding.navHostFragment.isVisible) {
                    binding.navHostFragment.isVisible = false
                    binding.mainViewPager.isVisible = true
                    showMainTab(tabId)
                }
                return@addOnDestinationChangedListener
            }

            val tabId = TAB_DESTINATION_MAP[destinationId] ?: childParentTabId
            updateBottomNavSelection(tabId ?: destinationId)
            updateBottomChromeVisibility(destinationId)
            applySystemBars()
        }
    }

    fun showMainTab(tabId: Int) {
        val index = mainTabs.indexOf(tabId)
        if (index == -1) return
        childParentTabId = null
        binding.navHostFragment.isVisible = false
        binding.mainViewPager.isVisible = true
        if (binding.mainViewPager.currentItem != index) {
            binding.mainViewPager.currentItem = index
        }
        updateBottomNavSelection(tabId)
        updateBottomNavItemStates(tabId)
        updateBottomChromeVisibility(tabId)
    }

    fun openChildDestination(parentTabId: Int, destinationId: Int, args: Bundle? = null) {
        val parentIndex = mainTabs.indexOf(parentTabId)
        if (parentIndex != -1 && binding.mainViewPager.currentItem != parentIndex) {
            binding.mainViewPager.currentItem = parentIndex
        }
        childParentTabId = parentTabId
        binding.mainViewPager.isVisible = false
        binding.navHostFragment.isVisible = true
        updateBottomNavSelection(parentTabId)
        updateBottomChromeVisibility(destinationId)

        runCatching {
            resettingChildBackStack = true
            try {
                navController.popBackStack(navController.graph.startDestinationId, false)
            } finally {
                resettingChildBackStack = false
            }
            navController.navigate(destinationId, args)
        }.onFailure {
            Log.e("MainActivity", "Child navigation failed to $destinationId", it)
            showMainTab(parentTabId)
        }
    }

    private inner class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = mainTabs.size
        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            return when (mainTabs[position]) {
                R.id.nav_timetable -> TimetableFragment()
                R.id.nav_notices -> NoticeFragment()
                R.id.nav_chat -> com.shuaib.classmate.chat.ChatFragment()
                R.id.nav_pdf -> PdfLibraryFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> TimetableFragment()
            }
        }
    }

    private fun getCurrentDestinationId(): Int {
        return if (binding.mainViewPager.isVisible) {
            mainTabs.getOrNull(binding.mainViewPager.currentItem) ?: -1
        } else {
            navController.currentDestination?.id ?: -1
        }
    }

    private fun checkAdminStatus() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val user = doc.toObject(User::class.java)
                isAdmin = user?.isAdmin() ?: false
                updateFabVisibility(getCurrentDestinationId())
            }
            .addOnFailureListener {
                // If offline or error, we default to student role which is safe
                isAdmin = false
                updateFabVisibility(getCurrentDestinationId())
            }
    }

    private fun updateFabVisibility(destinationId: Int) {
        // Show ONLY in timetable page and ONLY for admins, hide for notice, library, profile, and chat
        binding.fabGlobalAdd.isVisible = isAdmin && destinationId == R.id.nav_timetable
    }

    private fun updateBottomChromeVisibility(destinationId: Int) {
        bottomChromeAllowed = destinationId in MAIN_TAB_DESTINATIONS && destinationId != R.id.nav_chat
        bottomChromeHiddenByScroll = false
        clearBottomChromeScrollBehavior()
        binding.bottomNav.animate().cancel()
        binding.fabGlobalAdd.animate().cancel()
        binding.bottomNav.translationY = 0f
        binding.fabGlobalAdd.translationY = 0f
        binding.bottomNav.alpha = 1f
        binding.fabGlobalAdd.alpha = 1f
        binding.bottomNav.isVisible = bottomChromeAllowed
        if (bottomChromeAllowed) {
            updateFabVisibility(destinationId)
            attachBottomChromeScrollBehavior(destinationId)
        } else {
            binding.fabGlobalAdd.isVisible = false
        }
    }

    private fun attachBottomChromeScrollBehavior(destinationId: Int) {
        if (destinationId !in MAIN_TAB_DESTINATIONS) return
        val pageIndex = mainTabs.indexOf(destinationId)
        if (pageIndex == -1) return

        binding.mainViewPager.post {
            if (!bottomChromeAllowed ||
                !binding.mainViewPager.isVisible ||
                binding.mainViewPager.currentItem != pageIndex
            ) return@post

            val root = currentMainPagerFragmentView(pageIndex)
            if (root == null) {
                binding.mainViewPager.postDelayed({
                    if (!bottomChromeAllowed ||
                        !binding.mainViewPager.isVisible ||
                        binding.mainViewPager.currentItem != pageIndex
                    ) return@postDelayed

                    currentMainPagerFragmentView(pageIndex)?.let { retryRoot ->
                        activeScrollRoot = retryRoot
                        attachScrollCallbacks(retryRoot)
                    }
                }, 120L)
                return@post
            }

            activeScrollRoot = root
            attachScrollCallbacks(root)
        }
    }

    private fun currentMainPagerFragmentView(pageIndex: Int): View? {
        val itemId = binding.mainViewPager.adapter?.getItemId(pageIndex) ?: pageIndex.toLong()
        return supportFragmentManager.findFragmentByTag("f$itemId")?.view
    }

    private fun clearBottomChromeScrollBehavior() {
        activeScrollRoot?.let { clearNestedScrollCallbacks(it) }
        attachedRecyclerScrollListeners.forEach { (recyclerView, listener) ->
            recyclerView.removeOnScrollListener(listener)
        }
        attachedRecyclerScrollListeners.clear()
        activeScrollRoot = null
    }

    private fun attachScrollCallbacks(view: View) {
        when (view) {
            is RecyclerView -> {
                if (attachedRecyclerScrollListeners.containsKey(view)) return
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        handleBottomChromeScrollDelta(dy)
                    }
                }
                view.addOnScrollListener(listener)
                attachedRecyclerScrollListeners[view] = listener
            }
            is androidx.core.widget.NestedScrollView -> {
                var previousY = view.scrollY
                view.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val delta = if (oldScrollY != scrollY) scrollY - oldScrollY else scrollY - previousY
                    previousY = scrollY
                    handleBottomChromeScrollDelta(delta)
                }
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachScrollCallbacks(view.getChildAt(index))
            }
        }
    }

    private fun clearNestedScrollCallbacks(view: View) {
        if (view is androidx.core.widget.NestedScrollView) {
            view.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                clearNestedScrollCallbacks(view.getChildAt(index))
            }
        }
    }

    private fun handleBottomChromeScrollDelta(deltaY: Int) {
        if (!bottomChromeAllowed) return
        when {
            deltaY > BOTTOM_CHROME_SCROLL_THRESHOLD -> setBottomChromeHiddenByScroll(true)
            deltaY < -BOTTOM_CHROME_SCROLL_THRESHOLD -> setBottomChromeHiddenByScroll(false)
        }
    }

    private fun setBottomChromeHiddenByScroll(hidden: Boolean) {
        if (!bottomChromeAllowed || bottomChromeHiddenByScroll == hidden) return
        bottomChromeHiddenByScroll = hidden
        val navOffset = if (hidden) {
            binding.bottomNav.height + bottomSystemInset + dp(24)
        } else {
            0
        }.toFloat()
        val fabOffset = if (hidden) dp(88).toFloat() else 0f

        if (!binding.bottomNav.isVisible) binding.bottomNav.isVisible = true
        binding.bottomNav.animate()
            .translationY(navOffset)
            .alpha(if (hidden) 0f else 1f)
            .setDuration(BOTTOM_CHROME_ANIMATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                if (!hidden && bottomChromeAllowed) binding.bottomNav.isVisible = true
            }
            .start()

        if (binding.fabGlobalAdd.isVisible || hidden) {
            binding.fabGlobalAdd.animate()
                .translationY(fabOffset)
                .alpha(if (hidden) 0f else 1f)
                .setDuration(BOTTOM_CHROME_ANIMATION_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun updateBottomNavSelection(destinationId: Int) {
        val selectedTabId = TAB_DESTINATION_MAP[destinationId] ?: return
        if (binding.bottomNav.selectedItemId == selectedTabId) return
        updatingBottomSelection = true
        try {
            binding.bottomNav.selectedItemId = selectedTabId
        } finally {
            updatingBottomSelection = false
        }
    }

    private fun updateBottomNavItemStates(selectedTabId: Int?) {
        val activeIndex = mainTabs.indexOf(selectedTabId ?: -1)
        if (activeIndex != -1) {
            binding.bottomNav.updateActiveTab(activeIndex, mainTabs.size)
        }

        if (com.shuaib.classmate.utils.AnimUtils.isReduceMotionEnabled(this)) {
            MAIN_TAB_DESTINATIONS.forEach { tabId ->
                val itemView = binding.bottomNav.findViewById<View>(tabId) ?: return@forEach
                val selected = tabId == selectedTabId
                itemView.scaleX = if (selected) 1.05f else 1f
                itemView.scaleY = if (selected) 1.05f else 1f
                itemView.translationY = if (selected) -dp(3).toFloat() else 0f
            }
            return
        }

        MAIN_TAB_DESTINATIONS.forEach { tabId ->
            val itemView = binding.bottomNav.findViewById<View>(tabId) ?: return@forEach
            val selected = tabId == selectedTabId
            itemView.animate().cancel()
            
            val targetScale = if (selected) 1.05f else 1f
            val targetTranslation = if (selected) -dp(3).toFloat() else 0f
            val duration = if (selected) 250L else 180L
            val interpolator = if (selected) {
                android.view.animation.OvershootInterpolator(1.4f)
            } else {
                android.view.animation.DecelerateInterpolator()
            }
            
            itemView.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationY(targetTranslation)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()
        }
    }

    private fun applyRootInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarHeight = systemBars.top
            bottomSystemInset = systemBars.bottom
            applySystemBars()
            insets
        }
    }

    private fun applyBottomNavInsets() {
        val baseHorizontal = resources.getDimensionPixelSize(R.dimen.bottom_nav_inner_horizontal_padding)
        val baseVertical = resources.getDimensionPixelSize(R.dimen.bottom_nav_inner_vertical_padding)
        val baseBottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_nav_bottom_margin)

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            bottomSystemInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(baseHorizontal, baseVertical, baseHorizontal, baseVertical)
            view.layoutParams = (view.layoutParams as ConstraintLayout.LayoutParams).apply {
                bottomMargin = baseBottomMargin + bottomSystemInset
            }
            insets
        }
    }

    private fun navigateBottom(destinationId: Int) {
        if (destinationId !in MAIN_TAB_DESTINATIONS) return
        if (navController.currentDestination?.id == destinationId) return

        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false, true)
            .build()

        runCatching {
            navController.navigate(destinationId, null, options)
        }.onFailure {
            Log.e("BottomNavDebug", "Navigation failed to $destinationId", it)
        }
    }

    private fun showAdminPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "Post Notices")
        popup.menu.add(0, 2, 1, "Timetable Editor")
        popup.menu.add(0, 3, 2, "Create Poll")
        popup.menu.add(0, 4, 3, "Upload a PDF")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, PostNoticeActivity::class.java))
                2 -> startActivity(Intent(this, TimetableManagementActivity::class.java))
                3 -> startActivity(Intent(this, CreatePollActivity::class.java))
                4 -> startActivity(Intent(this, PdfUploadActivity::class.java))
            }
            true
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
        if (!firstResumeHandled) {
            firstResumeHandled = true
            return
        }
        WidgetUpdater.refresh(this)
        refreshUnreadNoticeBadge()
        handleNotificationRouting()
    }

    private fun applySystemBars() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val isNoticesTab = if (::binding.isInitialized) {
            binding.mainViewPager.isVisible && mainTabs.getOrNull(binding.mainViewPager.currentItem) == R.id.nav_notices
        } else {
            false
        }

        if (::binding.isInitialized) {
            val topPadding = if (isNoticesTab) 0 else statusBarHeight
            binding.mainViewPager.setPadding(0, topPadding, 0, 0)
            binding.navHostFragment.setPadding(0, statusBarHeight, 0, bottomSystemInset)
        }

        try {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isNight
                isAppearanceLightNavigationBars = !isNight
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set system bar appearance: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        startupHandler.removeCallbacksAndMessages(null)
        noticeBadgeListener?.remove()
    }

    private fun deferStartupWork() {
        startupHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            checkAdminStatus()
            listenForUnreadNotices()
            handleNotificationRouting()
            checkNotificationPermission()
        }, FIRST_DRAW_WORK_DELAY_MS)

        startupHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            WidgetUpdater.refresh(this)
            scheduleMidnightReset()
            NoticeReminderManager.syncRemindersWithLocal(this)
            com.shuaib.classmate.services.AutoMuteScheduler.scheduleAlarms(this)
        }, BACKGROUND_WORK_DELAY_MS)

        startupHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            checkAppUpdateSilently()
        }, BACKGROUND_WORK_DELAY_MS + 1500L)
    }

    private fun checkAppUpdateSilently() {
        val prefs = getSharedPreferences("app_update_prefs", MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_time", 0L)
        val currentTime = System.currentTimeMillis()

        // Debounce update check to once every 24 hours (86,400,000 ms), unless mock mode is enabled
        if (currentTime - lastCheck >= 86400000L || AppUpdateManager.isMockEnabled) {
            prefs.edit().putLong("last_check_time", currentTime).apply()

            lifecycleScope.launch {
                try {
                    val updateInfo = AppUpdateManager.checkLatestRelease()
                    if (AppUpdateManager.isUpdateAvailable(updateInfo.latestVersionName)) {
                        AppUpdateManager.showUpdateDialog(this@MainActivity, updateInfo, lifecycleScope)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Silent update check failed: ${e.message}")
                }
            }
        }
    }

    private fun listenForUnreadNotices() {
        noticeBadgeListener?.remove()
        val lastReadTime = com.google.firebase.Timestamp(java.util.Date(NoticeReadTracker.lastReadMillis(this)))
        noticeBadgeListener = firestore.collection("notices")
            .whereGreaterThan("timestamp", lastReadTime)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainActivity", "Error listening for unread notices", error)
                    return@addSnapshotListener
                }
                val unreadCount = snapshot?.size() ?: 0
                updateNoticeBadge(unreadCount)
            }
    }

    private fun refreshUnreadNoticeBadge() {
        listenForUnreadNotices()
    }

    private fun updateNoticeBadge(count: Int) {
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_notices)
        if (count > 0) {
            badge.isVisible = true
            badge.number = count
            badge.maxCharacterCount = 3
            badge.backgroundColor = ThemeColors.error(this)
            badge.badgeTextColor = ThemeColors.onPrimary(this)
        } else {
            binding.bottomNav.removeBadge(R.id.nav_notices)
        }
    }

    private fun observeChatUnreadBadge() {
        // AI Chat tab does not require unread badges from old WebSocket rooms
    }

    private fun handleNotificationRouting() {
        val tab = NotificationRouter.pendingTab ?: return
        val subject = NotificationRouter.pendingSubject

        when (tab) {
            "chat" -> {
                showMainTab(R.id.nav_chat)
            }
            "notices" -> showMainTab(R.id.nav_notices)
            "timetable" -> showMainTab(R.id.nav_timetable)
            "pdf_library" -> {
                showMainTab(R.id.nav_pdf)
                if (subject != null) {
                    val bundle = bundleOf("subjectName" to subject)
                    openChildDestination(R.id.nav_pdf, R.id.fragment_subject_pdf_list, bundle)
                }
            }
        }

        NotificationRouter.pendingTab = null
        NotificationRouter.pendingSubject = null
        NotificationRouter.pendingChatRoomId = null
        NotificationRouter.pendingChatSenderId = null
        NotificationRouter.pendingChatRoomType = null
        NotificationRouter.pendingChatSenderName = null
    }

    private fun scheduleMidnightReset() {
        val now = Calendar.getInstance()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = midnight.timeInMillis - now.timeInMillis

        val resetWork = OneTimeWorkRequestBuilder<TimetableResetWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "midnight_reset",
            ExistingWorkPolicy.REPLACE,
            resetWork
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FIRST_DRAW_WORK_DELAY_MS = 1200L
        private const val BACKGROUND_WORK_DELAY_MS = 3000L
        private const val BOTTOM_CHROME_SCROLL_THRESHOLD = 8
        private const val BOTTOM_CHROME_ANIMATION_MS = 220L

        private val MAIN_TAB_DESTINATIONS = setOf(
            R.id.nav_timetable,
            R.id.nav_notices,
            R.id.nav_chat,
            R.id.nav_pdf,
            R.id.nav_profile
        )

        private val TAB_DESTINATION_MAP = mapOf(
            R.id.nav_timetable to R.id.nav_timetable,
            R.id.nav_notices to R.id.nav_notices,
            R.id.nav_chat to R.id.nav_chat,
            R.id.fragment_group_chat to R.id.nav_chat,
            R.id.fragment_dm_list to R.id.nav_chat,
            R.id.fragment_dm_chat to R.id.nav_chat,
            R.id.nav_pdf to R.id.nav_pdf,
            R.id.fragment_library_all_files to R.id.nav_pdf,
            R.id.fragment_library_search to R.id.nav_pdf,
            R.id.fragment_subject_pdf_list to R.id.nav_pdf,
            R.id.nav_profile to R.id.nav_profile
        )
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Permission Needed")
            .setMessage("ClassMate needs permission to show you important notices.")
            .setPositiveButton("OK") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("No Thanks", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        val noticeId = intent.getStringExtra("noticeId")
            ?: intent.data?.takeIf { it.scheme == "classmate" && it.host == "notice" }?.lastPathSegment
        if (!noticeId.isNullOrBlank()) {
            NotificationRouter.pendingNoticeId = noticeId
            showMainTab(R.id.nav_notices)
            return
        }

        // Handle Local Chat Notifications
        val chatRoomId = intent.getStringExtra(ChatNotificationHelper.KEY_ROOM_ID)
            ?: intent.getStringExtra("roomId")
        chatRoomId?.let { roomId ->
            val roomType = intent.getStringExtra(ChatNotificationHelper.KEY_ROOM_TYPE)
                ?: intent.getStringExtra("roomType")
            val senderName = intent.getStringExtra(ChatNotificationHelper.KEY_SENDER_NAME)
                ?: intent.getStringExtra("senderName")

            showMainTab(R.id.nav_chat)
            return
        }

        // Handle OneSignal / Other Notifications
        intent.getStringExtra("OPEN_TAB")?.let { tab ->
            when (tab) {
                "notices" -> showMainTab(R.id.nav_notices)
                "polls" -> showMainTab(R.id.nav_notices)
            }
        }
    }
}
