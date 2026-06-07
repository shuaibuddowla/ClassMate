// com/shuaib/classmate/workers/TimetableResetWorker.kt
package com.shuaib.classmate.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class TimetableResetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = FirebaseFirestore.getInstance()
        val days = listOf(
            "saturday", "sunday", "monday",
            "tuesday", "wednesday"
        )
        days.forEach { day ->
            val snapshot = db.collection("timetable")
                .document(day)
                .collection("periods")
                .whereEqualTo("isCancelled", true)
                .get()
                .await()

            snapshot.documents.forEach { doc ->
                doc.reference.update(
                    mapOf(
                        "isCancelled" to false,
                        "cancelledFor" to "",
                        "isSubstitute" to false,
                        "substituteTeacher" to ""
                    )
                ).await()
            }
            
            // Also reset substitutes
            val subSnapshot = db.collection("timetable")
                .document(day)
                .collection("periods")
                .whereEqualTo("isSubstitute", true)
                .get()
                .await()

            subSnapshot.documents.forEach { doc ->
                doc.reference.update(
                    mapOf(
                        "isCancelled" to false,
                        "cancelledFor" to "",
                        "isSubstitute" to false,
                        "substituteTeacher" to ""
                    )
                ).await()
            }
        }
        return Result.success()
    }
}
