package com.shuaib.classmate.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject

class ClassMateProfileActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var currentProfile: JSONObject = JSONObject()
    private var currentBatchId: String = ""
    private var currentBatchLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classmate_profile)

        window.statusBarColor = getColor(R.color.cm_background)
        window.navigationBarColor = getColor(R.color.cm_background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val lightTheme = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }

        findViewById<View>(R.id.btnProfileBack).setOnClickListener { finish() }

        swipeRefresh = findViewById(R.id.swipeProfilePage)
        swipeRefresh.setColorSchemeResources(R.color.cm_primary)
        swipeRefresh.setOnRefreshListener { loadProfileData() }

        // Load cached identity first
        val cachedHome = ClassMateAcademicCache.home(this)
        if (cachedHome != null) {
            currentProfile = cachedHome.optJSONObject("profile") ?: JSONObject()
            currentBatchId = cachedHome.optString("batch")
            currentBatchLabel = cachedHome.optString("label")
            bindUi()
        }

        // Fetch fresh profile from network
        loadProfileData()
    }

    private fun loadProfileData() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val freshProfile = ClassMateAuthApi.initializeProfile()
                currentProfile = freshProfile
                val assignedBatch = freshProfile.optString("batch_id")
                if (currentBatchId.isBlank()) currentBatchId = assignedBatch
                if (currentBatchId.isNotBlank() && currentBatchLabel.isBlank()) {
                    val batch = ClassMateAuthApi.rows("batches", "select=batch_number,academic_session,departments(code)&id=eq.$currentBatchId&limit=1").optJSONObject(0)
                    if (batch != null) {
                        val deptCode = batch.optJSONObject("departments")?.optString("code")?.uppercase() ?: "MBSTU"
                        val bNum = batch.optInt("batch_number")
                        val sess = ClassMateAcademicSession.format(batch.optInt("academic_session"))
                        currentBatchLabel = "$deptCode Batch $bNum · Session $sess"
                    }
                }
                ClassMateAcademicCache.saveHome(this@ClassMateProfileActivity, freshProfile, currentBatchId, currentBatchLabel)
                bindUi()
            } catch (_: Exception) {
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun bindUi() {
        val account = currentProfile
        val role = account.optString("role")
        val isCr = account.optBoolean("is_cr")
        val isAdmin = role == "admin"
        val isTeacher = role == "teacher"

        findViewById<TextView>(R.id.tvProfileName).text = account.optString("full_name").ifBlank { "ClassMate user" }

        val roleBadge = findViewById<TextView>(R.id.tvRoleBadge)
        when {
            isCr -> {
                roleBadge.text = "CLASS REPRESENTATIVE"
                roleBadge.setBackgroundResource(R.drawable.bg_role_badge_admin)
            }
            isAdmin -> {
                roleBadge.text = "OWNER"
                roleBadge.setBackgroundResource(R.drawable.bg_role_badge_owner)
            }
            isTeacher -> {
                roleBadge.text = "TEACHER"
                roleBadge.setBackgroundResource(R.drawable.bg_role_badge_admin)
            }
            else -> {
                roleBadge.text = "STUDENT"
                roleBadge.setBackgroundResource(R.drawable.bg_role_badge_admin)
            }
        }

        val studentId = account.optString("student_id").takeUnless { it.isBlank() || it == "null" }
        val email = account.optString("email")
        val subInfoText = listOfNotNull(studentId, email).joinToString(" · ")
        findViewById<TextView>(R.id.tvUserSubInfo).text = subInfoText

        findViewById<View>(R.id.layoutSubInfo).setOnClickListener {
            val textToCopy = studentId ?: email
            if (!textToCopy.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Student ID", textToCopy))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        val photo = account.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }
            ?: GoogleSignIn.getLastSignedInAccount(this)?.photoUrl?.toString()
        if (!photo.isNullOrBlank()) {
            Glide.with(this)
                .load(photo)
                .placeholder(R.drawable.ic_default_avatar)
                .into(findViewById(R.id.ivProfile))
        }

        findViewById<View>(R.id.btnHeroEdit).setOnClickListener { openEditProfile() }
        findViewById<View>(R.id.cardPersonalInfo).setOnClickListener { openEditProfile() }

        // Blood Group
        val bloodGroup = account.optString("blood_group").takeUnless { it.isBlank() || it == "null" || it == "Unknown" }
        findViewById<TextView>(R.id.tvMetricBlood).text = bloodGroup ?: "Not set"
        findViewById<TextView>(R.id.tvMetricBloodSub).text = if (bloodGroup != null) "Tap for Blood Network" else "Add to help batchmates"
        findViewById<View>(R.id.tileBloodGroup).setOnClickListener {
            if (bloodGroup == null) {
                openEditProfile()
            } else {
                val intent = Intent(this, ClassMateBloodActivity::class.java).apply {
                    if (currentBatchId.isNotBlank()) putExtra("batch_id", currentBatchId)
                }
                startActivity(intent)
            }
        }

        // Academic Batch
        val rawBatch = currentBatchLabel.trim().ifBlank {
            ClassMateAcademicCache.home(this)?.optString("label")?.trim().orEmpty()
        }
        val (batchVal, batchSub) = if (rawBatch.contains(" · Session ")) {
            val parts = rawBatch.split(" · Session ", limit = 2)
            val deptBatch = parts[0].trim().replace(" Batch ", " · Batch ")
            val session = "Session ${parts.getOrElse(1) { "" }.trim()}"
            deptBatch to session
        } else if (rawBatch.contains(" · ")) {
            val parts = rawBatch.split(" · ", limit = 2)
            val deptBatch = parts[0].trim().replace(" Batch ", " · Batch ")
            val session = parts.getOrElse(1) { "" }.trim()
            deptBatch to session
        } else if (rawBatch.isNotBlank()) {
            rawBatch.replace(" Batch ", " · Batch ") to ""
        } else {
            "MBSTU" to ""
        }
        val assignedBatch = account.optString("batch_id")
        val isDifferentBatch = assignedBatch.isNotBlank() && currentBatchId.isNotBlank() && assignedBatch != currentBatchId
        findViewById<TextView>(R.id.tvMetricBatchTitle)?.text = if (isDifferentBatch) "ACTIVE BATCH" else "DEPARTMENT"
        findViewById<TextView>(R.id.tvMetricBatch).text = batchVal
        findViewById<TextView>(R.id.tvMetricBatchSub).text = batchSub.ifBlank { "Academic Batch" }
        findViewById<View>(R.id.tileBatchSession).setOnClickListener { openEditProfile() }

        // Home Town
        val town = account.optString("home_town").takeUnless { it.isBlank() || it == "null" }
        val residence = account.optString("current_residence").takeUnless { it.isBlank() || it == "null" }
        findViewById<TextView>(R.id.tvMetricDistrict).text = town ?: "Not set"
        findViewById<TextView>(R.id.tvMetricDistrictSub).text = residence ?: if (town != null) "District origin" else "Tap to add hometown"
        findViewById<View>(R.id.tileHomeDistrict).setOnClickListener { openEditProfile() }

        // Mobile Number
        val mobile = account.optString("mobile_number").takeUnless { it.isBlank() || it == "null" }
        findViewById<TextView>(R.id.tvMetricPhone).text = mobile ?: "Not set"
        findViewById<TextView>(R.id.tvMetricPhoneSub).text = if (mobile != null) "Shared with batch directory" else "Tap to add contact"
        findViewById<View>(R.id.tileContactPhone).setOnClickListener { openEditProfile() }

        // Blood Emergency Requests Card
        findViewById<View>(R.id.cardBloodEmergency).apply {
            visibility = if (isTeacher) View.GONE else View.VISIBLE
            setOnClickListener {
                val intent = Intent(this@ClassMateProfileActivity, ClassMateBloodActivity::class.java).apply {
                    if (currentBatchId.isNotBlank()) putExtra("batch_id", currentBatchId)
                }
                startActivity(intent)
            }
        }

        // Incomplete Profile Nudge
        val isComplete = mobile != null && town != null && bloodGroup != null
        findViewById<View>(R.id.cardProfileNudge).apply {
            visibility = if (!isComplete && !isTeacher) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.btnCompleteProfileNudge).setOnClickListener { openEditProfile() }
    }

    private fun openEditProfile() {
        if (currentProfile.length() == 0) return
        ClassMateProfileDetailsDialog.show(this, currentProfile, false) { updated ->
            currentProfile = updated
            ClassMateAcademicCache.saveHome(this, updated, currentBatchId, currentBatchLabel)
            bindUi()
        }
    }
}
