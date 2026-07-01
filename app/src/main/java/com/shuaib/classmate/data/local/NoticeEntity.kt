package com.shuaib.classmate.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.Timestamp
import com.shuaib.classmate.models.Notice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

@Entity(
    tableName = "notices",
    indices = [
        Index(value = ["isDeleted", "isPinned", "timestampMillis"]),
        Index(value = ["isDeleted", "timestampMillis"])
    ]
)
data class NoticeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val postedBy: String,
    val timestampMillis: Long,
    val type: String,
    val priority: String,
    val createdBy: String,
    val createdByName: String,
    val updatedAtMillis: Long,
    val deadlineAtMillis: Long,
    val attachmentsJson: String,
    val discussionRoomId: String,
    val isPinned: Boolean,
    val isDeleted: Boolean,
    val discussionCount: Int,
    val isCancel: Boolean,
    val isSub: Boolean,
    val isAssignment: Boolean,
    val isClassTest: Boolean,
    val isResource: Boolean,
    val attachmentType: String,
    val attachmentUrl: String,
    val attachmentName: String,
    val fileId: String,
    val assignmentId: String,
    val subject: String,
    val topic: String,
    val submissionDate: String,
    val deadlineType: String,
    val pdfId: String,
    val cachedAtMillis: Long = System.currentTimeMillis(),
    val readCount: Int = 0
) {
    fun toNotice(): Notice = Notice(
        id = id,
        title = title,
        body = body,
        postedBy = postedBy,
        timestamp = timestampMillis.toTimestampOrNull(),
        type = type,
        priority = priority,
        createdBy = createdBy,
        createdByName = createdByName,
        updatedAt = updatedAtMillis.toTimestampOrNull(),
        deadlineAt = deadlineAtMillis.toTimestampOrNull(),
        attachments = decodeAttachments(attachmentsJson),
        discussionRoomId = discussionRoomId,
        isPinned = isPinned,
        isDeleted = isDeleted,
        discussionCount = discussionCount,
        isCancel = isCancel,
        isSub = isSub,
        isAssignment = isAssignment,
        isClassTest = isClassTest,
        isResource = isResource,
        attachmentType = attachmentType,
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        fileId = fileId,
        assignmentId = assignmentId,
        subject = subject,
        topic = topic,
        submissionDate = submissionDate,
        deadlineType = deadlineType,
        pdfId = pdfId,
        readCount = readCount
    )

    companion object {
        fun fromNotice(notice: Notice): NoticeEntity = NoticeEntity(
            id = notice.id,
            title = notice.title,
            body = notice.body,
            postedBy = notice.postedBy,
            timestampMillis = notice.timestamp?.toDate()?.time ?: 0L,
            type = notice.type,
            priority = notice.priority,
            createdBy = notice.createdBy,
            createdByName = notice.createdByName,
            updatedAtMillis = notice.updatedAt?.toDate()?.time ?: 0L,
            deadlineAtMillis = notice.deadlineAt?.toDate()?.time ?: 0L,
            attachmentsJson = encodeAttachments(notice.attachments),
            discussionRoomId = notice.discussionRoomId,
            isPinned = notice.isPinned,
            isDeleted = notice.isDeleted,
            discussionCount = notice.discussionCount,
            isCancel = notice.isCancel,
            isSub = notice.isSub,
            isAssignment = notice.isAssignment,
            isClassTest = notice.isClassTest,
            isResource = notice.isResource,
            attachmentType = notice.attachmentType,
            attachmentUrl = notice.attachmentUrl,
            attachmentName = notice.attachmentName,
            fileId = notice.fileId,
            assignmentId = notice.assignmentId,
            subject = notice.subject,
            topic = notice.topic,
            submissionDate = notice.submissionDate,
            deadlineType = notice.deadlineType,
            pdfId = notice.pdfId,
            readCount = notice.readCount
        )

        private fun encodeAttachments(attachments: List<Map<String, Any>>): String {
            val array = JSONArray()
            attachments.forEach { attachment ->
                val obj = JSONObject()
                attachment.forEach { (key, value) -> obj.put(key, value) }
                array.put(obj)
            }
            return array.toString()
        }

        private fun decodeAttachments(json: String): List<Map<String, Any>> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                List(array.length()) { index ->
                    val obj = array.optJSONObject(index) ?: JSONObject()
                    obj.keys().asSequence().associateWith { key -> obj.opt(key) ?: "" }
                }
            }.getOrDefault(emptyList())
        }

        private fun Long.toTimestampOrNull(): Timestamp? {
            return if (this > 0L) Timestamp(Date(this)) else null
        }
    }
}
