package com.shuaib.classmate.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.activities.StudentAttendanceDetailActivity
import com.shuaib.classmate.adapters.AttendanceSummaryAdapter

class SummaryFragment : Fragment() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AttendanceSummaryAdapter

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
        adapter = AttendanceSummaryAdapter { row ->
            startActivity(Intent(requireContext(), StudentAttendanceDetailActivity::class.java).apply {
                putExtra(StudentAttendanceDetailActivity.EXTRA_STUDENT_UID, row.studentUid)
                putExtra(StudentAttendanceDetailActivity.EXTRA_STUDENT_NAME, row.studentName)
            })
        }
        recyclerView.adapter = adapter
        loadSummary()
    }

    private fun loadSummary() {
        val studentsTask = db.collection("users").whereEqualTo("role", "student").get()
        val sessionsTask = db.collection("attendance_sessions").whereEqualTo("status", "closed").get()

        Tasks.whenAllSuccess<Any>(studentsTask, sessionsTask)
            .addOnSuccessListener { results ->
                val students = (results[0] as com.google.firebase.firestore.QuerySnapshot).documents
                val sessions = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents
                val totalSessions = sessions.size
                val recordTasks = students.map { student ->
                    db.collection("attendance_records")
                        .whereEqualTo("studentUid", student.id)
                        .whereEqualTo("status", "present")
                        .get()
                }

                Tasks.whenAllSuccess<Any>(recordTasks).addOnSuccessListener { recordResults ->
                    val rows = students.mapIndexed { index, student ->
                        AttendanceSummaryAdapter.AttendanceSummaryRow(
                            studentUid = student.id,
                            studentName = student.getString("name") ?: student.getString("email") ?: "Student",
                            present = (recordResults[index] as com.google.firebase.firestore.QuerySnapshot).size(),
                            total = totalSessions
                        )
                    }.sortedBy { it.percentage }
                    adapter.submitList(rows)
                }
            }
    }
}
