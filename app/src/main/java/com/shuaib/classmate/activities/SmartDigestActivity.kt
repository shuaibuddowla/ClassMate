package com.shuaib.classmate.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.DigestDeadlineAdapter
import com.shuaib.classmate.adapters.DigestStudySuggestionAdapter
import com.shuaib.classmate.adapters.DigestTimelineAdapter
import com.shuaib.classmate.databinding.ActivitySmartDigestBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.services.AIService
import com.shuaib.classmate.storage.LibraryUrlOpener
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

class SmartDigestActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmartDigestBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var timetableRepo: TimetableRepository
    
    private var timelineAdapter: DigestTimelineAdapter? = null
    private var deadlineAdapter: DigestDeadlineAdapter? = null
    private var studySuggestionAdapter: DigestStudySuggestionAdapter? = null
    
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var todayPeriodsList: List<Period> = emptyList()
    private var deadlineCount = 0
    private var noticeCount = 0
    
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdownState()
            countdownHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySmartDigestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        firestore = FirebaseFirestore.getInstance()
        timetableRepo = TimetableRepository.getInstance(this)
        
        setupSystemBars()
        setupHeader()
        setupRecyclerViews()
        
        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(com.shuaib.classmate.R.anim.slide_in_left, com.shuaib.classmate.R.anim.slide_out_right)
        }
        
        loadData()
        
        // Staggered entrance animation for cards
        animateEntrance()
    }

    private fun setupSystemBars() {
        try {
            val isDark = ThemeColors.isDark(this)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        } catch (e: Exception) {
            Log.e("SmartDigest", "System bars setup failed: ${e.message}")
        }
    }

    private fun setupHeader() {
        binding.tvDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))
    }

    private fun setupRecyclerViews() {
        // Today's timeline
        timelineAdapter = DigestTimelineAdapter()
        binding.rvTimeline.apply {
            layoutManager = LinearLayoutManager(this@SmartDigestActivity)
            adapter = timelineAdapter
        }

        // Deadlines
        deadlineAdapter = DigestDeadlineAdapter { notice ->
            // Click to open details - standard toast/navigation or show dialog
            // We'll show a simple dialog with details for now
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_ClassMate_Dialog)
                .setTitle(notice.title)
                .setMessage(notice.body)
                .setPositiveButton("OK", null)
                .show()
        }
        binding.rvDeadlines.apply {
            layoutManager = LinearLayoutManager(this@SmartDigestActivity)
            adapter = deadlineAdapter
        }

        // Suggestions
        studySuggestionAdapter = DigestStudySuggestionAdapter { pdf ->
            LibraryUrlOpener.open(this, pdf)
        }
        binding.rvStudySuggestions.apply {
            layoutManager = LinearLayoutManager(this@SmartDigestActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = studySuggestionAdapter
        }
    }

    private fun loadData() {
        val todayName = LocalDate.now().dayOfWeek.name.lowercase()
        
        // 1. Observe periods from Repository reactively
        lifecycleScope.launch {
            timetableRepo.observePeriods(todayName).collect { periods ->
                todayPeriodsList = periods.sortedBy { parseTime(it.startTime) }
                
                withContext(Dispatchers.Main) {
                    if (todayPeriodsList.isEmpty()) {
                        binding.tvNoClasses.isVisible = true
                        binding.rvTimeline.isVisible = false
                    } else {
                        binding.tvNoClasses.isVisible = false
                        binding.rvTimeline.isVisible = true
                        timelineAdapter?.updateList(todayPeriodsList)
                    }
                    
                    binding.tvStatClasses.text = "${todayPeriodsList.filter { !it.isCancelled }.size} classes"
                    
                    // Start countdown
                    countdownHandler.removeCallbacks(countdownRunnable)
                    countdownHandler.post(countdownRunnable)
                }
            }
        }
        
        // 2. Trigger sync of today's schedule from firestore in background
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { timetableRepo.syncDayFromFirestore(todayName, com.google.firebase.firestore.Source.CACHE) }
            runCatching { timetableRepo.syncDayFromFirestore(todayName, com.google.firebase.firestore.Source.SERVER) }
        }

        // 3. Fetch Deadlines
        fetchDeadlines()

        // 4. Fetch Study Suggestions
        fetchStudySuggestions()

        // 5. Generate AI Morning Briefing
        generateAiBrief()
    }

    private fun fetchDeadlines() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nowTimestamp = Timestamp.now()
                // Fetch notices and filter in memory to avoid Firestore index requirements
                val snapshot = firestore.collection("notices")
                    .whereEqualTo("isDeleted", false)
                    .get()
                    .await()
                
                val notices = snapshot.documents.mapNotNull { doc ->
                    val notice = doc.toObject(Notice::class.java)?.copy(id = doc.id)
                    // Only keep future deadlines
                    if (notice != null && (notice.isAssignment || notice.isClassTest) && notice.deadlineAt != null && notice.deadlineAt.toDate().time > nowTimestamp.toDate().time) {
                        notice
                    } else {
                        null
                    }
                }.sortedBy { it.deadlineAt?.toDate()?.time ?: Long.MAX_VALUE }
                
                noticeCount = snapshot.size()
                deadlineCount = notices.size

                withContext(Dispatchers.Main) {
                    binding.tvStatDeadlines.text = "$deadlineCount deadlines"
                    binding.tvStatNotices.text = "$noticeCount notices"
                    
                    if (notices.isEmpty()) {
                        binding.tvNoDeadlines.isVisible = true
                        binding.rvDeadlines.isVisible = false
                    } else {
                        binding.tvNoDeadlines.isVisible = false
                        binding.rvDeadlines.isVisible = true
                        deadlineAdapter?.updateList(notices)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmartDigest", "Failed to fetch deadlines: ${e.message}")
            }
        }
    }

    private fun fetchStudySuggestions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Calculate tomorrow's subjects
                val tomorrowDayName = LocalDate.now().plusDays(1).dayOfWeek.name.lowercase()
                val tomorrowPeriodsSnapshot = firestore.collection("timetable")
                    .document(tomorrowDayName)
                    .collection("periods")
                    .get()
                    .await()
                
                val subjects = tomorrowPeriodsSnapshot.documents.mapNotNull { doc ->
                    doc.getString("subject")
                }.distinct()

                if (subjects.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.tvNoSuggestions.isVisible = true
                        binding.rvStudySuggestions.isVisible = false
                    }
                    return@launch
                }

                // Query library files matching these subjects
                val librarySnapshot = firestore.collection("library_files")
                    .whereEqualTo("isDeleted", false)
                    .get()
                    .await()

                val suggestions = librarySnapshot.documents.mapNotNull { doc ->
                    val file = doc.toPdfFile()
                    if (subjects.any { it.equals(file.subject, ignoreCase = true) }) {
                        file
                    } else {
                        null
                    }
                }.take(6) // Limit to top 6 suggestions

                withContext(Dispatchers.Main) {
                    if (suggestions.isEmpty()) {
                        binding.tvNoSuggestions.isVisible = true
                        binding.rvStudySuggestions.isVisible = false
                    } else {
                        binding.tvNoSuggestions.isVisible = false
                        binding.rvStudySuggestions.isVisible = true
                        studySuggestionAdapter?.updateList(suggestions)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmartDigest", "Suggestions fetch failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.tvNoSuggestions.isVisible = true
                    binding.rvStudySuggestions.isVisible = false
                }
            }
        }
    }

    private fun generateAiBrief() {
        lifecycleScope.launch {
            try {
                val nonCancelledPeriods = todayPeriodsList.filter { !it.isCancelled }
                // Re-use Morning Brief generator
                val brief = AIService.generateMorningBrief(nonCancelledPeriods, emptyList())
                withContext(Dispatchers.Main) {
                    binding.shimmerAiBriefing.isVisible = false
                    if (brief.isNullOrBlank()) {
                        binding.tvAiBriefingError.isVisible = true
                        binding.tvAiBriefingError.text = "AI briefing could not be generated."
                    } else {
                        binding.tvAiBriefingContent.isVisible = true
                        binding.tvAiBriefingContent.text = brief
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.shimmerAiBriefing.isVisible = false
                    binding.tvAiBriefingError.isVisible = true
                    binding.tvAiBriefingError.text = "AI briefing unavailable. Check connection."
                }
            }
        }
    }

    private fun updateCountdownState() {
        val now = LocalTime.now()
        
        // Find if there is an ongoing class
        var ongoingPeriod: Period? = null
        for (period in todayPeriodsList) {
            if (period.isCancelled) continue
            val start = parseTime(period.startTime) ?: continue
            val end = parseTime(period.endTime) ?: continue
            if (now.isAfter(start) && now.isBefore(end)) {
                ongoingPeriod = period
                break
            }
        }

        if (ongoingPeriod != null) {
            val end = parseTime(ongoingPeriod.endTime) ?: return
            val diffSecs = ChronoUnit.SECONDS.between(now, end)
            
            binding.tvNoMoreClasses.isVisible = false
            binding.tvCountdownLabel.text = "ONGOING CLASS"
            binding.tvCountdownSubject.text = ongoingPeriod.subject
            binding.tvCountdownTeacher.text = ongoingPeriod.teacher
            
            binding.tvCountdownTime.text = formatSecs(diffSecs)
            binding.tvCountdownTimeLabel.text = "remaining"
            
            // Pulse color if ending soon
            if (diffSecs < 600) {
                binding.tvCountdownTime.setTextColor(ThemeColors.error(this))
            } else {
                binding.tvCountdownTime.setTextColor(ThemeColors.primary(this))
            }
            return
        }

        // Find next class
        var nextPeriod: Period? = null
        for (period in todayPeriodsList) {
            if (period.isCancelled) continue
            val start = parseTime(period.startTime) ?: continue
            if (start.isAfter(now)) {
                nextPeriod = period
                break
            }
        }

        if (nextPeriod != null) {
            val start = parseTime(nextPeriod.startTime) ?: return
            val diffSecs = ChronoUnit.SECONDS.between(now, start)
            
            binding.tvNoMoreClasses.isVisible = false
            binding.tvCountdownLabel.text = "NEXT CLASS"
            binding.tvCountdownSubject.text = nextPeriod.subject
            binding.tvCountdownTeacher.text = nextPeriod.teacher
            
            binding.tvCountdownTime.text = formatSecs(diffSecs)
            binding.tvCountdownTimeLabel.text = "starts in"
            
            if (diffSecs < 600) {
                binding.tvCountdownTime.setTextColor(ThemeColors.error(this))
                // Subtle pulse animation
                binding.tvCountdownTime.animate().scaleX(1.1f).scaleY(1.1f).setDuration(500).withEndAction {
                    binding.tvCountdownTime.animate().scaleX(1f).scaleY(1f).setDuration(500).start()
                }.start()
            } else {
                binding.tvCountdownTime.setTextColor(ThemeColors.primary(this))
            }
        } else {
            // No more classes today
            binding.tvCountdownLabel.text = ""
            binding.tvCountdownSubject.text = ""
            binding.tvCountdownTeacher.text = ""
            binding.tvCountdownTime.text = ""
            binding.tvCountdownTimeLabel.text = ""
            binding.tvNoMoreClasses.isVisible = true
        }
    }

    private fun formatSecs(seconds: Long): String {
        if (seconds >= 3600) {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            return "${hours}h ${mins}m"
        }
        val mins = seconds / 60
        val secs = seconds % 60
        return "${mins}m ${secs}s"
    }

    private fun parseTime(timeStr: String): LocalTime? {
        val clean = timeStr.trim().uppercase()
        val formatters = listOf(
            DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.US),
            DateTimeFormatter.ofPattern("H:mm", Locale.US),
            DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        )
        for (formatter in formatters) {
            try {
                return LocalTime.parse(clean, formatter)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toPdfFile(): PdfFile {
        return PdfFile(
            id = id,
            title = getString("title") ?: "No Title",
            subject = getString("subject") ?: "",
            description = getString("description") ?: "",
            uploadedBy = getString("uploadedByName") ?: getString("uploadedBy") ?: "",
            telegramUrl = getString("telegramUrl") ?: "",
            driveUrl = getString("driveUrl") ?: "",
            fileId = getString("fileId") ?: "",
            timestamp = getTimestamp("timestamp") ?: getTimestamp("createdAt"),
            courseCode = getString("courseCode") ?: "",
            courseType = getString("courseType") ?: "",
            fileType = getString("fileType") ?: "other",
            mimeType = getString("mimeType") ?: "application/octet-stream",
            sizeBytes = getLong("sizeBytes") ?: 0L,
            provider = getString("provider") ?: "",
            downloadUrl = getString("downloadUrl") ?: "",
            githubAssetId = getLong("githubAssetId") ?: 0L,
            githubAssetName = getString("githubAssetName") ?: getString("title") ?: "",
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt"),
            downloadCount = getLong("downloadCount") ?: 0L,
            isDeleted = getBoolean("isDeleted") ?: false
        )
    }

    private fun animateEntrance() {
        val views = listOf(
            binding.cardCountdown,
            binding.cardAiBriefing,
            binding.rvTimeline,
            binding.rvDeadlines,
            binding.layoutStudySuggestions
        )
        
        views.forEachIndexed { index, view ->
            view.translationY = 100f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(100L + index * 50L)
                .setDuration(500)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownHandler.removeCallbacks(countdownRunnable)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(com.shuaib.classmate.R.anim.slide_in_left, com.shuaib.classmate.R.anim.slide_out_right)
    }
}
