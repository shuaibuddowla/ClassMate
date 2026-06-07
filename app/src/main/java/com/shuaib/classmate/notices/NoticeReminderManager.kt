package com.shuaib.classmate.notices

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.models.Notice
import java.util.Date
import java.util.concurrent.TimeUnit

object NoticeReminderManager {
    private const val DEBUG_TAG = "NoticeReminderDebug"
    private const val COLLECTION = "user_notice_reminders"
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun showReminderOptions(context: Context, notice: Notice) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            Toast.makeText(context, "Open notice details to set a reminder", Toast.LENGTH_SHORT).show()
            return
        }
        NoticeReminderBottomSheet.newInstance(notice)
            .show(activity.supportFragmentManager, NoticeReminderBottomSheet.TAG)
    }

    fun getCurrentReminder(noticeId: String, onResult: (Long?) -> Unit) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (userId.isBlank() || noticeId.isBlank()) {
            onResult(null)
            return
        }
        db.collection(COLLECTION)
            .document(docId(userId, noticeId))
            .get()
            .addOnSuccessListener { doc ->
                val reminderTime = doc.takeIf { it.exists() && it.getBoolean("isDone") != true }
                    ?.getTimestamp("reminderAt")
                    ?.toDate()
                    ?.time
                onResult(reminderTime)
            }
            .addOnFailureListener { onResult(null) }
    }

    /**
     * Fetches all active reminders from Firestore and ensures local WorkManager tasks exist.
     * This handles app re-installs and device restarts.
     */
    fun syncRemindersWithLocal(context: Context) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Log.d(DEBUG_TAG, "Starting sync for user: $userId")
        db.collection(COLLECTION)
            .whereEqualTo("userId", userId)
            .whereEqualTo("isDone", false)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc ->
                    val noticeId = doc.getString("noticeId") ?: return@forEach
                    val reminderTime = doc.getTimestamp("reminderAt")?.toDate()?.time ?: return@forEach
                    
                    // Note: Since we only store essential fields, we use generic title/body if not provided.
                    // For a better UX, these could be fetched from notice collection if needed.
                    val title = "Notice Reminder"
                    val body = "Tap to view notice details"

                    if (reminderTime > System.currentTimeMillis()) {
                        scheduleWork(context, userId, noticeId, title, body, reminderTime)
                        Log.d(DEBUG_TAG, "Synced/Rescheduled reminder for notice: $noticeId")
                    } else {
                        // Mark as done if it was in the past
                        doc.reference.update("isDone", true)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(DEBUG_TAG, "Sync failed", e)
            }
    }

    fun schedule(
        context: Context, 
        notice: Notice, 
        reminderAt: Long, 
        label: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        schedule(
            context = context,
            noticeId = notice.id,
            title = notice.title,
            body = NoticeUi.preview(notice, 120),
            reminderAt = reminderAt,
            label = label,
            onComplete = { success, _ -> onComplete(success) }
        )
    }

    fun schedule(
        context: Context,
        noticeId: String,
        title: String,
        body: String,
        reminderAt: Long,
        label: String,
        onComplete: (Boolean, Exception?) -> Unit = { _, _ -> }
    ) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId.isNullOrBlank()) {
            Toast.makeText(context, "Please sign in again.", Toast.LENGTH_SHORT).show()
            onComplete(false, IllegalStateException("No signed-in Firebase user."))
            return
        }
        if (reminderAt <= System.currentTimeMillis()) {
            Toast.makeText(context, "Pick a future reminder time", Toast.LENGTH_SHORT).show()
            onComplete(false, IllegalArgumentException("Reminder time must be in the future."))
            return
        }
        
        val reminderDocId = docId(userId, noticeId)
        val data = hashMapOf(
            "noticeId" to noticeId,
            "userId" to userId,
            "reminderAt" to Timestamp(Date(reminderAt)),
            "createdAt" to FieldValue.serverTimestamp(),
            "isDone" to false
        )

        Log.d(DEBUG_TAG, "Attempting to set reminder. uid=$userId noticeId=$noticeId docId=$reminderDocId reminderAt=${Date(reminderAt)}")
        Log.d(DEBUG_TAG, "Payload keys: ${data.keys}")

        db.collection(COLLECTION)
            .document(reminderDocId)
            .set(data)
            .addOnSuccessListener {
                scheduleWork(context, userId, noticeId, title, body, reminderAt)
                Toast.makeText(context, "Reminder set: $label", Toast.LENGTH_SHORT).show()
                onComplete(true, null)
            }
            .addOnFailureListener {
                logFailure("schedule_reminder", noticeId, userId, reminderDocId, it)
                Toast.makeText(context, "Reminder failed: ${it.message}", Toast.LENGTH_LONG).show()
                onComplete(false, it)
            }
    }

    fun remove(context: Context, noticeId: String, onComplete: (Boolean, Exception?) -> Unit = { _, _ -> }) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val reminderDocId = docId(userId, noticeId)
        db.collection(COLLECTION)
            .document(reminderDocId)
            .delete()
            .addOnSuccessListener {
                WorkManager.getInstance(context).cancelUniqueWork(workName(userId, noticeId))
                Toast.makeText(context, "Reminder removed", Toast.LENGTH_SHORT).show()
                onComplete(true, null)
            }
            .addOnFailureListener {
                logFailure("remove_reminder", noticeId, userId, reminderDocId, it)
                onComplete(false, it)
            }
    }

    private fun scheduleWork(
        context: Context,
        userId: String,
        noticeId: String,
        title: String,
        body: String,
        reminderAt: Long
    ) {
        val request = OneTimeWorkRequestBuilder<NoticeReminderWorker>()
            .setInputData(
                Data.Builder()
                    .putString("noticeId", noticeId)
                    .putString("title", title)
                    .putString("body", body)
                    .build()
            )
            .setInitialDelay(reminderAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(userId, noticeId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun docId(userId: String, noticeId: String): String = "${noticeId}_$userId"

    private fun workName(userId: String, noticeId: String): String = "notice_reminder_${noticeId}_$userId"

    private fun logFailure(action: String, noticeId: String, userId: String, documentId: String, error: Exception) {
        Log.e(DEBUG_TAG, "action=$action noticeId=$noticeId uid=$userId docId=$documentId message=${error.message}", error)
    }
}
