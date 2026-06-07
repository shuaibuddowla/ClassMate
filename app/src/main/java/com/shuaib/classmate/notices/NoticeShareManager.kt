package com.shuaib.classmate.notices

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object NoticeShareManager {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun recordShare(noticeId: String, targetType: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (noticeId.isBlank() || userId.isBlank()) return
        db.collection("notice_shares")
            .add(
                mapOf(
                    "noticeId" to noticeId,
                    "userId" to userId,
                    "targetType" to targetType,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
    }
}

