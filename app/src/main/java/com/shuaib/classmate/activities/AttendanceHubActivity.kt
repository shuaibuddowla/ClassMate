package com.shuaib.classmate.activities

import android.app.ProgressDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityAttendanceHubBinding
import com.shuaib.classmate.fragments.SessionsFragment
import com.shuaib.classmate.fragments.SummaryFragment
import com.shuaib.classmate.utils.AttendancePdfGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AttendanceHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttendanceHubBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_generate_monthly_pdf) {
                generateMonthlyPdf()
                true
            } else {
                false
            }
        }
        checkAdminAccess()
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: run {
            finish()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                if (role != "admin" && role != "superadmin") {
                    Toast.makeText(this, "Admin access required.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                setupTabs()
            }
            .addOnFailureListener { finish() }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) SessionsFragment() else SummaryFragment()
        }
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) "Sessions" else "Summary"
        }.attach()
    }

    private fun generateMonthlyPdf() {
        val dialog = ProgressDialog(this).apply {
            setMessage("Generating monthly report...")
            setCancelable(false)
            show()
        }

        AttendancePdfGenerator.generateMonthlyReport(this)
            .addOnSuccessListener { file ->
                val monthYear = SimpleDateFormat("MMMM_yyyy", Locale.getDefault()).format(Date())
                val storagePath = "pdfs/attendance/Attendance_Report_$monthYear.pdf"
                val ref = storage.reference.child(storagePath)
                ref.putFile(android.net.Uri.fromFile(file))
                    .continueWithTask { uploadTask ->
                        if (!uploadTask.isSuccessful) throw uploadTask.exception ?: RuntimeException("Upload failed")
                        ref.downloadUrl
                    }
                    .addOnSuccessListener { url ->
                        val title = "Attendance Report ${monthYear.replace("_", " ")}"
                        db.collection("pdfs").add(
                            mapOf(
                                "title" to title,
                                "subject" to "Attendance",
                                "description" to "Monthly attendance report",
                                "fileUrl" to url.toString(),
                                "url" to url.toString(),
                                "timestamp" to Timestamp.now(),
                                "uploadedBy" to (auth.currentUser?.uid ?: ""),
                                "type" to "attendance_report"
                            )
                        ).addOnSuccessListener {
                            dialog.dismiss()
                            Snackbar.make(binding.root, "Monthly report saved to Library", Snackbar.LENGTH_LONG).show()
                        }.addOnFailureListener {
                            dialog.dismiss()
                            Toast.makeText(this, "Metadata save failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener {
                        dialog.dismiss()
                        Toast.makeText(this, "Upload failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                dialog.dismiss()
                Toast.makeText(this, "Report failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }
}
