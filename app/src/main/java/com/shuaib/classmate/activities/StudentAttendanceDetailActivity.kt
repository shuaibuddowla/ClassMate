package com.shuaib.classmate.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.databinding.ActivityStudentAttendanceDetailBinding
import com.shuaib.classmate.databinding.ItemStudentSessionRowBinding
import java.text.SimpleDateFormat
import java.util.Locale

class StudentAttendanceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentAttendanceDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val adapter = StudentSessionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentAttendanceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvSessions.adapter = adapter

        val uid = intent.getStringExtra(EXTRA_STUDENT_UID).orEmpty()
        val name = intent.getStringExtra(EXTRA_STUDENT_NAME).orEmpty()
        binding.tvStudentName.text = name.ifBlank { "Student" }
        loadHistory(uid)
    }

    private fun loadHistory(uid: String) {
        val sessionsTask = db.collection("attendance_sessions").whereEqualTo("status", "closed").get()
        val recordsTask = db.collection("attendance_records").whereEqualTo("studentUid", uid).get()

        Tasks.whenAllSuccess<Any>(sessionsTask, recordsTask).addOnSuccessListener { results ->
            val sessions = (results[0] as com.google.firebase.firestore.QuerySnapshot).documents
            val records = (results[1] as com.google.firebase.firestore.QuerySnapshot).documents.associateBy {
                it.getString("sessionId").orEmpty()
            }
            val rows = sessions.map { session ->
                val record = records[session.id]
                StudentSessionRow(
                    subject = session.getString("subject") ?: "Attendance",
                    startTime = session.getTimestamp("startTime"),
                    status = record?.getString("status") ?: "absent"
                )
            }.sortedByDescending { it.startTime?.toDate()?.time ?: 0L }
            val present = rows.count { it.status == "present" }
            val percent = if (rows.isEmpty()) 0 else present * 100 / rows.size
            binding.tvPercentage.text = "$percent% attendance"
            binding.tvPercentage.setTextColor(colorFor(percent))
            adapter.submitList(rows)
        }
    }

    private fun colorFor(percent: Int): Int = Color.parseColor(
        when {
            percent >= 75 -> "#34D399"
            percent >= 50 -> "#F59E0B"
            else -> "#FF4D6D"
        }
    )

    data class StudentSessionRow(
        val subject: String,
        val startTime: Timestamp?,
        val status: String
    )

    private class StudentSessionAdapter : ListAdapter<StudentSessionRow, StudentSessionAdapter.ViewHolder>(Diff) {
        inner class ViewHolder(val binding: ItemStudentSessionRowBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemStudentSessionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            val present = row.status == "present"
            holder.binding.tvSubject.text = row.subject
            holder.binding.tvDate.text = row.startTime?.toDate()?.let { dateFormat.format(it) } ?: "-"
            holder.binding.tvStatusBadge.text = if (present) "Present" else "Absent"
            holder.binding.tvStatusBadge.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(if (present) "#34D399" else "#FF4D6D"))
        }
        companion object {
            private val Diff = object : DiffUtil.ItemCallback<StudentSessionRow>() {
                override fun areItemsTheSame(oldItem: StudentSessionRow, newItem: StudentSessionRow) = oldItem.subject == newItem.subject && oldItem.startTime == newItem.startTime
                override fun areContentsTheSame(oldItem: StudentSessionRow, newItem: StudentSessionRow) = oldItem == newItem
            }
            private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
        }
    }

    companion object {
        const val EXTRA_STUDENT_UID = "extra_student_uid"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
    }
}
