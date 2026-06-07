package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.adapters.AttendanceDashboardAdapter
import com.shuaib.classmate.adapters.AttendanceDashboardAdapter.AttendanceStudentRow
import com.shuaib.classmate.attendance.AttendanceSessionManager
import com.shuaib.classmate.attendance.BleAdvertiser
import com.shuaib.classmate.databinding.ActivityAttendanceDashboardBinding
import com.shuaib.classmate.services.BleAdvertiserService
import java.util.Locale

class AttendanceDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttendanceDashboardBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var sessionManager: AttendanceSessionManager
    private lateinit var adapter: AttendanceDashboardAdapter

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt = 0L
    private var sessionId = ""
    private var subject = "Attendance"
    private var isClosing = false
    private var students = emptyList<StudentInfo>()
    private var recordsByStudentUid = emptyMap<String, AttendanceRecordInfo>()
    private var recordsListener: ListenerRegistration? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = ((System.currentTimeMillis() - timerStartedAt) / 1000).coerceAtLeast(0)
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            binding.tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        sessionManager = AttendanceSessionManager(db)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()

        if (sessionId.isBlank()) {
            Toast.makeText(this, "Missing attendance session.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupRecyclerView()
        setupClicks()
        checkAdminAccessAndLoad()
        startTimer()
    }

    private fun setupRecyclerView() {
        adapter = AttendanceDashboardAdapter { row ->
            showManualOverrideDialog(row)
        }
        binding.rvStudents.layoutManager = LinearLayoutManager(this)
        binding.rvStudents.adapter = adapter
    }

    private fun setupClicks() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnEndSession.setOnClickListener { showEndSessionConfirmation() }
    }

    private fun checkAdminAccessAndLoad() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
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

                loadSession()
                loadStudents()
                listenToRecords()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not verify admin access.", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun loadSession() {
        db.collection("attendance_sessions").document(sessionId).get()
            .addOnSuccessListener { doc ->
                subject = doc.getString("subject") ?: "Attendance"
                binding.tvSubject.text = subject
            }
            .addOnFailureListener {
                binding.tvSubject.text = subject
            }
    }

    private fun loadStudents() {
        db.collection("users")
            .whereEqualTo("role", "student")
            .get()
            .addOnSuccessListener { snapshot ->
                students = snapshot.documents
                    .filter { doc ->
                        val classId = doc.getString("classId")
                        classId.isNullOrBlank() || classId == BleAdvertiser.CLASS_ID
                    }
                    .map { doc ->
                        StudentInfo(
                            uid = doc.id,
                            name = doc.getString("name") ?: doc.getString("email") ?: "Student"
                        )
                    }
                    .sortedBy { it.name.lowercase(Locale.getDefault()) }

                renderRows()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not load students.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun listenToRecords() {
        recordsListener?.remove()
        recordsListener = db.collection("attendance_records")
            .whereEqualTo("sessionId", sessionId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                recordsByStudentUid = snapshot.documents.mapNotNull { doc ->
                    val studentUid = doc.getString("studentUid") ?: return@mapNotNull null
                    studentUid to AttendanceRecordInfo(
                        status = doc.getString("status") ?: AttendanceDashboardAdapter.STATUS_WAITING,
                        detectedAt = doc.getTimestamp("detectedAt")
                    )
                }.toMap()

                renderRows()
            }
    }

    private fun renderRows() {
        val rows = students.map { student ->
            val record = recordsByStudentUid[student.uid]
            AttendanceStudentRow(
                uid = student.uid,
                name = student.name,
                status = record?.status ?: AttendanceDashboardAdapter.STATUS_WAITING,
                detectedAt = record?.detectedAt
            )
        }

        adapter.submitList(rows)
        val presentCount = rows.count { it.status == AttendanceDashboardAdapter.STATUS_PRESENT }
        binding.tvPresentCount.text = "$presentCount / ${rows.size} Present"
    }

    private fun showManualOverrideDialog(row: AttendanceStudentRow) {
        val newStatus = if (row.status == AttendanceDashboardAdapter.STATUS_PRESENT) {
            AttendanceDashboardAdapter.STATUS_ABSENT
        } else {
            AttendanceDashboardAdapter.STATUS_PRESENT
        }
        val label = if (newStatus == AttendanceDashboardAdapter.STATUS_PRESENT) "present" else "absent"

        AlertDialog.Builder(this)
            .setTitle("Manual override")
            .setMessage("Mark ${row.name} as $label?")
            .setPositiveButton("Mark ${label.replaceFirstChar { it.uppercase() }}") { _, _ ->
                writeManualOverride(row, newStatus)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeManualOverride(row: AttendanceStudentRow, status: String) {
        val recordRef = db.collection("attendance_records").document("${sessionId}__${row.uid}")
        val data = hashMapOf<String, Any?>(
            "sessionId" to sessionId,
            "studentUid" to row.uid,
            "studentName" to row.name,
            "status" to status,
            "detectedAt" to if (status == AttendanceDashboardAdapter.STATUS_PRESENT) Timestamp.now() else null,
            "source" to "manual"
        )

        recordRef.set(data)
            .addOnFailureListener {
                Toast.makeText(this, "Override failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showEndSessionConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("End attendance session?")
            .setMessage("This will close the session and mark all waiting students absent. This cannot be undone.")
            .setPositiveButton("End Session") { _, _ -> closeSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun closeSession() {
        if (isClosing) return
        isClosing = true
        binding.btnEndSession.isEnabled = false

        sessionManager.closeSession(
            sessionId = sessionId,
            onSuccess = {
                stopService(BleAdvertiserService.stopIntent(this))
                Toast.makeText(this, "Attendance session closed.", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, AdminPanelActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            },
            onFailure = {
                isClosing = false
                binding.btnEndSession.isEnabled = true
                Toast.makeText(this, "Close failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun startTimer() {
        timerStartedAt = System.currentTimeMillis()
        timerHandler.post(timerRunnable)
    }

    override fun onDestroy() {
        recordsListener?.remove()
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }

    private data class StudentInfo(
        val uid: String,
        val name: String
    )

    private data class AttendanceRecordInfo(
        val status: String,
        val detectedAt: Timestamp?
    )

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
