package com.shuaib.classmate.notices

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

object NoticeCommentManager {
    private const val DEBUG_TAG = "NoticeActionDebug"
    private const val COMMENTS_COLLECTION = "notice_comments"
    private const val COMMENT_LIKES_COLLECTION = "comment_likes"
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun addComment(
        noticeId: String,
        userId: String,
        userName: String,
        userAvatar: String,
        content: String,
        parentCommentId: String?,
        onComplete: (Boolean, Exception?) -> Unit
    ) {
        val trimmed = content.trim()
        val authUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authUserId.isNullOrBlank()) {
            logFailure("auth_missing_add_comment", noticeId, userId, "", "", IllegalStateException("No signed-in Firebase user."))
            onComplete(false, IllegalStateException("Please sign in again."))
            return
        }
        val effectiveUserId = authUserId.ifBlank { userId }
        if (noticeId.isBlank() || effectiveUserId.isBlank() || trimmed.isBlank()) {
            logFailure("invalid_add_comment", noticeId, effectiveUserId, COMMENTS_COLLECTION, "", IllegalArgumentException("Missing noticeId, userId, or content."))
            onComplete(false, IllegalArgumentException("Missing noticeId, userId, or content."))
            return
        }
        val doc = db.collection(COMMENTS_COLLECTION).document()
        val comment = hashMapOf<String, Any?>(
            "commentId" to doc.id,
            "noticeId" to noticeId,
            "userId" to effectiveUserId,
            "userName" to userName.ifBlank { "Classmate" },
            "userAvatar" to userAvatar,
            "content" to trimmed,
            "parentCommentId" to parentCommentId,
            "likeCount" to 0,
            "replyCount" to 0,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isDeleted" to false
        )
        val batch = db.batch()
        batch.set(doc, comment)
        batch.commit()
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener {
                logFailure("add_comment", noticeId, effectiveUserId, COMMENTS_COLLECTION, doc.id, it)
                onComplete(false, it)
            }
    }

    fun setCommentLiked(
        userId: String,
        commentId: String,
        liked: Boolean,
        noticeId: String = "",
        onComplete: (Boolean, Exception?) -> Unit = { _, _ -> }
    ) {
        val authUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authUserId.isNullOrBlank()) {
            logFailure("auth_missing_like_comment", noticeId, userId, COMMENT_LIKES_COLLECTION, docId(commentId, userId), IllegalStateException("No signed-in Firebase user."))
            onComplete(false, IllegalStateException("Please sign in again."))
            return
        }
        val effectiveUserId = authUserId.ifBlank { userId }
        if (effectiveUserId.isBlank() || commentId.isBlank()) {
            logFailure("invalid_like_comment", noticeId, effectiveUserId, COMMENT_LIKES_COLLECTION, docId(commentId, effectiveUserId), IllegalArgumentException("Missing commentId or userId."))
            onComplete(false, IllegalArgumentException("Missing commentId or userId."))
            return
        }
        val likeDocId = docId(commentId, effectiveUserId)
        val likeDoc = db.collection(COMMENT_LIKES_COLLECTION).document(likeDocId)
        val legacyLikeDocId = legacyDocId(effectiveUserId, commentId)
        val legacyLikeDoc = db.collection(COMMENT_LIKES_COLLECTION).document(legacyLikeDocId)
        val commentDoc = db.collection(COMMENTS_COLLECTION).document(commentId)
        db.runTransaction { transaction ->
            val hasCurrentLike = transaction.get(likeDoc).exists()
            val hasLegacyLike = legacyLikeDocId != likeDocId && transaction.get(legacyLikeDoc).exists()
            val current = hasCurrentLike || hasLegacyLike
            when {
                liked && !current -> {
                    transaction.set(
                        likeDoc,
                        mapOf(
                            "commentId" to commentId,
                            "userId" to effectiveUserId,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                    transaction.update(commentDoc, "likeCount", FieldValue.increment(1))
                }
                liked && hasLegacyLike && !hasCurrentLike -> {
                    transaction.set(
                        likeDoc,
                        mapOf(
                            "commentId" to commentId,
                            "userId" to effectiveUserId,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                    transaction.delete(legacyLikeDoc)
                }
                !liked && current -> {
                    if (hasCurrentLike) {
                        transaction.delete(likeDoc)
                    }
                    if (hasLegacyLike) {
                        transaction.delete(legacyLikeDoc)
                    }
                    transaction.update(commentDoc, "likeCount", FieldValue.increment(-1))
                }
            }
        }
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener {
                logFailure(if (liked) "like_comment" else "unlike_comment", noticeId, effectiveUserId, COMMENT_LIKES_COLLECTION, likeDocId, it)
                onComplete(false, it)
            }
    }

    fun softDeleteComment(comment: NoticeComment, onComplete: (Boolean) -> Unit = {}) {
        if (comment.commentId.isBlank()) {
            onComplete(false)
            return
        }
        db.collection(COMMENTS_COLLECTION).document(comment.commentId)
            .update(
                mapOf(
                    "content" to "",
                    "isDeleted" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    private fun docId(commentId: String, userId: String): String = "${commentId}_$userId"

    private fun legacyDocId(userId: String, commentId: String): String = "${userId}_$commentId"

    private fun logFailure(
        action: String,
        noticeId: String,
        userId: String,
        collectionPath: String,
        documentId: String,
        error: Exception
    ) {
        val code = (error as? FirebaseFirestoreException)?.code
        Log.e(
            DEBUG_TAG,
            "action=$action noticeId=$noticeId uid=$userId path=$collectionPath documentId=$documentId code=$code message=${error.message}",
            error
        )
    }
}
