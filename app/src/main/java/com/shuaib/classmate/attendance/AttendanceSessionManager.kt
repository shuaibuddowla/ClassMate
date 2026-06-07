package com.shuaib.classmate.attendance

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

class AttendanceSessionManager(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    data class AttendanceSession(
        val sessionId: String,
        val classId: String,
        val startMillis: Long
    )

    fun createSession(
        subject: String,
        adminUid: String,
        onSuccess: (AttendanceSession) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val sessionId = UUID.randomUUID().toString()
        val startMillis = System.currentTimeMillis()
        val session = AttendanceSession(
            sessionId = sessionId,
            classId = BleAdvertiser.CLASS_ID,
            startMillis = startMillis
        )

        val data = hashMapOf(
            "subject" to subject,
            "classId" to session.classId,
            "startedBy" to adminUid,
            "startTime" to Timestamp.now(),
            "endTime" to null,
            "status" to "active",
            "bleUUID" to sessionId
        )

        db.collection("attendance_sessions")
            .document(sessionId)
            .set(data)
            .addOnSuccessListener { onSuccess(session) }
            .addOnFailureListener { onFailure(it) }
    }

    fun closeSession(
        sessionId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val sessionRef = db.collection("attendance_sessions").document(sessionId)

        sessionRef.get()
            .continueWithTask { sessionTask ->
                val sessionDoc = sessionTask.result
                val subject = sessionDoc.getString("subject") ?: "Class"
                val classId = sessionDoc.getString("classId") ?: BleAdvertiser.CLASS_ID

                db.collection("users")
                    .whereEqualTo("role", "student")
                    .get()
                    .continueWithTask { studentsTask ->
                        val students = studentsTask.result.documents.filter { doc ->
                            val studentClassId = doc.getString("classId")
                            studentClassId.isNullOrBlank() || studentClassId == classId
                        }

                        db.collection("attendance_records")
                            .whereEqualTo("sessionId", sessionId)
                            .whereEqualTo("status", "present")
                            .get()
                            .continueWithTask { recordsTask ->
                                val presentStudentIds = recordsTask.result.documents
                                    .mapNotNull { it.getString("studentUid") }
                                    .toSet()

                                val batch = db.batch()
                                batch.update(
                                    sessionRef,
                                    mapOf(
                                        "status" to "closed",
                                        "endTime" to Timestamp.now()
                                    )
                                )

                                students
                                    .filter { it.id !in presentStudentIds }
                                    .forEach { studentDoc ->
                                        val absentRecordRef = db.collection("attendance_records")
                                            .document("${sessionId}__${studentDoc.id}")
                                        val studentName = studentDoc.getString("name") ?: "Student"

                                        batch.set(
                                            absentRecordRef,
                                            mapOf(
                                                "sessionId" to sessionId,
                                                "studentUid" to studentDoc.id,
                                                "studentName" to studentName,
                                                "status" to "absent",
                                                "detectedAt" to null
                                            )
                                        )

                                        val queueRef = db.collection("notification_queue").document()
                                        batch.set(
                                            queueRef,
                                            mapOf(
                                                "type" to "attendance_absent",
                                                "targetUid" to studentDoc.id,
                                                "title" to "Attendance marked absent",
                                                "body" to "You were marked absent for $subject.",
                                                "sessionId" to sessionId,
                                                "classId" to classId,
                                                "createdAt" to FieldValue.serverTimestamp(),
                                                "status" to "pending"
                                            )
                                        )
                                    }

                                batch.commit()
                            }
                    }
            }
            .addOnSuccessListener {
                Log.i("AttendanceSessionManager", "Uploading attendance sheet for $sessionId")
                AttendanceSheetUploader.uploadSheet(sessionId)
                onSuccess()
            }
            .addOnFailureListener { onFailure(it) }
    }

    fun getPresentCountQuery(sessionId: String) =
        db.collection("attendance_records")
            .whereEqualTo("sessionId", sessionId)
            .whereEqualTo("status", "present")

    fun getStudentCount(
        onSuccess: (Int) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        db.collection("users")
            .whereEqualTo("role", "student")
            .get()
            .addOnSuccessListener { snapshot ->
                val count = snapshot.documents.count { doc ->
                    val classId = doc.getString("classId")
                    classId.isNullOrBlank() || classId == BleAdvertiser.CLASS_ID
                }
                onSuccess(count)
            }
            .addOnFailureListener { onFailure(it) }
    }
}
