package com.shuaib.classmate.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.attendance.AttendanceSessionManager
import com.shuaib.classmate.attendance.BleAdvertiser
import com.shuaib.classmate.databinding.ActivityStartAttendanceBinding
import com.shuaib.classmate.services.BleAdvertiserService
import com.shuaib.classmate.utils.SubjectList

class StartAttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStartAttendanceBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var sessionManager: AttendanceSessionManager

    private var currentSession: AttendanceSessionManager.AttendanceSession? = null
    private var presentListener: ListenerRegistration? = null
    private var totalStudents = 0
    private var pendingStartAfterPermission = false

    private val advertisePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            startSession()
        } else if (!granted) {
            pendingStartAfterPermission = false
            Toast.makeText(
                this,
                "Bluetooth advertise permission is required.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        sessionManager = AttendanceSessionManager(db)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvClassId.text = "Class: ${BleAdvertiser.CLASS_ID}"

        setupSubjectDropdown()
        checkAdminAccess()
        loadStudentCount()

        binding.btnStartSession.setOnClickListener {
            ensureAdvertisePermissionThenStart()
        }

        binding.btnEndSession.setOnClickListener {
            endSession()
        }
    }

    private fun setupSubjectDropdown() {
        val subjects = SubjectList.subjects.map { it.name }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            subjects
        )
        binding.dropdownSubject.setAdapter(adapter)
        subjects.firstOrNull()?.let { binding.dropdownSubject.setText(it, false) }
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            finish()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                val allowed = role == "admin" || role == "superadmin"
                if (!allowed) {
                    Toast.makeText(this, "Admin access required.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not verify access.", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun loadStudentCount() {
        sessionManager.getStudentCount(
            onSuccess = {
                totalStudents = it
                updateCounter(0)
            },
            onFailure = {
                Toast.makeText(this, "Could not load student count.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun ensureAdvertisePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterPermission = true
            advertisePermissionLauncher.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
            return
        }

        startSession()
    }

    private fun startSession() {
        val subject = binding.dropdownSubject.text.toString().trim()
        if (subject.isBlank()) {
            binding.layoutSubject.error = "Select a subject"
            return
        }
        binding.layoutSubject.error = null

        val adminUid = auth.currentUser?.uid ?: return
        setBusy(true)

        sessionManager.createSession(
            subject = subject,
            adminUid = adminUid,
            onSuccess = { session ->
                currentSession = session
                startBleService(session)
                listenForPresentCount(session.sessionId)
                binding.tvSessionState.text = "Active session: ${session.sessionId}"
                binding.btnStartSession.isEnabled = false
                binding.btnEndSession.isEnabled = true
                setBusy(false)
                Toast.makeText(this, "Attendance session started.", Toast.LENGTH_SHORT).show()
                openDashboard(session.sessionId)
            },
            onFailure = {
                setBusy(false)
                Toast.makeText(this, "Start failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun startBleService(session: AttendanceSessionManager.AttendanceSession) {
        val intent = BleAdvertiserService.startIntent(
            context = this,
            sessionId = session.sessionId,
            classId = session.classId,
            timestamp = session.startMillis
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun openDashboard(sessionId: String) {
        val intent = Intent(this, AttendanceDashboardActivity::class.java).apply {
            putExtra(AttendanceDashboardActivity.EXTRA_SESSION_ID, sessionId)
        }
        startActivity(intent)
    }

    private fun listenForPresentCount(sessionId: String) {
        presentListener?.remove()
        presentListener = sessionManager.getPresentCountQuery(sessionId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                updateCounter(snapshot.size())
            }
    }

    private fun updateCounter(presentCount: Int) {
        binding.tvPresentCounter.text = "$presentCount / $totalStudents students marked present"
    }

    private fun endSession() {
        val sessionId = currentSession?.sessionId ?: return
        setBusy(true)

        sessionManager.closeSession(
            sessionId = sessionId,
            onSuccess = {
                stopService(BleAdvertiserService.stopIntent(this))
                presentListener?.remove()
                presentListener = null
                currentSession = null
                binding.tvSessionState.text = "Session closed"
                binding.btnStartSession.isEnabled = true
                binding.btnEndSession.isEnabled = false
                setBusy(false)
                Toast.makeText(this, "Attendance session closed.", Toast.LENGTH_SHORT).show()
            },
            onFailure = {
                setBusy(false)
                Toast.makeText(this, "Close failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setBusy(isBusy: Boolean) {
        binding.btnStartSession.isEnabled = !isBusy && currentSession == null
        binding.btnEndSession.isEnabled = !isBusy && currentSession != null
        binding.dropdownSubject.isEnabled = !isBusy && currentSession == null
    }

    override fun onDestroy() {
        presentListener?.remove()
        super.onDestroy()
    }
}
