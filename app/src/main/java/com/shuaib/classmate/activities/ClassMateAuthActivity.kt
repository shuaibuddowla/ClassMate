package com.shuaib.classmate.activities

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.graphics.Typeface
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.messaging.FirebaseMessaging
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.GlassBottomNavView
import com.shuaib.classmate.utils.AppPreferences
import com.google.android.material.card.MaterialCardView
import java.time.LocalDate
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Academic client backed by Supabase Auth and the classmate RLS schema. */
class ClassMateAuthActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var profileView: TextView
    private lateinit var resultView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var academicCards: LinearLayout
    private lateinit var homeHost: LinearLayout
    private lateinit var academicScreens: ClassMateAcademicScreensSupabase
    private lateinit var friendsScreen: ClassMateFriendsScreen
    private var profile: JSONObject? = null
    private var userId: String = ""
    private var askedNotificationPermission = false
    private var homeShown = false
    private var revealAfterAuth = false
    private var welcomeStep = 0
    private var welcomeDialog: AlertDialog? = null
    private var welcomePreparing = false
    private val welcomeProgress by lazy { getSharedPreferences("classmate_welcome_${BuildConfig.CLASSMATE_ENV}", MODE_PRIVATE) }

    private fun profileComplete(account: JSONObject): Boolean =
        !account.isNull("profile_completed_at") && account.optString("profile_completed_at").isNotBlank()

    private fun notificationChannelDisabled(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel("classmate_notifications")?.importance == android.app.NotificationManager.IMPORTANCE_NONE

    private fun notificationsEnabled(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        !notificationChannelDisabled() && AppPreferences(this).isNotificationsEnabled()

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26 && notificationChannelDisabled()) {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "classmate_notifications")
        } else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        startActivity(intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun startWelcomeFlow(firstStep: Int = 1) {
        if(userId.isBlank()) return
        welcomeProgress.edit().putInt(userId,firstStep).apply()
        welcomeStep=firstStep; revealAfterAuth=firstStep>0
    }

    private fun restoreWelcomeProgress(id: String) {
        val persisted=if(welcomeProgress.contains(id)) welcomeProgress.getInt(id,0) else null
        welcomeStep=ClassMateWelcomeProgress.restore(persisted,welcomeStep)
        revealAfterAuth=welcomeStep>0
    }

    private fun moveWelcomeStep(expected: Int,next: Int): Boolean {
        val current=welcomeProgress.getInt(userId,welcomeStep)
        if(!ClassMateWelcomeProgress.canMove(current,expected)) return false
        // A successful save must advance even if theme changes destroyed this activity.
        // showStep checks the lifecycle before displaying the next dialog.
        welcomeProgress.edit().putInt(userId,next).apply()
        welcomeStep=next; revealAfterAuth=next>0
        return true
    }
    private var tabAnimating = false
    private var authBusy = false
    private var profileValidated = false
    private var pendingCallNumber: String? = null
    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingCallNumber?.let { launchPhoneCall(it,granted) }
        pendingCallNumber=null
    }

    private fun callFriend(phone: String) {
        if(!phone.matches(Regex("\\+[1-9][0-9]{7,14}"))) return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED) launchPhoneCall(phone,true)
        else { pendingCallNumber=phone; callPermission.launch(Manifest.permission.CALL_PHONE) }
    }
    private fun launchPhoneCall(phone: String,allowed: Boolean) {
        try { startActivity(Intent(if(allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL,Uri.parse("tel:$phone"))) }
        catch (_: Exception) { Toast.makeText(this,"Could not open your phone app",Toast.LENGTH_SHORT).show() }
    }
    private var welcomeUi: ClassMateWelcomeUi? = null
    private var manageOpen = false
    private var selectedTab = R.id.nav_timetable
    private var selectedBatchId = ""
    private var selectedBatchLabel = ""
    private var selectedDay = LocalDate.now().dayOfWeek.value % 7

    private val periodEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if(homeShown) academicScreens.invalidateLibrary()
            ClassMateAcademicCache.invalidateSchedules(this, userId, selectedBatchId)
            if (homeShown) renderHomeTab(selectedTab)
        }
    }

    private fun openPeriodEditor(slot: JSONObject? = null, selectedDayOverride: Int? = null) {
        val intent = Intent(this, ClassMatePeriodEditorActivity::class.java)
            .putExtra("batch_id", selectedBatchId)
            .putExtra("batch_label", selectedBatchLabel)
            .putExtra("role", profile?.optString("role"))
            .putExtra("profile_id", profile?.optString("id"))
            .putExtra("day", slot?.optInt("day_of_week") ?: selectedDayOverride ?: selectedDay)
        slot?.let {
            intent.putExtra("edit_id", it.optString("id"))
                .putExtra("start", it.optString("start_time"))
                .putExtra("end", it.optString("end_time"))
        }
        periodEditorLauncher.launch(intent)
    }

    private val configurationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK && homeShown) {
            academicScreens.invalidateLibrary()
            ClassMateAcademicCache.invalidateSchedules(this,userId,selectedBatchId)
            renderHomeTab(if(manageOpen) R.id.nav_manage else selectedTab)
        }
    }
    private fun openConfiguration(mode: String="courses") {
        configurationLauncher.launch(ClassMateCourses.intent(this,selectedBatchId,selectedBatchLabel,
            profile?.optString("role").orEmpty(),userId,mode))
    }
    private fun openManage() {
        val p=profile ?: return
        if(p.optString("role") !in setOf("admin","teacher") && !p.optBoolean("is_cr")) return
        manageOpen=true
        findViewById<GlassBottomNavView>(R.id.classmate_home_nav).visibility=View.GONE
        renderHomeTab(R.id.nav_manage)
        onBackPressedDispatcher.addCallback(this,object: androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                manageOpen=false; remove()
                findViewById<GlassBottomNavView>(R.id.classmate_home_nav).visibility=View.VISIBLE
                renderHomeTab(R.id.nav_profile)
            }
        })
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppPreferences(this).setNotificationsEnabled(granted)
        if (granted) {
            registerFcmToken()
            if (notificationChannelDisabled()) openNotificationSettings()
        }
        if (!granted) status.text = "Notifications are off. Allow them in Android settings to receive class updates."
        if(homeShown && selectedTab==R.id.nav_profile) renderHomeTab(selectedTab)
    }

    private val courseFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (homeShown && selectedTab == R.id.nav_pdf) {
            academicScreens.invalidateLibrary()
            ClassMateAcademicCache.invalidateSchedules(this, userId, selectedBatchId)
            renderHomeTab(selectedTab)
        }
    }

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val idToken = account.idToken ?: error("Google returned no ID token")
            runAction("Signing in") {
                val auth = ClassMateAuthApi.signInWithGoogleIdToken(idToken)
                userId = auth.getJSONObject("user").getString("id")
                // Check before initialization creates a profile. Device-local history
                // cannot distinguish existing users signing in on a new phone.
                val existing = ClassMateAuthApi.rows("profiles", "select=id,profile_completed_at&id=eq.$userId&limit=1").optJSONObject(0)
                startWelcomeFlow(ClassMateWelcomeProgress.afterSignIn(existing==null,
                    existing?.let { profileComplete(it) } ?: false, notificationsEnabled()))
                loadProfile()
            }
        } catch (error: Exception) {
            authBusy = false
            status.text = if (error is ApiException && error.statusCode == 12501)
                "Sign-in cancelled. Continue whenever you’re ready."
            else "Could not sign in. Please try your MBSTU edumail again. ${error.message.orEmpty()}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ClassMateAuthApi.attach(applicationContext)
        if (!ClassMateAuthApi.hasSavedSession()) delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        super.onCreate(savedInstanceState)
        welcomeStep = savedInstanceState?.getInt("welcome_step") ?: 0
        pendingCallNumber=savedInstanceState?.getString("pending_call_number")
        selectedBatchId = savedInstanceState?.getString("selected_batch_id").orEmpty()
        selectedBatchLabel = savedInstanceState?.getString("selected_batch_label").orEmpty()
        selectedTab = savedInstanceState?.getInt("selected_tab") ?: selectedTab
        revealAfterAuth = savedInstanceState?.getBoolean("identity_reveal") ?: false
        ClassMateAuthApi.attach(applicationContext)
        if (intent.getStringExtra("OPEN_TAB") == "notices") selectedTab = R.id.nav_notices
        val cachedHome = if (ClassMateAuthApi.hasSavedSession()) ClassMateAcademicCache.home(this) else null
        if (cachedHome != null) {
            profile = cachedHome.optJSONObject("profile")
            userId = profile?.optString("id").orEmpty()
            selectedBatchId = cachedHome.optString("batch"); selectedBatchLabel = cachedHome.optString("label")
            if (profile?.optString("verification_status") == "active" && userId.isNotBlank() && selectedBatchId.isNotBlank()) {
                profile?.let { ClassMateAuthApi.saveNotificationIdentity(it) }
                // Render the last synced home first; revalidate the session in the background.
                status = TextView(this); profileView = TextView(this); resultView = TextView(this); actions = LinearLayout(this)
                showHome(); renderHomeTab(selectedTab)
                if (ClassMateAcademicCache.online(this)) {
                    reconnecting = true
                    lifecycleScope.launch {
                        try { loadProfile() }
                        catch (_: Exception) { if (!ClassMateAuthApi.hasSavedSession()) signOut() }
                        finally { reconnecting = false }
                    }
                }
                return
            }
        }
        showSignInScreen()
        if (!ClassMateAuthApi.configured) {
            status.text = "Supabase URL or public client key is missing from this build."
        } else {
            lifecycleScope.launch {
                authBusy = true
                status.text = "Restoring session…"
                if (ClassMateAuthApi.restoreSession()) {
                    runCatching { loadProfile() }
                        .onFailure {
                            authBusy = false
                            status.text = "Session restore failed: ${it.message}"
                            welcomeUi?.action("Retry profile setup", parent = actions) {
                                runAction("Loading profile") { loadProfile() }
                            }
                            welcomeUi?.action("Use another edumail", false, actions) { signOut() }
                        }
                } else {
                    authBusy = false
                    status.text = "Use your @mbstu.ac.bd Google account to join."
                }
            }
        }
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var reconnecting = false
    override fun onStart() {
        super.onStart()
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    if (homeShown && !reconnecting && ClassMateAuthApi.accessToken == null) {
                        reconnecting = true
                        lifecycleScope.launch { try { loadProfile() } catch (_: Exception) { if (!ClassMateAuthApi.hasSavedSession()) signOut() } finally { reconnecting = false } }
                    }
                }
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
    }
    override fun onStop() {
        networkCallback?.let { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("welcome_step", welcomeStep)
        outState.putString("pending_call_number",pendingCallNumber)
        outState.putString("selected_batch_id", selectedBatchId)
        outState.putString("selected_batch_label", selectedBatchLabel)
        outState.putInt("selected_tab", selectedTab)
        outState.putBoolean("identity_reveal", revealAfterAuth)
        super.onSaveInstanceState(outState)
    }

    private fun showSignInScreen() {
        homeShown = false
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        val ui = ClassMateWelcomeUi(this)
        welcomeUi = ui; content = ui.content
        ui.orbit()
        ui.title("Welcome to\nClassMate.")
        ui.text("Sign in with your @mbstu.ac.bd university email.")
        ui.action("Continue with Google") { signIn() }
        status = ui.text("",13f).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        profileView = ui.text("").apply { visibility=View.GONE }; resultView = ui.text("").apply { visibility=View.GONE }
        actions = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; content.addView(actions)
        ui.animateEntrance()
    }

    private fun googleClient() = GoogleSignIn.getClient(this,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build())

    private fun signIn() {
        if (!ClassMateAuthApi.configured || authBusy) return
        authBusy = true
        status.text = "Choose your @mbstu.ac.bd Google account…"
        val client = googleClient()
        client.signOut().addOnCompleteListener { googleLauncher.launch(client.signInIntent) }
    }

    private fun signOut() {
        profileValidated=false
        welcomeDialog?.dismiss(); welcomeDialog=null; welcomeStep=0; revealAfterAuth=false
        if (ClassMateAuthApi.accessToken == null || !ClassMateAcademicCache.online(this)) {
            ClassMateAuthApi.signOut(); homeShown = false; profile = null; userId = ""; selectedBatchId = ""; selectedBatchLabel = ""; authBusy = false
            googleClient().signOut(); showSignInScreen(); return
        }
        status.text = "Signing out…"
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            lifecycleScope.launch {
                val result = runCatching {
                    if (task.isSuccessful && !task.result.isNullOrBlank())
                        ClassMateAuthApi.unregisterDeviceToken(task.result)
                }
                ClassMateAuthApi.signOut()
                homeShown = false
                profile = null
                userId = ""
                selectedBatchId = ""
                selectedBatchLabel = ""
                revealAfterAuth = false
                authBusy = false
                profileView.text = ""
                resultView.text = ""
                actions.removeAllViews()
                googleClient().signOut().addOnCompleteListener {
                    showSignInScreen()
                    status.text = if (result.isSuccess) "Signed out."
                        else "Signed out. Device notification removal failed: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    private suspend fun loadProfile() {
        var loaded = ClassMateAuthApi.initializeProfile()
        if (loaded.optString("role") == "student" && loaded.isNull("department_id")) {
            val prefix = Regex("^([a-z]+)[0-9]{2}[0-9]{3,4}@mbstu\\.ac\\.bd$")
                .matchEntire(loaded.optString("email").lowercase())?.groupValues?.get(1)
            if (prefix != null) {
                val departments = ClassMateAuthApi.rows("departments",
                    "select=id,email_prefix&is_active=eq.true")
                val matches = (0 until departments.length()).map { departments.getJSONObject(it) }
                    .filter { it.optString("email_prefix") == prefix }
                if (matches.size == 1) {
                    loaded = ClassMateAuthApi.rpc("complete_student_onboarding",
                        JSONObject().put("selected_department", matches.single().getString("id")))
                    userId=loaded.getString("id")
                }
            }
        }
        authBusy = false
        profile = loaded
        profileValidated = true
        ClassMateAuthApi.saveNotificationIdentity(loaded)
        userId = loaded.getString("id")
        restoreWelcomeProgress(userId)
        val resumedStep=ClassMateWelcomeProgress.resume(welcomeStep,profileComplete(loaded),notificationsEnabled())
        if(resumedStep!=welcomeStep) startWelcomeFlow(resumedStep)
        if (loaded.optString("verification_status") == "active") {
            val role = loaded.optString("role")
            if (role == "admin" || role == "teacher") {
                if (selectedBatchId.isBlank()) {
                    showBatchPicker()
                    return
                } else {
                    if (!homeShown) showHome()
                    renderHomeTab(selectedTab)
                }
            } else {
                selectedBatchId = loaded.optString("batch_id").takeUnless { it == "null" }.orEmpty()
                if (selectedBatchId.isBlank()) error("Active student has no assigned batch")
                if (!homeShown) showHome()
                renderHomeTab(selectedTab)
            }
            if (AppPreferences(this).isNotificationsEnabled())
                runCatching { com.shuaib.classmate.services.ClassMateNoticeReminderScheduler.restore(this, userId) }
            ClassMateAuthApi.saveNotificationIdentity(loaded)
            if (revealAfterAuth || welcomeStep > 0) {
                if (welcomeStep == 0) welcomeStep = 1
                showIdentityReveal(loaded)
            } else { registerFcmToken() }

        } else {
            ClassMateAcademicCache.clear(this)
            showProfilePending(loaded)
            renderActions(loaded)
        }
    }

    private fun showProfilePending(loaded: JSONObject) {
        homeShown = false
        val ui = ClassMateWelcomeUi(this)
        welcomeUi = ui
        content = ui.content
        ui.orbit()
        val needsDepartment = loaded.isNull("department_id")
        val rejected = loaded.optString("verification_status") == "rejected"
        ui.title(when {
            rejected -> "Your profile\nneeds a review."
            needsDepartment -> "Let’s find\nyour batch."
            else -> "Your account.\nOne step closer."
        })
        ui.text(loaded.optString("email"))
        status = ui.text(when {
            rejected -> "Your profile needs a review: ${loaded.optString("rejection_reason")}. Contact your administrator for help."
            needsDepartment -> "Your edumail is verified. Choose your department to finish setting up your academic profile."
            else -> "Your details have been submitted. An administrator will verify your profile before you enter your batch."
        })
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        profileView = ui.text("").apply { visibility = View.GONE }
        resultView = ui.text("").apply { visibility = View.GONE }
        actions = ui.panel()
        ui.action("Use another edumail", false) { signOut() }
        ui.animateEntrance()
    }

    private suspend fun showIdentityReveal(loaded: JSONObject) {
        if (welcomePreparing || welcomeDialog?.isShowing == true || !homeShown || welcomeStep == 0) return
        welcomePreparing=true
        val batch: JSONObject?
        val department: JSONObject?
        try {
            batch=selectedBatchId.takeIf { it.isNotBlank() }?.let { id -> runCatching {
                ClassMateAuthApi.rows("batches", "select=batch_number,academic_session,department_id&id=eq.$id&limit=1").optJSONObject(0)
            }.getOrNull() }
            val departmentId=batch?.optString("department_id") ?: loaded.optString("department_id")
            department=departmentId.takeUnless { it.isBlank() || it=="null" }?.let { id -> runCatching {
                ClassMateAuthApi.rows("departments", "select=name,code&id=eq.$id&limit=1").optJSONObject(0)
            }.getOrNull() }
        } finally { welcomePreparing=false }
        fun showStep() {
            restoreWelcomeProgress(userId)
            val resumedStep=ClassMateWelcomeProgress.resume(welcomeStep,profileComplete(profile ?: loaded),notificationsEnabled())
            if(resumedStep!=welcomeStep) startWelcomeFlow(resumedStep)
            if (isDestroyed || isFinishing || !homeShown || welcomeStep==0 || welcomeDialog?.isShowing==true) return
            val shownStep=welcomeStep
            val permission=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
            welcomeDialog=ClassMateWelcomeDialogs.show(this,shownStep,profile ?: loaded,batch,department,
                notificationsEnabled(),
                continueFlow={
                    if(moveWelcomeStep(shownStep,shownStep+1)) {
                        welcomeDialog=null
                        window.decorView.post { showStep() }
                    }
                },
                enable={
                    if(moveWelcomeStep(shownStep,0)) {
                        welcomeDialog=null
                        AppPreferences(this).setNotificationsEnabled(true)
                        if(Build.VERSION.SDK_INT>=33 && !permission) {
                            val previouslyAsked=welcomeProgress.getBoolean("notification_permission_requested",false)
                            if(previouslyAsked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
                            else {
                                welcomeProgress.edit().putBoolean("notification_permission_requested",true).apply()
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else { registerFcmToken(); if(!notificationsEnabled()) openNotificationSettings() }
                    }
                },
                skip={ if(moveWelcomeStep(shownStep,0)) { welcomeDialog=null; AppPreferences(this).setNotificationsEnabled(false) } },
                onProfileUpdated={ updated -> updatePersonalProfile(updated) }
            )
        }
        window.decorView.post { showStep() }
    }

    override fun onDestroy() {
        welcomeDialog?.dismiss()
        welcomeDialog=null
        super.onDestroy()
    }

    private fun updatePersonalProfile(updated: JSONObject) {
        if(updated.optString("id")!=userId) return
        profile=updated
        ClassMateAuthApi.saveNotificationIdentity(updated)
        ClassMateAcademicCache.saveHome(this,updated,selectedBatchId,selectedBatchLabel)
    }

    private fun editPersonalProfile() {
        val account=profile ?: return
        ClassMateProfileDetailsDialog.show(this,account,false) { updated ->
            updatePersonalProfile(updated)
            if(homeShown && selectedTab==R.id.nav_profile) renderHomeTab(selectedTab)
        }
    }

    private fun showHome() {
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        welcomeUi = null
        setContentView(R.layout.activity_classmate_home)
        window.statusBarColor = getColor(R.color.cm_bg)
        window.navigationBarColor = getColor(R.color.cm_bg)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            val lightTheme = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        homeShown = true
        ClassMateAcademicCache.saveHome(this, profile ?: JSONObject(), selectedBatchId, selectedBatchLabel)
        homeHost = findViewById(R.id.classmate_home_content)
        content = homeHost
        academicScreens = ClassMateAcademicScreensSupabase(
            this, lifecycleScope, { selectedBatchId }, { selectedBatchLabel },
            { profile ?: JSONObject() },
            { showNoticeForm() }, { showUploadForm() }, { file -> openFileEditor(file) }, { signOut() },
            { showBatchPicker() }, { slot, day -> openPeriodEditor(slot, day) },
            { bus, kind -> showBusScheduleDialog(bus,kind) },
            { courseId, courseName, canManage ->
                courseFilesLauncher.launch(Intent(this, ClassMateCourseFilesActivity::class.java)
                    .putExtra("course_id", courseId).putExtra("course_name", courseName)
                    .putExtra("batch_id", selectedBatchId).putExtra("profile_id", userId).putExtra("can_manage", canManage)
                    .putExtra("role", profile?.optString("role")).putExtra("is_cr", profile?.optBoolean("is_cr") == true))
            },
            { editPersonalProfile() }, { openManage() }, { openConfiguration() }, { enableNotificationsFromProfile() },
            { aiLauncher.launch(Intent(this,ClassMateAiActivity::class.java).putExtra("batch_id",selectedBatchId).putExtra("profile_id",userId)) },
            { unreadActivity?.refresh() }
        )
        if(unreadActivity==null) unreadActivity=ClassMateUnreadActivity(this,{selectedBatchId},{userId},{selectedTab==R.id.nav_friends && !manageOpen})
        unreadActivity?.refresh()
        friendsScreen=ClassMateFriendsScreen(this,lifecycleScope,{ selectedBatchId },{ phone -> callFriend(phone) })
        val nav = findViewById<GlassBottomNavView>(R.id.classmate_home_nav)
        nav.menu.findItem(R.id.nav_friends).isVisible=selectedBatchId.isNotBlank() && profile?.optString("verification_status")=="active"
        nav.menu.findItem(R.id.nav_manage).isVisible = false
        if(nav.menu.findItem(selectedTab)?.isVisible!=true) selectedTab=R.id.nav_timetable
        nav.selectedItemId = selectedTab
        val swipeHost = homeHost as com.shuaib.classmate.ui.ClassMateSwipeTabsHost
        fun destination(direction: Int): Int? {
            val tabs=(0 until nav.menu.size()).map { nav.menu.getItem(it) }.filter { it.isVisible }
            return tabs.getOrNull(tabs.indexOfFirst { it.itemId==selectedTab }+direction)?.itemId
        }
        swipeHost.canSwipe = { direction -> !manageOpen && destination(direction)!=null }
        swipeHost.onSwipe = { direction -> destination(direction)?.let { next ->
            swipeHost.animate().cancel(); swipeHost.alpha=1f; swipeHost.translationX=0f
            nav.selectedItemId=next
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                return@let
            }
            tabAnimating=true
            swipeHost.translationX=direction*12f*resources.displayMetrics.density; swipeHost.alpha=0.92f
            swipeHost.animate().translationX(0f).alpha(1f).setDuration(110).withEndAction { tabAnimating=false }.start()
        } }
        nav.setOnItemSelectedListener { item ->
            if(item.itemId==selectedTab) return@setOnItemSelectedListener true
            if (item.itemId != selectedTab) {
                com.shuaib.classmate.ui.ClassMateHaptics.selection(nav)
            }
            if(tabAnimating) {
                swipeHost.animate().cancel(); swipeHost.alpha=1f; swipeHost.translationX=0f; tabAnimating=false
            }
            selectedTab = item.itemId
            renderHomeTab(selectedTab)
            true
        }
    }

    private fun showBatchPicker() {
        homeShown = false
        val ui = ClassMateWelcomeUi(this)
        welcomeUi = ui
        content = ui.content
        ui.orbit(true)
        ui.text(if (profile?.optString("role") == "teacher") "TEACHER WORKSPACE" else "ADMIN WORKSPACE", 11f)
        ui.title("Choose your\nclassroom.")
        ui.text("Choose a running batch.")
        status = ui.text("Finding your batches…")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        profileView = ui.text("").apply { visibility = View.GONE }
        resultView = ui.text("").apply { visibility = View.GONE }
        actions = ui.panel()
        ui.animateEntrance()
        lifecycleScope.launch {
            runCatching {
                val batches = ClassMateAuthApi.rows("batches",
                    "select=id,batch_number,academic_session,department_id&is_active=eq.true&order=batch_number")
                val departments = ClassMateAuthApi.rows("departments", "select=id,code")
                val codes = (0 until departments.length()).associate { i ->
                    departments.getJSONObject(i).getString("id") to
                        departments.getJSONObject(i).getString("code").uppercase()
                }
                val allowed = if (profile?.optString("role") == "teacher") {
                    val assignments = ClassMateAuthApi.rows("teacher_course_assignments",
                        "select=semester_course_id&teacher_id=eq.$userId&active=eq.true")
                    val courseIds = (0 until assignments.length()).map { i ->
                        assignments.getJSONObject(i).getString("semester_course_id")
                    }.toSet()
                    val offerings = ClassMateAuthApi.rows("semester_courses", "select=id,semester_id")
                    val semesterIds = (0 until offerings.length()).mapNotNull { i ->
                        offerings.getJSONObject(i).takeIf { it.getString("id") in courseIds }
                            ?.getString("semester_id")
                    }.toSet()
                    val semesters = ClassMateAuthApi.rows("semesters", "select=id,batch_id&status=eq.active")
                    (0 until semesters.length()).mapNotNull { i ->
                        semesters.getJSONObject(i).takeIf { it.getString("id") in semesterIds }
                            ?.getString("batch_id")
                    }.toSet()
                } else null
                actions.removeAllViews()
                var count = 0
                for (i in 0 until batches.length()) {
                    val batch = batches.getJSONObject(i)
                    val id = batch.getString("id")
                    if (allowed != null && id !in allowed) continue
                    val label = "${codes[batch.getString("department_id")] ?: "Department"} " +
                        "Batch ${batch.getInt("batch_number")} · Session ${ClassMateAcademicSession.format(batch.getInt("academic_session"))}"
                    ui.action("$label  →", false, actions) {
                        selectedBatchId = id
                        selectedBatchLabel = label
                        selectedTab = R.id.nav_timetable
                        runAction("Opening batch") { loadProfile() }
                    }
                    count++
                }
                status.text = if (count == 0) "No running batches available. Teachers: ask your administrator to assign an active course to your account." else "$count running ${if (count == 1) "batch" else "batches"} available"
                ui.action("Sign out", false, actions) { signOut() }
            }.onFailure {
                status.text = "Could not load running batches: ${it.message}"
                ui.action("Try again", parent = actions) { showBatchPicker() }
                ui.action("Sign out", false, actions) { signOut() }
            }
        }
    }

    private fun renderHomeTab(tab: Int) {
        if (!homeShown) return
        findViewById<GlassBottomNavView>(R.id.classmate_home_nav).apply {
            animate().cancel()
            translationY = 0f
            alpha = 1f
            val visibleItems = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
            post { updateActiveTab(visibleItems.indexOfFirst { it.itemId == tab }, visibleItems.size) }
        }
        if (tab != R.id.nav_manage) {
            content = homeHost
            homeHost.removeAllViews()
            if(tab==R.id.nav_friends) {
                friendsScreen.render(homeHost,selectedBatchLabel)
                unreadActivity?.showUnseenRequest()
                return
            }
            academicScreens.render(tab, homeHost)
            return
        }
        homeHost.removeAllViews()
        val manageScroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        manageScroll.addView(content)
        homeHost.addView(manageScroll, LinearLayout.LayoutParams(-1, -1))
        content.removeAllViews()
        val title = when (tab) {
            R.id.nav_notices -> "Notices"
            R.id.nav_pdf -> "Library"
            R.id.nav_profile -> "Profile"
            R.id.nav_manage -> "Manage"
            else -> "Timetable"
        }
        if (tab == R.id.nav_timetable) {
            label("${greeting()},", 15f)
            label(profile?.optString("full_name")?.substringBefore(' ')?.ifBlank { "ClassMate" }
                ?: "ClassMate", 25f)
            label(selectedBatchLabel.ifBlank { "Your timetable" }, 14f)
            showDaySelector()
        } else {
            val toolbar=com.google.android.material.appbar.MaterialToolbar(this).apply {
                this.title=title; setTitleTextColor(getColor(R.color.cm_text_primary)); setNavigationIcon(R.drawable.ic_chevron_left)
                navigationIcon?.setTint(getColor(R.color.cm_text_primary))
                setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
            content.addView(toolbar)
        }
        status = label("").apply { visibility = View.GONE }
        profileView = label("").apply { visibility = View.GONE }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (tab == R.id.nav_timetable) resultView = label("")
        content.addView(actions)
        if (tab != R.id.nav_timetable) resultView = label("")
        academicCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(academicCards)

        val loaded = profile ?: return
        val role = loaded.optString("role")
        when (tab) {
            R.id.nav_notices -> {
                if (loaded.optBoolean("is_cr")) button("Post notice", actions) { showNoticeForm() }
                readRows("notices", "select=id,title,body,published_at&order=published_at.desc&limit=30", true)
            }
            R.id.nav_pdf -> {
                readRows("file_metadata",
                    "select=id,title,file_type,size_bytes,created_at&status=eq.active&order=created_at.desc&limit=50", true)
            }
            R.id.nav_profile -> {
                profileView.visibility = View.VISIBLE
                profileView.text = "Loading your profile…"
                lifecycleScope.launch { renderProfileDetails(loaded) }
                button("Refresh profile", actions) { runAction("Refreshing profile") { loadProfile() } }
                if (role == "admin" || role == "teacher")
                    button("Switch running batch", actions) { showBatchPicker() }
                button("Sign out", actions) { signOut() }
            }
            R.id.nav_manage -> {
                if (role !in setOf("admin", "teacher") && !loaded.optBoolean("is_cr")) return
                renderManageOverview(role)
            }
            else -> {
                button("Courses", actions) {
                    readRows("semester_courses", "select=*&limit=50", true)
                }
                button("Class changes", actions) {
                    readRows("class_changes", "select=*&limit=30", true)
                }
                button("Bus schedule", actions) {
                    readRows("bus_schedules", "select=*&limit=30")
                }
                readRows("routine_slots",
                    "select=semester_course_id,day_of_week,start_time,end_time,room,type" +
                        "&order=start_time.asc&day_of_week=eq.$selectedDay&limit=50", true)
            }
        }
    }

    private fun renderManageOverview(role: String) {
        val density = resources.displayMetrics.density
        label(selectedBatchLabel.ifBlank { "Current batch" }, 15f).apply {
            setTextColor(getColor(R.color.cm_text_secondary))
        }
        manageCard(actions,"Courses","Configure this batch’s courses and teachers") { openConfiguration() }
        if (role == "admin") {
            manageCard(actions,"System health","Delivery queue, database growth and hosting usage") {
                ClassMateHealthDialog.show(this,lifecycleScope)
            }
            label("Administration", 21f).apply {
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (24 * density).toInt(), 0, 0)
            }
            manageCard(actions, "Batches & semesters", "Create batches, publish semesters, clone a plan") {
                showManageMenu("Batches & semesters", listOf(1, 4, 9, 13, 14))
            }
            manageCard(actions, "People & approvals", "Review students and manage the roster") {
                openConfiguration("people")
            }
            manageCard(actions, "Teachers", "Allowlist teachers and assign courses") {
                openConfiguration("teachers")
            }
            manageCard(actions, "Departments", "Create and configure departments") {
                showManageMenu("Departments", listOf(0, 3, 10))
            }
        }
        content.setPadding(content.paddingLeft, content.paddingTop,
            content.paddingRight, (32 * density).toInt())
    }

    private fun manageCard(parent: LinearLayout, title: String, subtitle: String,
                           action: () -> Unit) {
        val d = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 20 * d
            strokeWidth = d.toInt().coerceAtLeast(1)
            strokeColor = getColor(R.color.cm_border)
            setCardBackgroundColor(getColor(R.color.cm_surface))
            cardElevation = 2 * d
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (9 * d).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18*d).toInt(), (15*d).toInt(), (16*d).toInt(), (15*d).toInt())
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ClassMateAuthActivity).apply {
                text = title; textSize = 16f; setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_text_primary))
            })
            addView(TextView(this@ClassMateAuthActivity).apply {
                text = subtitle; textSize = 12f
                setTextColor(getColor(R.color.cm_text_secondary))
                setPadding(0, (3*d).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"; textSize = 27f; setTextColor(getColor(R.color.cm_primary))
            setPadding((12*d).toInt(), 0, 0, 0)
        })
        card.addView(row)
        parent.addView(card)
    }

    private fun loadManageCourses(host: LinearLayout) {
        val requestedBatch = selectedBatchId
        host.removeAllViews()
        val loading = TextView(this).apply {
            text = "Loading active courses…"
            setTextColor(getColor(R.color.cm_text_secondary))
        }
        host.addView(loading)
        lifecycleScope.launch {
            runCatching {
                val semester = ClassMateAuthApi.rows("semesters",
                    "select=id,semester_number&batch_id=eq.$requestedBatch&status=eq.active&limit=1")
                    .optJSONObject(0)
                val offerings = if (semester == null) JSONArray() else ClassMateAuthApi.rows(
                    "semester_courses", "select=id,course_id&semester_id=eq.${semester.getString("id")}")
                val catalog = ClassMateAuthApi.rows("courses",
                    "select=id,course_code,course_title,course_type&order=course_code")
                val assignedIds = if (profile?.optString("role") == "teacher") {
                    val assigned = ClassMateAuthApi.rows("teacher_course_assignments",
                        "select=semester_course_id&teacher_id=eq.$userId&active=eq.true")
                    (0 until assigned.length()).map {
                        assigned.getJSONObject(it).optString("semester_course_id") }.toSet()
                } else emptySet()
                Pair(Triple(semester, offerings, catalog), assignedIds)
            }.onSuccess { (loaded, assignedIds) ->
                val (semester, offerings, catalog) = loaded
                if (selectedTab != R.id.nav_manage || selectedBatchId != requestedBatch ||
                    !host.isAttachedToWindow) return@onSuccess
                host.removeAllViews()
                if (semester == null) {
                    host.addView(TextView(this@ClassMateAuthActivity).apply {
                        text = "No active semester. Publish a semester before adding courses."
                        setTextColor(getColor(R.color.cm_text_secondary))
                    })
                    return@onSuccess
                }
                host.addView(TextView(this@ClassMateAuthActivity).apply {
                    text = "SEMESTER ${semester.optInt("semester_number")} · ${offerings.length()} COURSES"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_primary))
                    setPadding(0, 8, 0, 4)
                })
                val byId = (0 until catalog.length()).associate { i ->
                    catalog.getJSONObject(i).let { it.optString("id") to it }
                }
                if (offerings.length() == 0) host.addView(TextView(this@ClassMateAuthActivity).apply {
                    text = "No courses yet. Add one to make it available throughout this batch."
                    setTextColor(getColor(R.color.cm_text_secondary))
                })
                val sorted = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                    .sortedBy { byId[it.optString("course_id")]?.optString("course_code") }
                sorted.forEach { offering ->
                    val course = byId[offering.optString("course_id")] ?: return@forEach
                    val code = course.optString("course_code")
                    val title = course.optString("course_title")
                    manageCard(host, "$code · $title",
                        "${course.optString("course_type").replaceFirstChar { it.uppercase() }}  •  Tap for options") {
                        val canEditContent = profile?.optString("role") != "teacher" ||
                            offering.optString("id") in assignedIds
                        val options = (if (canEditContent)
                            listOf("View course files", "Edit timetable", "Remove from batch")
                        else listOf("View course files", "Remove from batch")).toMutableList().apply {
                            if (profile?.optString("role") == "admin") add(1, "Edit course details")
                        }.toTypedArray()
                        AlertDialog.Builder(this@ClassMateAuthActivity)
                            .setTitle("$code · $title")
                            .setItems(options) { _, item ->
                                when (options[item]) {
                                    "View course files" -> courseFilesLauncher.launch(Intent(this@ClassMateAuthActivity,
                                        ClassMateCourseFilesActivity::class.java)
                                        .putExtra("course_id", offering.optString("id"))
                                        .putExtra("course_name", title)
                                        .putExtra("batch_id", requestedBatch).putExtra("profile_id", userId)
                                        .putExtra("can_manage", canEditContent).putExtra("role", profile?.optString("role")).putExtra("is_cr", profile?.optBoolean("is_cr") == true))
                                    "Edit course details" -> showEditCourseDialog(course)
                                    "Edit timetable" -> openPeriodEditor()
                                    "Remove from batch" -> confirmRemoveBatchCourse(
                                        offering.optString("id"), "$code · $title")
                                }
                            }.show()
                    }
                }
            }.onFailure { loading.text = "Could not load courses: ${it.message}" }
        }
    }

    private fun showEditCourseDialog(course: JSONObject, onSaved: (() -> Unit)? = null) {
        if(profile?.optString("role") != "admin") return
        val form=ClassMateFormUi(this)
        form.label("Changes apply everywhere this course is used.")
        val code=form.field("Course code").apply { setText(course.optString("course_code")); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; filters=arrayOf(android.text.InputFilter.LengthFilter(30)) }
        val name=form.field("Course name").apply { setText(course.optString("course_title")); filters=arrayOf(android.text.InputFilter.LengthFilter(200)) }
        val error=form.status()
        val dialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Edit course details")
            .setView(form.scroll).setNegativeButton("Cancel",null).setPositiveButton("Save changes",null).create()
        dialog.show()
        var saving=false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(saving) return@setOnClickListener
            val newCode=code.text.toString().trim().uppercase(java.util.Locale.ROOT)
            val newName=name.text.toString().trim()
            if(newCode.length !in 2..30) { code.error="Enter a course code (2–30 characters)"; return@setOnClickListener }
            if(newName.length !in 2..200) { name.error="Enter a course name (2–200 characters)"; return@setOnClickListener }
            if(!ClassMateAcademicCache.online(this)) { error.visibility=View.VISIBLE; error.text="Connect to save course details."; return@setOnClickListener }
            saving=true; code.isEnabled=false; name.isEnabled=false; dialog.setCancelable(false)
            dialog.getButton(-1).isEnabled=false; dialog.getButton(-2).isEnabled=false
            error.visibility=View.VISIBLE; error.text="Saving…"
            lifecycleScope.launch {
                try {
                    val result=ClassMateAuthApi.rpc("edit_course",JSONObject().put("target_course",course.getString("id")).put("target_code",newCode).put("target_title",newName))
                    val updated=result.getJSONObject("course")
                    val ids=result.getJSONArray("offering_ids")
                    ClassMateAcademicCache.renameCourse(this@ClassMateAuthActivity,(0 until ids.length()).map { ids.getString(it) }.toSet(),updated.getString("course_title"),updated.getString("course_code"))
                    academicScreens.invalidateLibrary()
                    dialog.dismiss()
                    if(homeShown && selectedTab==R.id.nav_manage) renderHomeTab(selectedTab)
                    Toast.makeText(this@ClassMateAuthActivity,"Course updated",Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } catch(e:Exception) { error.text=e.message ?: "Could not save. Your changes are still here." }
                finally { saving=false; code.isEnabled=true; name.isEnabled=true; dialog.setCancelable(true); dialog.getButton(-1).isEnabled=true; dialog.getButton(-2).isEnabled=true }
            }
        }
    }

    private fun showAddBatchCourse() {
        if (selectedBatchId.isBlank()) return
        AlertDialog.Builder(this).setTitle("Add a course")
            .setItems(arrayOf("Choose an existing course", "Create a new course")) { _, choice ->
                if (choice == 0) chooseExistingBatchCourse() else showNewBatchCourseDialog()
            }.show()
    }

    private fun chooseExistingBatchCourse() {
        runAction("Loading department courses") {
            val batch = ClassMateAuthApi.rows("batches",
                "select=department_id&id=eq.$selectedBatchId&limit=1").optJSONObject(0)
                ?: error("Batch is unavailable")
            val courses = ClassMateAuthApi.rows("courses",
                "select=id,course_code,course_title,course_type,credit&department_id=eq.${batch.getString("department_id")}&order=course_code")
            val form=ClassMateFormUi(this@ClassMateAuthActivity)
            form.label(if(courses.length()==0) "No courses in this catalog yet. Create a new course to get started." else "Shared department catalog · select a course to add it to this batch")
            val dialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMateAuthActivity)
                .setTitle("Department courses").setView(form.scroll).setNegativeButton("Done",null)
                .setPositiveButton("New course") { _,_ -> showNewBatchCourseDialog() }.create()
            for(index in 0 until courses.length()) {
                val course=courses.getJSONObject(index)
                val row=LinearLayout(this@ClassMateAuthActivity).apply {
                    gravity=android.view.Gravity.CENTER_VERTICAL
                    setPadding(0,12,0,12)
                }
                val name=TextView(this@ClassMateAuthActivity).apply {
                    text="${course.optString("course_code")} · ${course.optString("course_title")}"
                    textSize=15f; setTextColor(getColor(R.color.cm_text_primary)); minHeight=(48*resources.displayMetrics.density).toInt()
                    gravity=android.view.Gravity.CENTER_VERTICAL
                    contentDescription="Add $text to this batch"
                    setOnClickListener {
                        dialog.dismiss()
                        saveBatchCourse(course.optString("course_code"),course.optString("course_title"),course.optString("course_type"),course.optDouble("credit").takeIf { !it.isNaN() })
                    }
                }
                row.addView(name,LinearLayout.LayoutParams(0,-2,1f))
                if(profile?.optString("role")=="admin") {
                    val menu=TextView(this@ClassMateAuthActivity).apply {
                        text="⋮"; textSize=24f; gravity=android.view.Gravity.CENTER; setTextColor(getColor(R.color.cm_text_primary))
                        contentDescription="Manage ${course.optString("course_title")}"
                        setOnClickListener {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMateAuthActivity)
                                .setTitle(course.optString("course_title"))
                                .setItems(arrayOf("Edit course details","Delete from all batches")) { _, action ->
                                    dialog.dismiss()
                                    if(action==0) showEditCourseDialog(course) { chooseExistingBatchCourse() }
                                    else confirmGlobalCourseDeletion(course)
                                }.show()
                        }
                    }
                    row.addView(menu,LinearLayout.LayoutParams((48*resources.displayMetrics.density).toInt(),(48*resources.displayMetrics.density).toInt()))
                }
                form.panel.addView(row)
            }
            dialog.show()
        }
    }

    private fun confirmGlobalCourseDeletion(course: JSONObject) {
        if(profile?.optString("role")!="admin") return
        runAction("Checking linked course data") {
            val impact=ClassMateAuthApi.rpc("course_deletion_preview",JSONObject().put("target_course",course.getString("id")))
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMateAuthActivity)
                .setTitle("Permanently delete this course?")
                .setMessage("${course.optString("course_code")} · ${course.optString("course_title")}\n\nThis removes the course from ${impact.optInt("batches")} batches, ${impact.optInt("periods")} periods, ${impact.optInt("files")} files and ${impact.optInt("notices")} notices, plus teacher assignments and class changes. This cannot be undone.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Delete everywhere") { _,_ ->
                    runAction("Deleting course everywhere") {
                        val offeringIds=mutableSetOf<String>()
                        var offset=0
                        do {
                            val chunk=ClassMateAuthApi.rows("semester_courses","select=id&course_id=eq.${course.getString("id")}&order=id&limit=1000&offset=$offset")
                            for(i in 0 until chunk.length()) offeringIds.add(chunk.getJSONObject(i).getString("id"))
                            offset+=chunk.length()
                        } while(chunk.length()==1000)
                        ClassMateAuthApi.deleteGlobalCourse(course.getString("id"))
                        ClassMateAcademicCache.removeCourse(this@ClassMateAuthActivity,offeringIds)
                        academicScreens.invalidateLibrary()
                        academicScreens.invalidateNotices()
                        if(homeShown && selectedTab==R.id.nav_manage) renderHomeTab(selectedTab)
                        Toast.makeText(this@ClassMateAuthActivity,"Course and linked records deleted",Toast.LENGTH_LONG).show()
                        chooseExistingBatchCourse()
                    }
                }.show()
        }
    }

    private fun showNewBatchCourseDialog() {
        val d = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22*d).toInt(), (10*d).toInt(), (22*d).toInt(), 0)
        }
        fun field(hintText: String): EditText = EditText(this).also {
            it.hint = hintText
            it.isSingleLine = true
            panel.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        val code = field("Course code, e.g. CSE2105")
        val title = field("Course title")
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@ClassMateAuthActivity,
                android.R.layout.simple_spinner_dropdown_item, arrayOf("Theory", "Lab", "Other"))
            panel.addView(this, LinearLayout.LayoutParams(-1, (50*d).toInt()))
        }
        val credit = field("Credits (optional)").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val dialog = AlertDialog.Builder(this).setTitle("New batch course")
            .setView(panel).setPositiveButton("Add course", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enteredCode = code.text.toString().trim().uppercase()
                val enteredTitle = title.text.toString().trim()
                val enteredCredit = credit.text.toString().trim().toDoubleOrNull()
                when {
                    enteredCode.length !in 2..30 -> code.error = "Enter a course code"
                    enteredTitle.length !in 2..200 -> title.error = "Enter a course title"
                    credit.text.isNotBlank() && (enteredCredit == null || enteredCredit !in 0.01..30.0) ->
                        credit.error = "Enter credits between 0 and 30"
                    else -> {
                        dialog.dismiss()
                        saveBatchCourse(enteredCode, enteredTitle,
                            arrayOf("theory", "lab", "other")[type.selectedItemPosition], enteredCredit)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveBatchCourse(code: String, title: String, type: String, credit: Double?) {
        val batch = selectedBatchId
        runAction("Adding course") {
            ClassMateAuthApi.rpc("add_batch_course", JSONObject()
                .put("target_batch", batch).put("target_code", code)
                .put("target_title", title).put("target_type", type)
                .put("target_credit", credit ?: JSONObject.NULL))
            academicScreens.invalidateLibrary()
            ClassMateAcademicCache.invalidateSchedules(this, userId, batch)
            if (selectedBatchId == batch && selectedTab == R.id.nav_manage)
                renderHomeTab(R.id.nav_manage)
        }
    }

    private fun confirmRemoveBatchCourse(offeringId: String, name: String) {
        AlertDialog.Builder(this).setTitle("Remove $name?")
            .setMessage("This removes its timetable periods, assignments, and course notices from this batch. Delete its library files first. This cannot be undone.")
            .setPositiveButton("Remove course") { _, _ -> runAction("Removing course") {
                ClassMateAuthApi.rpcText("remove_batch_course",
                    JSONObject().put("target_offering", offeringId))
                academicScreens.invalidateLibrary()
                ClassMateAcademicCache.invalidateSchedules(this, userId, selectedBatchId)
                if (selectedTab == R.id.nav_manage) renderHomeTab(R.id.nav_manage)
            } }.setNegativeButton("Cancel", null).show()
    }

    private suspend fun renderProfileDetails(loaded: JSONObject) {
        val departmentId = loaded.optString("department_id").takeUnless { it == "null" }.orEmpty()
        val batchId = loaded.optString("batch_id").takeUnless { it == "null" }.orEmpty()
        val department = if (departmentId.isNotBlank()) runCatching {
            ClassMateAuthApi.rows("departments", "select=name&id=eq.$departmentId")
                .optJSONObject(0)?.optString("name")
        }.getOrNull() else null
        val batch = if (batchId.isNotBlank()) runCatching {
            ClassMateAuthApi.rows("batches", "select=batch_number&id=eq.$batchId")
                .optJSONObject(0)?.optInt("batch_number")
        }.getOrNull() else null
        val semester = if (batchId.isNotBlank()) runCatching {
            ClassMateAuthApi.rows("semesters", "select=semester_number&batch_id=eq.$batchId&status=eq.active")
                .optJSONObject(0)?.optInt("semester_number")
        }.getOrNull() else null
        if (!homeShown || selectedTab != R.id.nav_profile) return
        profileView.text = buildString {
            appendLine(loaded.optString("full_name"))
            appendLine(loaded.optString("email"))
            appendLine("Role: ${loaded.optString("role")}")
            appendLine("Status: ${loaded.optString("verification_status")}")
            if (department != null) appendLine("Department: $department")
            if (!loaded.isNull("student_id")) appendLine("Student ID: ${loaded.optString("student_id")}")
            if (!loaded.isNull("academic_session")) appendLine("Academic session: ${ClassMateAcademicSession.format(loaded.optString("academic_session"))}")
            if (batch != null) appendLine("Batch: $batch")
            if (semester != null) appendLine("Current semester: $semester")
            if (loaded.optString("role") == "student")
                appendLine("CR: ${if (loaded.optBoolean("is_cr")) "Active" else "No"}")
        }
    }

    private fun greeting(): String = when (java.time.LocalTime.now().hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else -> "Good Night"
    }

    private fun showDaySelector() {
        val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val row = LinearLayout(this)
        days.forEachIndexed { index, day ->
            val view = layoutInflater.inflate(R.layout.item_day_card, row, false)
            view.findViewById<TextView>(R.id.tvDayShort).text = day
            view.findViewById<TextView>(R.id.tvDayDate).text =
                LocalDate.now().minusDays(
                    (LocalDate.now().dayOfWeek.value % 7 - index).toLong()
                ).dayOfMonth.toString()
            if (index == selectedDay) {
                view.setBackgroundResource(R.drawable.bg_day_card_selected)
                view.findViewById<View>(R.id.vDayIndicator).visibility = View.VISIBLE
            }
            view.setOnClickListener { selectedDay = index; renderHomeTab(R.id.nav_timetable) }
            row.addView(view)
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotificationPermission &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderActions(loaded: JSONObject) {
        actions.removeAllViews()
        welcomeUi?.let { ui ->
            if (loaded.optString("verification_status") != "active") {
                if (loaded.optString("role") == "student" && loaded.isNull("department_id"))
                    ui.action("Choose my department  →", parent = actions) { showDepartments() }
                ui.action("Check profile status", false, actions) { runAction("Checking profile") { loadProfile() } }
                return
            }
        }
        button("Refresh profile", actions) { runAction("Loading profile") { loadProfile() } }
        val role = loaded.optString("role")
        val state = loaded.optString("verification_status")
        if (role == "student" && loaded.isNull("department_id")) {
            button("Choose department / complete onboarding", actions) { showDepartments() }
            return
        }
        if (state != "active") return
        button("Notices", actions) {
            readRows("notices", "select=id,title,body,published_at&order=published_at.desc&limit=30")
        }
        button("Class changes", actions) { readRows("class_changes", "select=*&limit=30") }
        button("Routine", actions) {
            readRows("routine_slots", "select=semester_course_id,day_of_week,start_time,end_time,room,type" +
                "&order=day_of_week.asc,start_time.asc&limit=50")
        }
        button("Courses", actions) { readRows("semester_courses", "select=*&limit=50") }
        button("Files", actions) { showFiles() }
        button("Bus schedule", actions) { readRows("bus_schedules", "select=*&limit=30") }
        if (role == "admin" || role == "teacher" || loaded.optBoolean("is_cr")) {
            button("Post batch notice", actions) { showNoticeForm() }
            button("Upload protected file", actions) { showUploadForm() }
        }
        if (role == "admin" || role == "teacher")
            button("Post class change", actions) { showClassChangeForm() }
        if (role == "admin") {
            button("Manage: departments, batches, pending users", actions) { showManageMenu() }
        }
    }

    private var unreadActivity: ClassMateUnreadActivity? = null

    override fun onResume() {
        super.onResume()
        unreadActivity?.refresh()
        if(homeShown && selectedTab==R.id.nav_profile) renderHomeTab(selectedTab)
        // Profile setup belongs to explicit sign-in, never ordinary app resumes.
        if(homeShown && welcomeStep==0 && ClassMateAuthApi.hasSavedSession()) registerFcmToken()
    }

    private fun registerFcmToken() {
        if (!AppPreferences(this).isNotificationsEnabled() || !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if(notificationRegistrationBusy) return
        notificationRegistrationBusy=true
        FirebaseMessaging.getInstance().token.addOnFailureListener {
            notificationRegistrationBusy=false
            findViewById<TextView>(R.id.tvNotificationStatus)?.text="Could not connect. Tap to retry."
        }.addOnSuccessListener { token ->
            if (token.isNotBlank() && ClassMateAuthApi.hasSavedSession()) {
                lifecycleScope.launch {
                    runCatching {
                        if(AppPreferences(this@ClassMateAuthActivity).isNotificationsEnabled()) {
                            ClassMateAuthApi.registerDeviceToken(token)
                            if(!AppPreferences(this@ClassMateAuthActivity).isNotificationsEnabled()) ClassMateAuthApi.unregisterDeviceToken(token)
                        }
                    }
                        .onFailure { findViewById<TextView>(R.id.tvNotificationStatus)?.text="Could not connect. Tap to retry." }
                        .onSuccess { findViewById<TextView>(R.id.tvNotificationStatus)?.text="Notices and academic updates" }
                    notificationRegistrationBusy=false
                }
            } else notificationRegistrationBusy=false
        }
    }
    private var notificationRegistrationBusy=false
    private fun enableNotificationsFromProfile() {
        AppPreferences(this).setNotificationsEnabled(true)
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            val asked=welcomeProgress.getBoolean("notification_permission_requested",false)
            if(asked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
            else { welcomeProgress.edit().putBoolean("notification_permission_requested",true).apply(); notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else if(!notificationsEnabled()) openNotificationSettings()
        else registerFcmToken()
    }

    private fun showDepartments() {
        runAction("Loading departments") {
            val departments = ClassMateAuthApi.rows(
                "departments", "select=id,name,code,email_prefix,session_offset&is_active=eq.true&order=name"
            )
            if (departments.length() == 0) error("No active departments")
            val names = Array(departments.length()) { departments.getJSONObject(it).getString("name") }
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle("Select department")
                .setItems(names) { _, which -> onboard(departments.getJSONObject(which)) }
                .show()
        }
    }

    private fun onboard(department: JSONObject) {
        val id = department.getString("id")
        if (!department.isNull("email_prefix")) {
            AlertDialog.Builder(this).setTitle("Confirm department")
                .setMessage("Use ${department.getString("name")}? Your student ID, session, and batch will be calculated by the server from your university email.")
                .setPositiveButton("Continue") { _, _ ->
                    runAction("Completing onboarding") {
                        ClassMateAuthApi.rpc("complete_student_onboarding",
                            JSONObject().put("selected_department", id))
                        loadProfile()
                    }
                }.setNegativeButton("Cancel", null).show()
        } else {
            form("Manual department request", listOf("Student ID", "Running batch", "Academic session (e.g. 24-25)")) { values ->
                runAction("Submitting for verification") {
                    ClassMateAuthApi.rpc("complete_student_onboarding", JSONObject()
                        .put("selected_department", id)
                        .put("entered_student_id", values[0])
                        .put("entered_batch_number", values[1].toInt())
                        .put("entered_session", ClassMateAcademicSession.end(values[2])))
                    loadProfile()
                }
            }
        }
    }

    private fun readRows(table: String, query: String, forCurrentBatch: Boolean = false) {
        val targetResult = resultView
        val targetStatus = status
        val targetCards = if (forCurrentBatch) academicCards else null
        val batchAtRequest = selectedBatchId
        val title = when (table) {
            "routine_slots" -> "timetable"
            "semester_courses" -> "courses"
            "file_metadata" -> "library"
            "class_changes" -> "class changes"
            "bus_schedules" -> "bus schedule"
            else -> table
        }
        runAction("Loading $title") {
            val scopedQuery = if (forCurrentBatch) {
                check(selectedBatchId.isNotBlank()) { "Select a batch first" }
                when (table) {
                    "notices", "file_metadata", "class_changes" -> "$query&batch_id=eq.$selectedBatchId"
                    "semester_courses", "routine_slots" -> {
                        val semesters = ClassMateAuthApi.rows("semesters",
                            "select=id&batch_id=eq.$selectedBatchId&status=eq.active&limit=1")
                        if (semesters.length() == 0) "$query&id=eq.00000000-0000-0000-0000-000000000000"
                        else if (table == "semester_courses")
                            "$query&semester_id=eq.${semesters.getJSONObject(0).getString("id")}" 
                        else {
                            val courses = ClassMateAuthApi.rows("semester_courses",
                                "select=id&semester_id=eq.${semesters.getJSONObject(0).getString("id")}")
                            val ids = (0 until courses.length()).map { courses.getJSONObject(it).getString("id") }
                            if (ids.isEmpty()) "$query&id=eq.00000000-0000-0000-0000-000000000000"
                            else "$query&semester_course_id=in.(${ids.joinToString(",")})"
                        }
                    }
                    else -> query
                }
            } else query
            val rows = ClassMateAuthApi.rows(table, scopedQuery)
            if (forCurrentBatch && (!homeShown || selectedBatchId != batchAtRequest ||
                    targetCards !== academicCards)) return@runAction
            val emptyMessage = when (table) {
                "notices" -> "No notices yet."
                "routine_slots" -> "No timetable is published yet."
                "semester_courses" -> "No courses are published yet."
                "file_metadata" -> "No academic files yet."
                "class_changes" -> "No class changes yet."
                "bus_schedules" -> "No bus schedule is available yet."
                else -> "No $table visible to this account."
            }
            if (forCurrentBatch && table in setOf("notices", "routine_slots", "semester_courses",
                    "file_metadata", "class_changes")) {
                targetResult.text = if (rows.length() == 0) emptyMessage else ""
                renderAcademicCards(table, rows, targetCards!!)
            } else targetResult.text = if (rows.length() == 0) emptyMessage
                else formatAcademicRows(table, rows)
            targetStatus.text = ""
            targetStatus.visibility = View.GONE
        }
    }

    private suspend fun renderAcademicCards(table: String, rows: JSONArray, target: LinearLayout) {
        target.removeAllViews()
        val courseNames = if (table == "routine_slots" || table == "semester_courses") {
            val courses = ClassMateCourses.catalog(selectedBatchId)
            (0 until courses.length()).associate { i ->
                val course = courses.getJSONObject(i)
                course.getString("id") to "${course.getString("course_code")} · ${course.getString("course_title")}"
            }
        } else emptyMap()
        val offeringNames = if (table == "routine_slots") {
            val offerings = ClassMateAuthApi.rows("semester_courses", "select=id,course_id")
            (0 until offerings.length()).associate { i ->
                val offering = offerings.getJSONObject(i)
                offering.getString("id") to (courseNames[offering.getString("course_id")] ?: "Course")
            }
        } else emptyMap()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val heading = when (table) {
                "notices", "file_metadata" -> row.optString("title")
                "routine_slots" -> offeringNames[row.optString("semester_course_id")] ?: "Class"
                "semester_courses" -> courseNames[row.optString("course_id")] ?: "Course"
                else -> row.optString("kind").replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            val detail = when (table) {
                "notices" -> row.optString("body")
                "file_metadata" -> "${row.optString("file_type")} · ${row.optLong("size_bytes") / 1024} KB"
                "routine_slots" -> "${row.optString("start_time").take(5)}–${row.optString("end_time").take(5)}" +
                    " · ${row.optString("room")} · ${row.optString("type")}" 
                "class_changes" -> "${row.optString("effective_date")} · ${row.optString("details")}" 
                else -> "Current semester"
            }
            val card = MaterialCardView(this).apply {
                radius = 20 * resources.displayMetrics.density
                cardElevation = 2 * resources.displayMetrics.density
                setCardBackgroundColor(ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_surface))
                strokeColor = ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_border)
                strokeWidth = (resources.displayMetrics.density).toInt()
                val margin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, margin, 0, margin) }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (18 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            body.addView(TextView(this).apply {
                text = heading
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            body.addView(TextView(this).apply {
                text = detail
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_text_secondary))
            })
            card.addView(body)
            if (table == "file_metadata") card.setOnClickListener {
                runAction("Opening protected file") {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse(ClassMateAuthApi.signedResource(row.getString("id")))))
                }
            }
            target.addView(card)
        }
    }

    private suspend fun formatAcademicRows(table: String, rows: JSONArray): String {
        val courseNames = if (table == "semester_courses" || table == "routine_slots") {
            val courses = ClassMateAuthApi.rows("courses",
                "select=id,course_code,course_title&order=course_code")
            (0 until courses.length()).associate { i ->
                val course = courses.getJSONObject(i)
                course.getString("id") to
                    "${course.getString("course_code")} · ${course.getString("course_title")}"
            }
        } else emptyMap()
        if (table == "semester_courses") {
            return buildString {
                appendLine("${rows.length()} active course(s)")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    appendLine("\n${courseNames[row.optString("course_id")] ?: "Course"}")
                }
            }
        }
        val routineCourseNames = if (table == "routine_slots") {
            val semesterCourses = ClassMateAuthApi.rows("semester_courses", "select=id,course_id")
            (0 until semesterCourses.length()).associate { i ->
                val offering = semesterCourses.getJSONObject(i)
                offering.getString("id") to
                    (courseNames[offering.getString("course_id")] ?: "Course")
            }
        } else emptyMap()
        if (table !in setOf("notices", "class_changes", "routine_slots", "bus_schedules", "file_metadata"))
            return rows.pretty(40)
        return buildString {
            appendLine("${rows.length()} visible item(s)")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                appendLine()
                when (table) {
                    "notices" -> {
                        appendLine(row.optString("title"))
                        appendLine(row.optString("body"))
                        appendLine(row.optString("published_at").replace('T', ' ').take(16))
                    }
                    "class_changes" -> {
                        appendLine("${row.optString("kind").replace('_', ' ')} · ${row.optString("effective_date")}")
                        appendLine(row.optString("details"))
                    }
                    "routine_slots" -> {
                        val day = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday",
                            "Thursday", "Friday", "Saturday")
                        appendLine("${day.getOrElse(row.optInt("day_of_week", -1)) { "Day" }} · " +
                            "${row.optString("start_time").take(5)}–${row.optString("end_time").take(5)}")
                        appendLine(routineCourseNames[row.optString("semester_course_id")] ?: "Course")
                        appendLine("${row.optString("type")} · ${row.optString("room")}")
                    }
                    "bus_schedules" -> {
                        appendLine("${row.optString("route_name")} · ${row.optString("departure_time")}")
                        appendLine("${row.optString("origin")} → ${row.optString("destination")}")
                    }
                    "file_metadata" -> {
                        appendLine(row.optString("title"))
                        appendLine("${row.optString("file_type")} · ${row.optLong("size_bytes") / 1024} KB")
                    }
                }
            }
        }
    }

    private fun showFiles() {
        runAction("Loading files") {
            val rows = ClassMateAuthApi.rows("file_metadata",
                "select=id,title,file_type,size_bytes,created_at&status=eq.active&batch_id=eq.$selectedBatchId&order=created_at.desc&limit=50")
            resultView.text = if (rows.length() == 0) "No files visible to this account." else rows.pretty(50)
            if (rows.length() > 0) {
                val titles = Array(rows.length()) { rows.getJSONObject(it).optString("title") }
                AlertDialog.Builder(this@ClassMateAuthActivity).setTitle("Open protected file")
                    .setItems(titles) { _, which ->
                        val id = rows.getJSONObject(which).getString("id")
                        runAction("Getting signed link") {
                            val url = ClassMateAuthApi.signedResource(id)
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }.show()
            }
        }
    }

    private fun chooseBatch(onSelected: (String) -> Unit) {
        if (selectedBatchId.isNotBlank()) {
            onSelected(selectedBatchId)
            return
        }
        runAction("Loading available batches") {
            val batches = ClassMateAuthApi.rows("batches",
                "select=id,batch_number,academic_session,department_id&is_active=eq.true&order=batch_number")
            if (batches.length() == 0) error("No active batch is visible to this account")
            val departments = ClassMateAuthApi.rows("departments", "select=id,code")
            val codes = (0 until departments.length()).associate { i ->
                departments.getJSONObject(i).getString("id") to
                    departments.getJSONObject(i).getString("code").uppercase()
            }
            val labels = Array(batches.length()) { i ->
                val batch = batches.getJSONObject(i)
                "${codes[batch.getString("department_id")] ?: "Department"} batch " +
                    "${batch.getInt("batch_number")} · session ${ClassMateAcademicSession.format(batch.getInt("academic_session"))}"
            }
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle("Choose batch")
                .setItems(labels) { _, index ->
                    onSelected(batches.getJSONObject(index).getString("id"))
                }.show()
        }
    }

    private fun chooseActiveCourse(batchId: String, onSelected: (String) -> Unit) {
        runAction("Loading active courses") {
            val semesters = ClassMateAuthApi.rows("semesters",
                "select=id&batch_id=eq.$batchId&status=eq.active&limit=1")
            if (semesters.length() == 0) error("This batch has no active semester")
            val semesterId = semesters.getJSONObject(0).getString("id")
            val semesterCourses = ClassMateAuthApi.rows("semester_courses",
                "select=id,course_id&semester_id=eq.$semesterId")
            val available = if (profile?.optString("role") == "teacher") {
                val assignments = ClassMateAuthApi.rows("teacher_course_assignments",
                    "select=semester_course_id&teacher_id=eq.$userId&active=eq.true")
                val assignedIds = (0 until assignments.length()).map { i ->
                    assignments.getJSONObject(i).getString("semester_course_id")
                }.toSet()
                (0 until semesterCourses.length()).map { semesterCourses.getJSONObject(it) }
                    .filter { it.getString("id") in assignedIds }
            } else (0 until semesterCourses.length()).map { semesterCourses.getJSONObject(it) }
            if (available.isEmpty()) error("No assigned active course is available")
            val courses = ClassMateCourses.catalog(selectedBatchId)
            val names = (0 until courses.length()).associate { i ->
                val course = courses.getJSONObject(i)
                course.getString("id") to
                    "${course.getString("course_code")} · ${course.getString("course_title")}"
            }
            val labels = available.map { names[it.getString("course_id")] ?: "Course" }.toTypedArray()
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle("Choose active course")
                .setItems(labels) { _, index -> onSelected(available[index].getString("id")) }
                .show()
        }
    }

    private val publishLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && homeShown) {
            academicScreens.invalidateLibrary()
            academicScreens.invalidateNotices()
            ClassMateAcademicCache.invalidateSchedules(this, userId, selectedBatchId)
            renderHomeTab(selectedTab)
        }
    }
    private val aiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK && homeShown) {
            academicScreens.invalidateLibrary()
            academicScreens.invalidateNotices()
            ClassMateAcademicCache.invalidateSchedules(this,userId,selectedBatchId)
            renderHomeTab(selectedTab)
        }
    }
    private fun openPublishPage(upload: Boolean) = chooseBatch { batchId ->
        publishLauncher.launch(Intent(this, ClassMatePublishActivity::class.java)
            .putExtra("batch_id", batchId).putExtra("batch_label", selectedBatchLabel)
            .putExtra("role", profile?.optString("role")).putExtra("profile_id", userId)
            .putExtra("upload", upload))
    }
    private fun openFileEditor(file: JSONObject) {
        publishLauncher.launch(Intent(this, ClassMatePublishActivity::class.java)
            .putExtra("batch_id", selectedBatchId).putExtra("batch_label", selectedBatchLabel).putExtra("role", profile?.optString("role"))
            .putExtra("profile_id", userId).putExtra("upload", true).putExtra("resource", file.toString()))
    }
    private fun showNoticeForm() = openPublishPage(false)
    private fun showUploadForm() = openPublishPage(true)

    private fun showClassChangeForm() {
        chooseBatch { batchId -> chooseActiveCourse(batchId) { courseId ->
            val kinds = arrayOf("Cancel a class", "Reschedule", "Change room", "Change time")
            val codes = arrayOf("cancelled", "rescheduled", "room_changed", "time_changed")
            AlertDialog.Builder(this).setTitle("Class change")
                .setItems(kinds) { _, kind ->
                    if (kind == 0) {
                        AlertDialog.Builder(this).setTitle("When is the class cancelled?")
                            .setItems(arrayOf("Today", "Tomorrow")) { _, day ->
                                runAction("Posting cancellation") {
                                    ClassMateAuthApi.rpc("post_cancellation_notice", JSONObject()
                                        .put("target_batch", batchId).put("target_course", courseId)
                                        .put("change_date", LocalDate.now().plusDays(day.toLong()).toString()))
                                    selectedTab = R.id.nav_notices
                                    renderHomeTab(selectedTab)
                                }
                            }.show()
                    } else {
                        val today = LocalDate.now()
                        android.app.DatePickerDialog(this, { _, year, month, day ->
                            val date = LocalDate.of(year, month + 1, day)
                            form(kinds[kind], listOf("What changed?")) { values ->
                                if (values[0].isBlank()) {
                                    Toast.makeText(this, "Describe the change", Toast.LENGTH_SHORT).show()
                                } else runAction("Posting class change") {
                                    ClassMateAuthApi.rpc("post_class_change", JSONObject()
                                        .put("target_batch", batchId).put("target_course", courseId)
                                        .put("change_kind", codes[kind])
                                        .put("change_date", date.toString())
                                        .put("change_details", values[0]))
                                    resultView.text = "Class change posted."
                                }
                            }
                        }, today.year, today.monthValue - 1, today.dayOfMonth).show()
                    }
                }
                .show()
        } }
    }

    private fun showManageMenu(section: String = "Manage", selected: List<Int>? = null) {
        val actions = arrayOf("View departments", "View batches", "View pending users",
            "Create department", "Create batch", "Approve student", "Reject student",
            "Assign CR", "Revoke CR", "Publish semester", "Configure department",
            "Allowlist teacher", "Assign teacher to course", "Clone semester",
            "View semesters", "View courses", "View roster", "View teacher assignments",
            "Create course", "Add semester course", "Add routine slot", "Save bus schedule")
        val options = selected?.map { actions[it] }?.toTypedArray() ?: actions
        AlertDialog.Builder(this).setTitle(section).setItems(options) { _, optionIndex ->
            val index = selected?.get(optionIndex) ?: optionIndex
            when (index) {
                0 -> showManageRecords("Departments", "departments",
                    "select=name,code,is_active,email_prefix,session_offset&order=name") {
                    "${it.optString("name")} · ${it.optString("code").uppercase()}" to
                        if (it.optBoolean("is_active")) "Active" else "Inactive"
                }
                1 -> showManageRecords("Batches", "batches",
                    "select=batch_number,academic_session,is_active&order=batch_number") {
                    "Batch ${it.optInt("batch_number")}" to
                        "Session ${ClassMateAcademicSession.format(it.optInt("academic_session"))} · ${if (it.optBoolean("is_active")) "Active" else "Inactive"}"
                }
                2 -> showManageRecords("Pending students", "profiles",
                    "select=full_name,email,student_id,verification_status&verification_status=eq.pending") {
                    it.optString("full_name").ifBlank { it.optString("email") } to
                        "${it.optString("email")} · ${it.optString("student_id")}"
                }
                3 -> adminForm("Create department", "create_department", listOf("Name", "Code"),
                    listOf("target_name", "target_code"))
                4 -> pickRow("departments", "select=id,name&is_active=eq.true&order=name",
                    "Choose department", { it.optString("name") }) { department ->
                    form("Create batch in ${department.optString("name")}",
                        listOf("Batch number", "Academic session (e.g. 24-25)")) { values ->
                        runAction("Creating batch") {
                            ClassMateAuthApi.rpc("create_batch", JSONObject()
                                .put("target_department", department.getString("id"))
                                .put("target_batch_number", values[0].toInt())
                                .put("target_session", ClassMateAcademicSession.end(values[1])))
                            renderHomeTab(R.id.nav_manage)
                        }
                    }
                }
                5 -> pickRow("profiles", "select=id,email,student_id,academic_session,department_id&verification_status=eq.pending",
                    "Choose pending student", { it.optString("email") }) { student ->
                    pickRow("batches", "select=id,batch_number,academic_session&department_id=eq.${student.getString("department_id")}&is_active=eq.true",
                        "Choose verified batch", { "Batch ${it.optInt("batch_number")} · Session ${ClassMateAcademicSession.format(it.optInt("academic_session"))}" }) { batch ->
                        form("Approve ${student.optString("email")}", listOf("Student ID", "Session"),
                            listOf(student.optString("student_id"), ClassMateAcademicSession.format(batch.optString("academic_session")))) { values ->
                            runAction("Approving student") {
                                ClassMateAuthApi.rpc("approve_student_profile", JSONObject()
                                    .put("target_profile", student.getString("id"))
                                    .put("corrected_student_id", values[0])
                                    .put("target_batch", batch.getString("id"))
                                    .put("corrected_session", ClassMateAcademicSession.end(values[1])))
                                resultView.text = "Student approved."
                            }
                        }
                    }
                }
                6 -> pickRow("profiles", "select=id,email&verification_status=eq.pending",
                    "Choose pending student", { it.optString("email") }) { student ->
                    form("Reject ${student.optString("email")}", listOf("Reason")) { values ->
                        runAction("Rejecting student") {
                            ClassMateAuthApi.rpc("reject_student_profile", JSONObject()
                                .put("target_profile", student.getString("id"))
                                .put("reason", values[0]))
                            resultView.text = "Student rejected."
                        }
                    }
                }
                7 -> pickRow("profiles", "select=id,email,batch_id&role=eq.student&verification_status=eq.active",
                    "Choose student", { it.optString("email") }) { student ->
                    AlertDialog.Builder(this).setTitle("Assign ${student.optString("email")} as CR")
                        .setItems(arrayOf("Until revoked", "For 30 days", "For 90 days")) { _, duration ->
                            val expiry = when (duration) {
                                1 -> java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS).toString()
                                2 -> java.time.Instant.now().plus(90, java.time.temporal.ChronoUnit.DAYS).toString()
                                else -> null
                            }
                            runAction("Assigning CR") {
                                ClassMateAuthApi.rpc("assign_cr", JSONObject()
                                    .put("target_profile", student.getString("id"))
                                    .put("target_batch", student.getString("batch_id"))
                                    .put("valid_until", expiry ?: JSONObject.NULL))
                                resultView.text = "CR access assigned to ${student.optString("email")}."
                            }
                        }.show()
                }
                8 -> pickRow("profiles", "select=id,email&is_cr=eq.true", "Choose CR",
                    { it.optString("email") }) { student ->
                    runAction("Revoking CR") {
                        ClassMateAuthApi.rpc("revoke_cr", JSONObject()
                            .put("target_profile", student.getString("id")))
                        resultView.text = "CR access revoked."
                    }
                }
                9 -> pickRow("semesters", "select=id,semester_number&batch_id=eq.$selectedBatchId&status=eq.not_started",
                    "Choose semester to publish", { "Semester ${it.optInt("semester_number")}" }) { semester ->
                    AlertDialog.Builder(this).setTitle("Publish semester ${semester.optInt("semester_number")}")
                        .setMessage("This activates the semester for its batch and completes the previous active semester.")
                        .setPositiveButton("Publish") { _, _ ->
                            runAction("Publishing semester") {
                                ClassMateAuthApi.rpc("publish_semester", JSONObject()
                                    .put("target_semester", semester.getString("id")))
                                resultView.text = "Semester ${semester.optInt("semester_number")} is now active."
                                renderHomeTab(R.id.nav_manage)
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
                10 -> pickRow("departments", "select=id,name,email_prefix,session_offset,is_active&order=name",
                    "Choose department", { it.optString("name") }) { department ->
                    form("Configure ${department.optString("name")}",
                        listOf("Email prefix (optional)", "Session offset (optional)"),
                        listOf(department.optString("email_prefix").takeUnless { it == "null" }.orEmpty(),
                            department.optString("session_offset").takeUnless { it == "null" }.orEmpty())) { v ->
                        AlertDialog.Builder(this).setTitle("Department status")
                            .setItems(arrayOf("Active", "Inactive")) { _, selectedStatus ->
                                runAction("Configuring department") {
                                    ClassMateAuthApi.rpc("configure_department", JSONObject()
                                        .put("target_department", department.getString("id"))
                                        .put("target_prefix", v[0])
                                        .put("target_offset", v[1].toIntOrNull() ?: JSONObject.NULL)
                                        .put("target_active", selectedStatus == 0))
                                    renderHomeTab(R.id.nav_manage)
                                }
                            }.show()
                    }
                }
                11 -> pickRow("departments", "select=id,name&order=name", "Choose department",
                    { it.optString("name") }) { department ->
                    form("Teacher university email", listOf("University email")) { v ->
                        AlertDialog.Builder(this).setTitle("Teacher access")
                            .setItems(arrayOf("Allow teacher", "Remove access")) { _, choice ->
                                runAction("Updating teacher access") {
                                    ClassMateAuthApi.rpc("set_teacher_allowlist", JSONObject()
                                        .put("target_email", v[0])
                                        .put("target_department", department.getString("id"))
                                        .put("target_active", choice == 0))
                                    resultView.text = "Teacher access updated."
                                }
                            }.show()
                    }
                }
                12 -> pickRow("profiles",
                    "select=id,full_name,email&role=eq.teacher&verification_status=eq.active&order=full_name",
                    "Choose teacher", { it.optString("full_name").ifBlank { it.optString("email") } }) { teacher ->
                    chooseActiveCourse(selectedBatchId) { courseId ->
                        runAction("Assigning teacher") {
                            ClassMateAuthApi.rpc("assign_teacher_to_course", JSONObject()
                                .put("target_teacher", teacher.getString("id"))
                                .put("target_semester_course", courseId))
                            renderHomeTab(R.id.nav_manage)
                        }
                    }
                }
                13 -> pickRow("semesters",
                    "select=id,semester_number,status&batch_id=eq.$selectedBatchId&status=in.(active,completed)&order=semester_number",
                    "Copy from semester", { "Semester ${it.optInt("semester_number")} · ${it.optString("status")}" }) { source ->
                    pickRow("semesters",
                        "select=id,semester_number&batch_id=eq.$selectedBatchId&status=eq.not_started&order=semester_number",
                        "Copy into draft semester", { "Semester ${it.optInt("semester_number")}" }) { target ->
                        AlertDialog.Builder(this).setTitle("Copy semester plan?")
                            .setMessage("Copy courses, timetable periods, and teacher assignments into semester ${target.optInt("semester_number")}. The destination must be empty.")
                            .setPositiveButton("Copy") { _, _ -> runAction("Cloning semester") {
                                ClassMateAuthApi.rpcText("clone_semester", JSONObject()
                                    .put("from_id", source.getString("id"))
                                    .put("to_id", target.getString("id")))
                                resultView.text = "Semester plan copied."
                            } }.setNegativeButton("Cancel", null).show()
                    }
                }
                14 -> showManageRecords("Semesters", "semesters",
                    "select=semester_number,status&batch_id=eq.$selectedBatchId&order=semester_number") {
                    "Semester ${it.optInt("semester_number")}" to it.optString("status").replace('_', ' ')
                }
                15 -> renderHomeTab(R.id.nav_manage)
                16 -> showManageRecords("People", "profiles",
                    "select=full_name,email,role,verification_status,is_cr&order=email") {
                    it.optString("full_name").ifBlank { it.optString("email") } to
                        "${it.optString("role")}${if (it.optBoolean("is_cr")) " · CR" else ""} · ${it.optString("verification_status")}" 
                }
                17 -> showTeacherAssignments()
                18, 19 -> showAddBatchCourse()
                20 -> openPeriodEditor()
                21 -> showBusScheduleManager()
            }
        }.show()
    }

    private fun adminForm(title: String, rpc: String, labels: List<String>,
                          keys: List<String>, numeric: Set<Int> = emptySet()) {
        form(title, labels) { values ->
            runAction(title) {
                val args = JSONObject()
                values.forEachIndexed { index, value ->
                    args.put(keys[index], if (value.isBlank()) JSONObject.NULL
                        else if (index in numeric) value.toInt() else value)
                }
                ClassMateAuthApi.rpc(rpc, args)
                resultView.text = "$title saved."
            }
        }
    }

    private fun showManageRecords(title: String, table: String, query: String,
                                  display: (JSONObject) -> Pair<String, String>) {
        runAction("Loading $title") {
            val rows = ClassMateAuthApi.rows(table, query)
            if (rows.length() == 0) error("No $title available")
            val descriptions = (0 until rows.length()).map { display(rows.getJSONObject(it)) }
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle(title)
                .setItems(descriptions.map { (name, detail) -> "$name\n$detail" }.toTypedArray()) {
                    _, position ->
                    val (name, detail) = descriptions[position]
                    AlertDialog.Builder(this@ClassMateAuthActivity).setTitle(name)
                        .setMessage(detail).setPositiveButton("Done", null).show()
                }.setNegativeButton("Close", null).show()
        }
    }

    private fun showTeacherAssignments() {
        runAction("Loading teacher assignments") {
            val assignments = ClassMateAuthApi.rows("teacher_course_assignments",
                "select=teacher_id,semester_course_id&active=eq.true&limit=100")
            val people = ClassMateAuthApi.rows("profiles", "select=id,full_name,email&role=eq.teacher")
            val offerings = ClassMateAuthApi.rows("semester_courses", "select=id,course_id")
            val courses = ClassMateCourses.catalog(selectedBatchId)
            val teachersById = (0 until people.length()).associate { i ->
                people.getJSONObject(i).let { it.optString("id") to
                    it.optString("full_name").ifBlank { it.optString("email") } }
            }
            val offeringsById = (0 until offerings.length()).associate { i ->
                offerings.getJSONObject(i).let { it.optString("id") to it.optString("course_id") }
            }
            val coursesById = (0 until courses.length()).associate { i ->
                courses.getJSONObject(i).let { it.optString("id") to
                    "${it.optString("course_code")} · ${it.optString("course_title")}" }
            }
            val labels = (0 until assignments.length()).map { i ->
                assignments.getJSONObject(i).let { a ->
                    "${teachersById[a.optString("teacher_id")] ?: "Teacher"}\n" +
                        (offeringsById[a.optString("semester_course_id")]?.let { coursesById[it] }
                            ?: "Course")
                }
            }
            if (labels.isEmpty()) error("No teacher assignments yet")
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle("Teacher assignments")
                .setItems(labels.toTypedArray(), null).setPositiveButton("Done", null).show()
        }
    }

    private fun showBusScheduleManager() {
        AlertDialog.Builder(this).setTitle("Bus schedule")
            .setItems(arrayOf("Add a route", "Edit a route")) { _, choice ->
                if (choice == 0) showBusScheduleDialog(null)
                else pickRow("bus_schedules",
                    "select=id,route_name,departure_time,origin,destination,weekdays,notes,active&order=departure_time",
                    "Choose route", { "${it.optString("route_name")} · ${it.optString("departure_time").take(5)}" }) {
                    showBusScheduleDialog(it)
                }
            }.show()
    }

    private val busEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            ClassMateAcademicCache.invalidateSchedules(this, userId, selectedBatchId)
            if (homeShown) renderHomeTab(selectedTab)
        }
    }
    private fun showBusScheduleDialog(existing: JSONObject?,dayKind: String?=null) {
        busEditorLauncher.launch(Intent(this, ClassMateBusEditorActivity::class.java).putExtra("day_kind",dayKind).putExtra("profile_id", userId).putExtra("batch_id", selectedBatchId).apply { existing?.let { putExtra("bus", it.toString()) } })
    }

    private fun pickRow(table: String, query: String, title: String,
                        display: (JSONObject) -> String, onSelected: (JSONObject) -> Unit) {
        runAction("Loading $title") {
            val rows = ClassMateAuthApi.rows(table, query)
            if (rows.length() == 0) error("No matching records")
            val choices = Array(rows.length()) { display(rows.getJSONObject(it)) }
            AlertDialog.Builder(this@ClassMateAuthActivity).setTitle(title)
                .setItems(choices) { _, index -> onSelected(rows.getJSONObject(index)) }.show()
        }
    }

    private fun form(title: String, labels: List<String>, initial: List<String> = emptyList(),
                     submit: (List<String>) -> Unit) {
        val d = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22*d).toInt(), (8*d).toInt(), (22*d).toInt(), (8*d).toInt())
        }
        val fields = labels.mapIndexed { index, label ->
            EditText(this).apply {
                hint = label
                setText(initial.getOrNull(index) ?: "")
                setSingleLine(label != "Body")
                if (label.contains("email", true)) inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or
                    android.text.InputType.TYPE_CLASS_TEXT
                if (label.contains("number", true) ||
                    label.contains("offset", true)) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                panel.addView(TextView(this@ClassMateAuthActivity).apply {
                    text = label
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_secondary))
                    setPadding(0, (12*d).toInt(), 0, 0)
                })
                panel.addView(this, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("Save") { _, _ -> submit(fields.map { it.text.toString().trim() }) }
            .setNegativeButton("Cancel", null).show()
    }


    private fun runAction(label: String, action: suspend () -> Unit) {
        if (ClassMateAuthApi.accessToken == null && label != "Signing in") {
            status.visibility = View.VISIBLE
            status.text = "Sign in first."
            return
        }
        status.visibility = View.VISIBLE
        status.text = "$label…"
        lifecycleScope.launch {
            runCatching { action() }
                .onSuccess {
                    if (status.text == "$label…") status.text = "$label complete."
                    if (homeShown && selectedTab != R.id.nav_manage)
                        android.widget.Toast.makeText(this@ClassMateAuthActivity,
                            "$label complete.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    authBusy = false
                    status.text = "$label failed: ${it.message}"
                    if (!homeShown && ClassMateAuthApi.accessToken != null) {
                        actions.removeAllViews()
                        welcomeUi?.action("Retry profile setup", parent = actions) {
                            runAction("Loading profile") { loadProfile() }
                        }
                        welcomeUi?.action("Use another edumail", false, actions) { signOut() }
                    }
                    if (homeShown)
                        android.widget.Toast.makeText(this@ClassMateAuthActivity,
                            status.text, android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun JSONArray.pretty(limit: Int): String = buildString {
        appendLine("${length()} visible record(s)")
        for (i in 0 until minOf(length(), limit)) appendLine(getJSONObject(i).toString(2))
    }

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).also {
        it.text = text
        it.textSize = size
        it.setPadding(0, 8, 0, 8)
        content.addView(it)
    }

    private fun button(text: String, parent: LinearLayout = content, action: () -> Unit) {
        val margin = (7 * resources.displayMetrics.density).toInt()
        val padding = (18 * resources.displayMetrics.density).toInt()
        val card = MaterialCardView(this).apply {
            radius = 18 * resources.displayMetrics.density
            cardElevation = 2 * resources.displayMetrics.density
            setCardBackgroundColor(ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_surface))
            strokeColor = ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_border)
            strokeWidth = (resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, margin, 0, margin) }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ClassMateAuthActivity, R.color.cm_text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(padding, padding, padding, padding)
        })
        parent.addView(card)
    }
}
