package com.shuaib.classmate.activities

import android.app.ProgressDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivitySessionDetailBinding
import com.shuaib.classmate.databinding.ItemSessionStudentBinding
import com.shuaib.classmate.models.AttendanceRecord
import com.shuaib.classmate.utils.AttendancePdfGenerator
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val adapter = StudentAdapter()
    private var sessionId = ""
    private var isAdmin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isBlank()) {
            finish()
            return
        }

        checkAdminAccess()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_export_pdf) {
                downloadSheet()
                true
            } else {
                false
            }
        }
        binding.rvStudents.adapter = adapter
        loadSession()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_session_detail, menu)
        menu?.findItem(R.id.action_export_pdf)?.isVisible = isAdmin
        return true
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                isAdmin = role == "admin" || role == "superadmin"
                invalidateOptionsMenu()
            }
    }

    private fun loadSession() {
        val sessionTask = db.collection("attendance_sessions").document(sessionId).get()
        val studentsTask = db.collection("users").whereEqualTo("role", "student").get()
        val recordsTask = db.collection("attendance_records").whereEqualTo("sessionId", sessionId).get()

        Tasks.whenAllSuccess<Any>(sessionTask, studentsTask, recordsTask)
            .addOnSuccessListener { results ->
                val session = results[0] as com.google.firebase.firestore.DocumentSnapshot
                val students = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents
                val records = (results[2] as com.google.firebase.firestore.QuerySnapshot).documents
                    .associateBy { it.getString("studentUid").orEmpty() }
                val start = session.getTimestamp("startTime")
                val end = session.getTimestamp("endTime")

                binding.tvSubject.text = session.getString("subject") ?: "Attendance"
                binding.tvDate.text = formatDate(start)
                binding.tvDuration.text = "Duration: ${durationText(start, end)}"
                binding.tvStartedBy.text = "Started by: ${session.getString("startedBy") ?: "-"}"

                val rows = students.map { student ->
                    val record = records[student.id]
                    StudentRow(
                        name = student.getString("name") ?: student.getString("email") ?: "Student",
                        status = record?.getString("status") ?: "absent",
                        time = record?.getTimestamp("detectedAt"),
                        source = record?.getString("source") ?: "-"
                    )
                }.sortedBy { it.name.lowercase(Locale.getDefault()) }
                adapter.submitList(rows)
            }
    }

    private fun downloadSheet() {
        val progress = ProgressDialog(this).apply {
            setMessage("Downloading sheet...")
            setCancelable(false)
            show()
        }

        db.collection("attendance_sheets").document(sessionId).get()
            .addOnSuccessListener { sheetDoc ->
                if (sheetDoc.exists()) {
                    val storageUrl = sheetDoc.getString("storageUrl")
                    if (storageUrl != null) {
                        downloadFromUrl(storageUrl, progress)
                    } else {
                        generateAndShare(progress)
                    }
                } else {
                    generateAndShare(progress)
                }
            }
            .addOnFailureListener {
                progress.dismiss()
                Toast.makeText(this, "Failed to check sheet: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun downloadFromUrl(url: String, progress: ProgressDialog) {
        // For simplicity, since it's Firebase Storage, but to download from URL
        // Use HttpURLConnection to download
        Thread {
            try {
                val connection = URL(url).openConnection() as java.net.HttpURLConnection
                connection.connect()
                val input = connection.inputStream
                val file = File(cacheDir, "attendance_sheet_$sessionId.pdf")
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
                runOnUiThread {
                    progress.dismiss()
                    shareFile(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun generateAndShare(progress: ProgressDialog) {
        // Fetch session and records
        val sessionTask = db.collection("attendance_sessions").document(sessionId).get()
        val recordsTask = db.collection("attendance_records").whereEqualTo("sessionId", sessionId).get()

        Tasks.whenAllSuccess<Any>(sessionTask, recordsTask)
            .addOnSuccessListener { results ->
                val session = results[0] as com.google.firebase.firestore.DocumentSnapshot
                val recordsDocs = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents

                val startTime = session.getTimestamp("startTime") ?: Timestamp.now()
                val sessionDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(startTime.toDate())
                val subject = session.getString("subject") ?: "Attendance"

                val records = recordsDocs.map { doc ->
                    AttendanceRecord(
                        studentName = doc.getString("studentName") ?: "Unknown",
                        status = doc.getString("status") ?: "absent",
                        detectedAt = doc.getTimestamp("detectedAt")
                    )
                }

                val file = AttendancePdfGenerator.generateFormalSheet(
                    sessionId = sessionId,
                    sessionDate = sessionDate,
                    records = records,
                    subject = subject
                )
                progress.dismiss()
                shareFile(file)
            }
            .addOnFailureListener {
                progress.dismiss()
                Toast.makeText(this, "Generation failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share attendance sheet"))
    }

    private fun formatDate(timestamp: Timestamp?): String =
        timestamp?.toDate()?.let { dateFormat.format(it) } ?: "-"

    private fun durationText(start: Timestamp?, end: Timestamp?): String {
        if (start == null || end == null) return "-"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(end.toDate().time - start.toDate().time).coerceAtLeast(0)
        return "$minutes min"
    }

    private data class StudentRow(
        val name: String,
        val status: String,
        val time: Timestamp?,
        val source: String
    )

    private class StudentAdapter : ListAdapter<StudentRow, StudentAdapter.ViewHolder>(Diff) {
        inner class ViewHolder(val binding: ItemSessionStudentBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemSessionStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            val present = row.status == "present"
            holder.binding.tvStudentName.text = row.name
            holder.binding.tvTime.text = row.time?.toDate()?.let { timeFormat.format(it) } ?: "-"
            holder.binding.tvSource.text = row.source
            holder.binding.tvStatusBadge.text = if (present) "Present" else "Absent"
            holder.binding.tvStatusBadge.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(if (present) "#34D399" else "#FF4D6D"))
        }
        companion object {
            private val Diff = object : DiffUtil.ItemCallback<StudentRow>() {
                override fun areItemsTheSame(oldItem: StudentRow, newItem: StudentRow) = oldItem.name == newItem.name
                override fun areContentsTheSame(oldItem: StudentRow, newItem: StudentRow) = oldItem == newItem
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        private val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    }
}
