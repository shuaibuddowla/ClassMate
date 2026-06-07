package com.shuaib.classmate.notices

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore

object NoticeLikeManager {
    private const val DEBUG_TAG = "NoticeActionDebug"
    private const val COLLECTION = "notice_likes"
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun setLiked(
        userId: String,
        noticeId: String,
        liked: Boolean,
        onComplete: (Boolean, Exception?) -> Unit = { _, _ -> }
    ) {
        val authUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authUserId.isNullOrBlank()) {
            logFailure("auth_missing", noticeId, userId, docId(noticeId, userId), IllegalStateException("No signed-in Firebase user."))
            onComplete(false, IllegalStateException("Please sign in again."))
            return
        }
        val effectiveUserId = authUserId.ifBlank { userId }
        if (effectiveUserId.isBlank() || noticeId.isBlank()) {
            logFailure("invalid_input", noticeId, effectiveUserId, docId(noticeId, effectiveUserId), IllegalArgumentException("Missing noticeId or userId."))
            onComplete(false, IllegalArgumentException("Missing noticeId or userId."))
            return
        }
        val id = docId(noticeId, effectiveUserId)
        val doc = db.collection(COLLECTION).document(id)
        val legacyId = legacyDocId(effectiveUserId, noticeId)
        val legacyDoc = db.collection(COLLECTION).document(legacyId)
        db.runTransaction { transaction ->
            val legacyExists = legacyId != id && transaction.get(legacyDoc).exists()
            if (liked) {
                transaction.set(
                    doc,
                    mapOf(
                        "noticeId" to noticeId,
                        "userId" to effectiveUserId,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                if (legacyExists) {
                    transaction.delete(legacyDoc)
                }
            } else {
                transaction.delete(doc)
                if (legacyExists) {
                    transaction.delete(legacyDoc)
                }
            }
        }
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener {
                logFailure(if (liked) "like_notice" else "unlike_notice", noticeId, effectiveUserId, id, it)
                onComplete(false, it)
            }
    }

    private fun docId(noticeId: String, userId: String): String = "${noticeId}_$userId"

    private fun legacyDocId(userId: String, noticeId: String): String = "${userId}_$noticeId"

    private fun logFailure(action: String, noticeId: String, userId: String, documentId: String, error: Exception) {
        val code = (error as? FirebaseFirestoreException)?.code
        Log.e(
            DEBUG_TAG,
            "action=$action noticeId=$noticeId uid=$userId path=$COLLECTION documentId=$documentId code=$code message=${error.message}",
            error
        )
    }
}
