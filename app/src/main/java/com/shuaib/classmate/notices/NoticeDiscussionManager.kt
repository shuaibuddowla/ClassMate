package com.shuaib.classmate.notices

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.models.Notice

object NoticeDiscussionManager {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun getOrCreateRoom(notice: Notice, onReady: (String) -> Unit, onFallback: () -> Unit) {
        if (notice.id.isBlank()) {
            onFallback()
            return
        }
        val existingRoomId = notice.discussionRoomId.ifBlank { "notice_${notice.id}" }
        if (notice.discussionRoomId.isNotBlank()) {
            onReady(existingRoomId)
            return
        }
        db.collection("notices")
            .document(notice.id)
            .update(
                mapOf(
                    "discussionRoomId" to existingRoomId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener { onReady(existingRoomId) }
            .addOnFailureListener { onFallback() }
    }
}
