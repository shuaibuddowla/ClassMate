package com.shuaib.classmate.attendance

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.shuaib.classmate.models.AttendanceRecord
import com.shuaib.classmate.utils.AttendancePdfGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object AttendanceSheetUploader {

    private const val TAG = "AttendanceSheetUploader"
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    fun uploadSheet(sessionId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // a) Fetch session doc
                val sessionDoc = db.collection("attendance_sessions").document(sessionId).get().result
                if (!sessionDoc.exists()) {
                    Log.e(TAG, "Session $sessionId not found")
                    return@launch
                }

                val subject = sessionDoc.getString("subject") ?: "Unknown"
                val startTime = sessionDoc.getTimestamp("startTime") ?: Timestamp.now()
                val sessionDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(startTime.toDate())

                // b) Fetch all attendance_records
                val recordsSnapshot = db.collection("attendance_records")
                    .whereEqualTo("sessionId", sessionId)
                    .get()
                    .result

                val records = recordsSnapshot.documents.map { doc ->
                    AttendanceRecord(
                        studentName = doc.getString("studentName") ?: "Unknown",
                        status = doc.getString("status") ?: "absent",
                        detectedAt = doc.getTimestamp("detectedAt")
                    )
                }

                // c) Generate PDF
                val pdfFile = AttendancePdfGenerator.generateFormalSheet(
                    sessionId = sessionId,
                    sessionDate = sessionDate,
                    records = records,
                    subject = subject
                )

                // d) Upload to Storage
                val storageRef = storage.reference.child("attendance_sheets/$sessionId.pdf")
                val uploadTask = storageRef.putFile(android.net.Uri.fromFile(pdfFile))

                uploadTask.addOnSuccessListener {
                    // Get download URL
                    storageRef.downloadUrl.addOnSuccessListener { uri ->
                        val downloadUrl = uri.toString()

                        // e) Save metadata to Firestore
                        val metadata = hashMapOf(
                            "sessionId" to sessionId,
                            "subject" to subject,
                            "date" to startTime,
                            "storageUrl" to downloadUrl,
                            "uploadedAt" to Timestamp.now()
                        )

                        db.collection("attendance_sheets").document(sessionId)
                            .set(metadata)
                            .addOnSuccessListener {
                                Log.i(TAG, "Sheet uploaded: $downloadUrl")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to save metadata: ${e.message}")
                            }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Failed to get download URL: ${e.message}")
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to upload PDF: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading sheet for $sessionId: ${e.message}")
            }
        }
    }
}
