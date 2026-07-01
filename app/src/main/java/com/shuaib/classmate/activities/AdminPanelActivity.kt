// com/shuaib/classmate/activities/AdminPanelActivity.kt
package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityAdminPanelBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.AppConstants
import com.shuaib.classmate.utils.applyClickAnimation

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.btnTestTelegram.setOnClickListener {
            testTelegramConnection()
        }

        checkPermissionsAndSetupUI()
    }

    private fun checkPermissionsAndSetupUI() {
        val uid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                val user = document.toObject(User::class.java)
                user?.let {
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
                val testUrl = "https://api.telegram.org/bot" +
                        AppConstants.TELEGRAM_BOT_TOKEN +
                        "/sendMessage"

                val connection = java.net.URL(testUrl)
                    .openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )
                connection.doOutput = true
                connection.connectTimeout = 10000

                val body = """
            {
                "chat_id": "${AppConstants.TELEGRAM_CHANNEL_ID}",
                "text": "✅ ClassMate bot is connected and working!"
            }
            """.trimIndent()

                connection.outputStream.write(
                    body.toByteArray(Charsets.UTF_8)
                )
                connection.outputStream.flush()

                val responseCode = connection.responseCode
                val response = if (responseCode == 200) {
                    connection.inputStream
                        .bufferedReader().readText()
                } else {
                    connection.errorStream
                        ?.bufferedReader()?.readText()
                        ?: "Unknown error"
                }

                android.util.Log.d("TELEGRAM_TEST",
                    "Code: $responseCode")
                android.util.Log.d("TELEGRAM_TEST",
                    "Response: $response")

                Handler(Looper.getMainLooper()).post {
                    if (responseCode == 200) {
                        Toast.makeText(this,
                            "✅ Telegram connected!",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this,
                            "❌ Failed: $response",
                            Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("TELEGRAM_TEST",
                    "Error: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun animateEntry() {
        val viewsToAnimate = mutableListOf<View>()
        viewsToAnimate.add(binding.tvTitle)
        
        if (binding.cardPostNotice.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardPostNotice)
        if (binding.cardEditTimetable.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardEditTimetable)
        if (binding.cardAcademicCalendar.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardAcademicCalendar)
        if (binding.cardManageBusSchedule.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardManageBusSchedule)
        if (binding.cardUploadPDF.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardUploadPDF)
        if (binding.cardUploadSeatPlan.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardUploadSeatPlan)
        if (binding.cardUploadResult.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardUploadResult)
        if (binding.cardManageUsers.visibility == View.VISIBLE) viewsToAnimate.add(binding.cardManageUsers)
        if (binding.btnTestTelegram.visibility == View.VISIBLE) viewsToAnimate.add(binding.btnTestTelegram)

        viewsToAnimate.forEachIndexed { index, view ->
            view.translationY = 100f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(index * 100L)
                .setDuration(600)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
