package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityAdminPanelBinding
import com.shuaib.classmate.models.Batch
import com.shuaib.classmate.models.User
import com.shuaib.classmate.network.BackendApiClient
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.applyClickAnimation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var currentUser: User? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        updateManagedBatchSubtitle()
        binding.tvManagedBatchSubtitle.setOnClickListener {
            showBatchSwitcherDialog()
        }

        binding.btnTestTelegram.setOnClickListener {
            testTelegramConnection()
        }

        checkPermissionsAndSetupUI()
    }

    private fun updateManagedBatchSubtitle() {
        val managed = AppContextManager.getManagedBatchId()
        binding.tvManagedBatchSubtitle.text = "Managing: ${Batch.formatName(managed)} (Tap to switch)"
    }

    private fun showBatchSwitcherDialog() {
        val user = currentUser ?: return
        val availableBatches: List<String> = if (user.isGlobalSuperAdmin()) {
            Batch.PREDEFINED_BATCHES.map { it.id }
        } else {
            user.adminBatchIds.map { it.lowercase() }
        }

        if (availableBatches.size <= 1) return

        val batchNames = availableBatches.map { Batch.formatName(it) }.toTypedArray()
        val currentManaged = AppContextManager.getManagedBatchId()
        val currentIndex = availableBatches.indexOf(currentManaged).coerceAtLeast(0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Select Batch to Manage")
            .setSingleChoiceItems(batchNames, currentIndex) { dialog, which ->
                val selected = availableBatches[which]
                AppContextManager.setManagedBatchId(selected)
                updateManagedBatchSubtitle()
                dialog.dismiss()
                Toast.makeText(this, "Switched to managing ${Batch.formatName(selected)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndSetupUI() {
        val uid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                val user = document.toObject(User::class.java)
                user?.let {
                    currentUser = it
                    val isSuperAdmin = it.role == "superadmin"
                    binding.btnTestTelegram.visibility = if (isSuperAdmin) View.VISIBLE else View.GONE
                    setupClickListeners(it)
                    animateEntry()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupClickListeners(user: User) {
        // Post Notice
        if (user.canPostNotices()) {
            binding.cardPostNotice.visibility = View.VISIBLE
            binding.cardPostNotice.applyClickAnimation {
                startActivity(Intent(this, PostNoticeActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // Timetable
        if (user.canEditTimetable()) {
            binding.cardEditTimetable.visibility = View.VISIBLE
            binding.cardEditTimetable.applyClickAnimation {
                startActivity(Intent(this, TimetableManagementActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }

            binding.cardAcademicCalendar.visibility = View.VISIBLE
            binding.cardAcademicCalendar.applyClickAnimation {
                startActivity(Intent(this, AcademicCalendarActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }

            binding.cardManageBusSchedule.visibility = View.VISIBLE
            binding.cardManageBusSchedule.applyClickAnimation {
                startActivity(Intent(this, BusScheduleManagementActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // PDF
        if (user.canUploadPDF() || user.canUploadLibrary()) {
            binding.cardUploadPDF.visibility = View.VISIBLE
            binding.cardUploadPDF.applyClickAnimation {
                startActivity(Intent(this, PdfUploadActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // Seat Plan
        if (user.canUploadSeatPlan()) {
            binding.cardUploadSeatPlan.visibility = View.VISIBLE
            binding.cardUploadSeatPlan.applyClickAnimation {
                startActivity(Intent(this, SeatPlanUploadActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        // Result
        if (user.canUploadResult()) {
            binding.cardUploadResult.visibility = View.VISIBLE
            binding.cardUploadResult.applyClickAnimation {
                startActivity(Intent(this, ResultUploadActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }

        if (user.canManageUsers()) {
            binding.cardManageUsers.visibility = View.VISIBLE
            binding.cardManageUsers.applyClickAnimation {
                startActivity(Intent(this, UserManagementActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }

    private fun testTelegramConnection() {
        Thread {
            try {
                val request = BackendApiClient.authenticated(
                    Request.Builder()
                        .url(BackendApiClient.url("/v1/telegram/test"))
                        .post("{}".toRequestBody("application/json".toMediaType()))
                )
                val (responseCode, response) = OkHttpClient().newCall(request).execute().use {
                    it.code to it.body?.string().orEmpty()
                }

                android.util.Log.d("TELEGRAM_TEST", "Code: $responseCode")
                android.util.Log.d("TELEGRAM_TEST", "Response: $response")

                Handler(Looper.getMainLooper()).post {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Telegram connected!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ Telegram failed (HTTP $responseCode)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "❌ Telegram error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun animateEntry() {
        val views = listOf(
            binding.cardPostNotice,
            binding.cardEditTimetable,
            binding.cardAcademicCalendar,
            binding.cardUploadPDF,
            binding.cardUploadSeatPlan,
            binding.cardUploadResult,
            binding.cardManageUsers,
            binding.cardManageBusSchedule
        ).filter { it.visibility == View.VISIBLE }

        views.forEachIndexed { index, view ->
            view.translationY = 80f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setStartDelay((index * 60).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
