package com.shuaib.classmate.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.models.Assignment
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.*

class MorningBriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val firestore = FirebaseFirestore.getInstance()
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: return Result.success()

        val calendar = Calendar.getInstance()
        val dayName = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            else -> "friday"
        }

        try {
            if (AcademicCalendarRepository(firestore).areAllClassesSuspended(LocalDate.now())) {
                return Result.success()
            }

            // 1. Fetch Today's Classes
            val classesSnapshot = firestore.collection("timetable").document(dayName)
                .collection("periods")
                .orderBy("startTime")
                .get()
                .await()

            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val periods = classesSnapshot.toObjects(Period::class.java).filter {
                !(it.isCancelled && it.cancelDate == todayDate)
            }

            // 2. Fetch Today's Assignments
            val assignmentSnapshot = firestore.collection("users")
                .document(uid)
                .collection("countdowns")
                .get()
                .await()

            val todayAssignments = assignmentSnapshot.documents.mapNotNull { doc ->
                val submissionDate = doc.getString("submissionDate") ?: ""
                if (submissionDate == todayDate) {
                    doc.getString("subject") ?: "Assignment"
                } else null
            }

            if (periods.isNotEmpty() || todayAssignments.isNotEmpty()) {
                val aiBrief = com.shuaib.classmate.services.AIService.generateMorningBrief(periods, todayAssignments)
                showBriefNotification(periods, todayAssignments, aiBrief)
            }

            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    private fun showBriefNotification(periods: List<Period>, assignments: List<String>, aiBrief: String?) {
        val channelId = "morning_brief"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Morning Briefing", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val title = "Good Morning! Today's Schedule 📅"
        val fallbackContent = if (periods.isNotEmpty()) {
            "You have ${periods.size} classes today. Starts at ${periods.first().startTime}."
        } else {
            "No classes today! 🎉"
        }

        val content = aiBrief ?: fallbackContent

        val bigText = StringBuilder().apply {
            if (!aiBrief.isNullOrBlank()) {
                append(aiBrief)
                append("\n\n")
            }
            if (periods.isNotEmpty()) {
                append("Classes:\n")
                periods.forEach { append("- ${it.startTime}: ${it.subject}\n") }
            }
            if (assignments.isNotEmpty()) {
                append("\nAssignments due:\n")
                assignments.forEach { append("- $it\n") }
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.toString()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(800, notification)
    }
}
