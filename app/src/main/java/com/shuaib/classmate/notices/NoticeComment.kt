package com.shuaib.classmate.notices

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

data class NoticeComment(
    val commentId: String = "",
    val noticeId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val content: String = "",
    val parentCommentId: String? = null,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val isDeleted: Boolean = false
) {
    val isReply: Boolean
        get() = !parentCommentId.isNullOrBlank()

    companion object {
        fun from(doc: DocumentSnapshot): NoticeComment {
            return NoticeComment(
                commentId = doc.getString("commentId") ?: doc.id,
                noticeId = doc.getString("noticeId").orEmpty(),
                userId = doc.getString("userId").orEmpty(),
                userName = doc.getString("userName").orEmpty().ifBlank { "Classmate" },
                userAvatar = doc.getString("userAvatar").orEmpty(),
                content = doc.getString("content").orEmpty(),
                parentCommentId = doc.getString("parentCommentId")?.takeIf { it.isNotBlank() },
                likeCount = (doc.getLong("likeCount") ?: 0L).toInt(),
                replyCount = (doc.getLong("replyCount") ?: 0L).toInt(),
                createdAt = doc.getTimestamp("createdAt"),
                updatedAt = doc.getTimestamp("updatedAt"),
                isDeleted = doc.getBoolean("isDeleted") ?: false
            )
        }
    }
}

