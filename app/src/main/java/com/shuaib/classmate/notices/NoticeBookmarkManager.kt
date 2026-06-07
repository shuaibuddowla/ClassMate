package com.shuaib.classmate.notices

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object NoticeBookmarkManager {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun fetchBookmarks(userId: String, onResult: (Set<String>) -> Unit) {
        if (userId.isBlank()) {
            onResult(emptySet())
            return
        }
        db.collection("user_notice_bookmarks")
            .whereEqualTo("userId", userId)
            .get()
            .addOnSuccessListener { snapshot ->
                onResult(snapshot.documents.mapNotNull { it.getString("noticeId") }.toSet())
            }
            .addOnFailureListener { onResult(emptySet()) }
    }

    fun setBookmarked(
        userId: String,
        noticeId: String,
        bookmarked: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        if (userId.isBlank() || noticeId.isBlank()) {
            onComplete(false)
            return
        }
        val doc = db.collection("user_notice_bookmarks").document("${userId}_$noticeId")
        if (bookmarked) {
            doc.set(
                mapOf(
                    "userId" to userId,
                    "noticeId" to noticeId,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).addOnSuccessListener { onComplete(true) }
                .addOnFailureListener { onComplete(false) }
        } else {
            doc.delete()
                .addOnSuccessListener { onComplete(true) }
                .addOnFailureListener { onComplete(false) }
        }
    }
}
