// com/shuaib/classmate/utils/CountdownManager.kt
package com.shuaib.classmate.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.models.Assignment

object CountdownManager {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun addCountdown(
        assignment: Assignment,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run {
            onFailure("Authentication required")
            return
        }
        val countdownId = assignment.id.ifBlank {
            "${assignment.subject}_${assignment.topic}_${assignment.submissionDate}"
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "deadline_${System.currentTimeMillis()}" }
        }
        val data = hashMapOf(
            "assignmentId" to countdownId,
            "subject" to assignment.subject,
            "topic" to assignment.topic,
            "submissionDate" to assignment.submissionDate,
            "type" to assignment.type,
            "addedAt" to FieldValue.serverTimestamp()
        )
        db.collection("users")
            .document(uid)
            .collection("countdowns")
            .document(countdownId)
            .set(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Error") }
    }

    fun removeCountdown(
        assignmentId: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run {
            onFailure("Authentication required")
            return
        }
        if (assignmentId.isBlank()) {
            onFailure("Deadline id missing")
            return
        }
        db.collection("users")
            .document(uid)
            .collection("countdowns")
            .document(assignmentId)
            .delete()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Error") }
    }

    fun getUserCountdowns(
        onResult: (List<Assignment>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users")
            .document(uid)
            .collection("countdowns")
            .orderBy("submissionDate")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val assignments = snapshot.documents.map { doc ->
                    val storedAssignmentId = doc.getString("assignmentId").orEmpty()
                    Assignment(
                        id = storedAssignmentId.ifBlank { doc.id },
                        subject = doc.getString("subject") ?: "",
                        topic = doc.getString("topic") ?: "",
                        submissionDate = doc.getString("submissionDate") ?: "",
                        type = doc.getString("type") ?: "assignment"
                    )
                }
                onResult(assignments)
            }
    }
}
