package com.shuaib.classmate.fragments

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.shuaib.classmate.activities.SessionDetailActivity
import com.shuaib.classmate.adapters.AttendanceSessionsAdapter
import com.shuaib.classmate.models.AttendanceRecord
import com.shuaib.classmate.utils.AttendancePdfGenerator
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class SessionsFragment : Fragment() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AttendanceSessionsAdapter
    private var isAdmin = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        recyclerView = RecyclerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            setPadding(0, 12, 0, 20)
            layoutManager = LinearLayoutManager(requireContext())
        }
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        checkAdminAccess()
        loadSessions()
    }

    private fun setupAdapter() {
        adapter = AttendanceSessionsAdapter(
            onClick = { row ->
                startActivity(Intent(requireContext(), SessionDetailActivity::class.java).apply {
                    putExtra(SessionDetailActivity.EXTRA_SESSION_ID, row.sessionId)
                })
            },
            onDownload = if (isAdmin) { row -> downloadSheet(row.sessionId) } else null
        )
        recyclerView.adapter = adapter
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                val wasAdmin = isAdmin
                isAdmin = role == "admin" || role == "superadmin"
                if (isAdmin != wasAdmin) {
                    setupAdapter()
                }
            }
    }

    private fun loadSessions() {
        val studentsTask = db.collection("users").whereEqualTo("role", "student").get()
        val sessionsTask = db.collection("attendance_sessions")
            .whereEqualTo("status", "closed")
            .orderBy("startTime", Query.Direction.DESCENDING)
            .get()

        Tasks.whenAllSuccess<Any>(studentsTask, sessionsTask)
            .addOnSuccessListener { results ->
                val totalStudents = (results[0] as com.google.firebase.firestore.QuerySnapshot).size()
                val sessions = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents
                
                if (sessions.isEmpty()) {
                    adapter.submitList(emptyList())
                    return@addOnSuccessListener
                }

                val countTasks = sessions.map { session ->
                    db.collection("attendance_records")
                        .whereEqualTo("sessionId", session.id)
                        .whereEqualTo("status", "present")
                        .get()
                }

                Tasks.whenAllSuccess<Any>(countTasks).addOnSuccessListener { countResults ->
                    val rows = sessions.mapIndexed { index, session ->
                        AttendanceSessionsAdapter.AttendanceSessionRow(
                            sessionId = session.id,
                            subject = session.getString("subject") ?: "Attendance",
                            startTime = session.getTimestamp("startTime"),
                            endTime = session.getTimestamp("endTime"),
                            presentCount = (countResults[index] as com.google.firebase.firestore.QuerySnapshot).size(),
                            totalStudents = totalStudents
                        )
                    }
                    adapter.submitList(rows)
                }
            }
    }

    private fun downloadSheet(sessionId: String) {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Downloading sheet...")
            setCancelable(false)
            show()
        }

        db.collection("attendance_sheets").document(sessionId).get()
            .addOnSuccessListener { sheetDoc ->
                if (sheetDoc.exists()) {
                    val storageUrl = sheetDoc.getString("storageUrl")
                    if (storageUrl != null) {
                        downloadFromUrl(storageUrl, sessionId, progress)
                    } else {
                        generateAndShare(sessionId, progress)
                    }
                } else {
                    generateAndShare(sessionId, progress)
                }
            }
            .addOnFailureListener {
                progress.dismiss()
                Toast.makeText(requireContext(), "Failed to check sheet: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun downloadFromUrl(url: String, sessionId: String, progress: ProgressDialog) {
        Thread {
            try {
                val connection = URL(url).openConnection() as java.net.HttpURLConnection
                connection.connect()
                val input = connection.inputStream
                val file = File(requireContext().cacheDir, "attendance_sheet_$sessionId.pdf")
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
                requireActivity().runOnUiThread {
                    progress.dismiss()
                    shareFile(file)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun generateAndShare(sessionId: String, progress: ProgressDialog) {
        val sessionTask = db.collection("attendance_sessions").document(sessionId).get()
        val recordsTask = db.collection("attendance_records").whereEqualTo("sessionId", sessionId).get()

        Tasks.whenAllSuccess<Any>(sessionTask, recordsTask)
            .addOnSuccessListener { results ->
                val session = results[0] as com.google.firebase.firestore.DocumentSnapshot
                val recordsDocs = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents

                val startTime = session.getTimestamp("startTime") ?: com.google.firebase.Timestamp.now()
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
                Toast.makeText(requireContext(), "Generation failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun shareFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share attendance sheet"))
    }
}
