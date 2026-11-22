package com.shuaib.classmate.notices

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeFeedAdapterDiffTest {

    private fun createSampleNoticeModel(
        id: String = "n1",
        title: String = "Test Notice",
        cleanBody: String = "Clean body",
        isLiked: Boolean = false,
        likeCount: Int = 0,
        commentCount: Int = 0,
        isRead: Boolean = false,
        readCount: Int = 0,
        seenAvatars: List<String> = emptyList(),
        isExpanded: Boolean = false,
        isTranslating: Boolean = false,
        translationText: String? = null,
        poll: PollPresentationModel? = null
    ): NoticePresentationModel {
        return NoticePresentationModel(
            id = id,
            title = title,
            cleanBody = cleanBody,
            rawBody = cleanBody,
            rawPublishedAt = "2026-11-22T02:30:00Z",
            publishedAtText = "22 Nov, 08:30 AM",
            authorId = "user_1",
            authorName = "CR Alice",
            authorRole = "student",
            authorIsCr = true,
            authorAvatarUrl = null,
            isResource = false,
            isCancellation = false,
            isPoll = poll != null,
            assessmentId = null,
            resourceId = null,
            classChangeId = null,
            isReadByMe = isRead,
            readCount = readCount,
            readerAvatars = seenAvatars,
            reactionCount = likeCount,
            isLikedByMe = isLiked,
            commentsCount = commentCount,
            isPinned = false,
            translationText = translationText,
            isTranslating = isTranslating,
            isExpanded = isExpanded,
            poll = poll,
            attachments = emptyList(),
            rawJson = JSONObject().put("id", id)
        )
    }

    @Test
    fun testAreItemsTheSame() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val row1 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1"))
        val row2 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isLiked = true))
        val row3 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n2"))

        assertTrue(diff.areItemsTheSame(row1, row2))
        assertFalse(diff.areItemsTheSame(row1, row3))

        val header1 = NoticeFeedRow.DateHeader("TODAY", true)
        val header2 = NoticeFeedRow.DateHeader("TODAY", false)
        val header3 = NoticeFeedRow.DateHeader("YESTERDAY", false)
        assertTrue(diff.areItemsTheSame(header1, header2))
        assertFalse(diff.areItemsTheSame(header1, header3))
    }

    @Test
    fun testAreContentsTheSame() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val row1 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1"))
        val row2 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1"))
        val row3 = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isLiked = true, likeCount = 1))

        assertTrue(diff.areContentsTheSame(row1, row2))
        assertFalse(diff.areContentsTheSame(row1, row3))
    }

    @Test
    fun testGetChangePayloadLikeOnly() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val oldRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isLiked = false, likeCount = 0))
        val newRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isLiked = true, likeCount = 1))

        val payload = diff.getChangePayload(oldRow, newRow)
        assertNotNull(payload)
        assertTrue(payload is NoticePayload.Like)
        val likePayload = payload as NoticePayload.Like
        assertTrue(likePayload.isLiked)
        assertEquals(1, likePayload.count)
    }

    @Test
    fun testGetChangePayloadReadReceiptsOnly() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val oldRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isRead = false, readCount = 0))
        val newRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isRead = true, readCount = 5, seenAvatars = listOf("av1", "av2")))

        val payload = diff.getChangePayload(oldRow, newRow)
        assertNotNull(payload)
        assertTrue(payload is NoticePayload.Read)
        val readPayload = payload as NoticePayload.Read
        assertTrue(readPayload.isRead)
        assertEquals(5, readPayload.count)
        assertEquals(2, readPayload.seenAvatars.size)
    }

    @Test
    fun testGetChangePayloadExpandOnly() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val oldRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isExpanded = false))
        val newRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", isExpanded = true))

        val payload = diff.getChangePayload(oldRow, newRow)
        assertNotNull(payload)
        assertTrue(payload is NoticePayload.Expand)
        assertTrue((payload as NoticePayload.Expand).isExpanded)
    }

    @Test
    fun testGetChangePayloadPollOnly() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val poll1 = PollPresentationModel(
            id = "p1",
            noticeId = "n1",
            question = "When?",
            isMultipleChoice = false,
            isClosed = false,
            totalVoters = 1,
            totalVotes = 1,
            canViewVotes = true,
            mySelectedOptionIds = emptySet(),
            options = emptyList()
        )
        val poll2 = poll1.copy(totalVoters = 2, totalVotes = 2, mySelectedOptionIds = setOf("opt1"))

        val oldRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", poll = poll1))
        val newRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", poll = poll2))

        val payload = diff.getChangePayload(oldRow, newRow)
        assertNotNull(payload)
        assertTrue(payload is NoticePayload.Poll)
        assertEquals(2, (payload as NoticePayload.Poll).poll.totalVotes)
    }

    @Test
    fun testGetChangePayloadFullRebindOnTitleChange() {
        val diff = NoticeFeedAdapter.NoticeDiffCallback
        val oldRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", title = "Old Title"))
        val newRow = NoticeFeedRow.NoticeCard(createSampleNoticeModel("n1", title = "New Title", isLiked = true))

        val payload = diff.getChangePayload(oldRow, newRow)
        assertNull(payload) // Must trigger full rebind
    }
}
