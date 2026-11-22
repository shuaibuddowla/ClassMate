package com.shuaib.classmate.notices

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePresentationMapperTest {

    @Test
    fun testAssessmentTagExtractionAndCleanBody() {
        val rawItem = JSONObject().apply {
            put("id", "notice_123")
            put("title", "CT Announcement")
            put("body", "Please review chapter 1 to 4.\n\n[assessment_id:assessment_uuid_456]")
            put("published_at", "2026-11-22T02:30:00Z")
        }

        val presentation = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = emptyMap(),
            translatingIds = emptySet(),
            translatedIds = emptySet(),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false
        )

        assertEquals("notice_123", presentation.id)
        assertEquals("assessment_uuid_456", presentation.assessmentId)
        assertEquals("Please review chapter 1 to 4.", presentation.cleanBody.toString().trim())
        assertFalse(presentation.cleanBody.contains("[assessment_id:"))
    }

    @Test
    fun testPollPercentageAndZeroTotalVotes() {
        val pollJson = JSONObject().apply {
            put("id", "poll_1")
            put("notice_id", "notice_123")
            put("question", "Exam time?")
            put("total_votes", 0)
            put("total_voters", 0)
            put("options", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "opt_1")
                    put("option_text", "10:00 AM")
                    put("votes_count", 0)
                })
            })
        }

        val pollPresentation = NoticePresentationMapper.mapPoll(pollJson, "user_1")
        assertNotNull(pollPresentation)
        assertEquals(0, pollPresentation!!.totalVotes)
        assertEquals(1, pollPresentation.options.size)
        assertEquals(0f, pollPresentation.options[0].percentage, 0.001f)
    }

    @Test
    fun testPollWithVotesCalculatesPercentages() {
        val pollJson = JSONObject().apply {
            put("id", "poll_2")
            put("notice_id", "notice_123")
            put("question", "Preferred Day?")
            put("total_votes", 4)
            put("total_voters", 4)
            put("options", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "opt_1")
                    put("option_text", "Monday")
                    put("votes_count", 3)
                })
                put(JSONObject().apply {
                    put("id", "opt_2")
                    put("option_text", "Tuesday")
                    put("votes_count", 1)
                })
            })
        }

        val pollPresentation = NoticePresentationMapper.mapPoll(pollJson, "user_1")
        assertNotNull(pollPresentation)
        assertEquals(4, pollPresentation!!.totalVotes)
        assertEquals(75f, pollPresentation.options[0].percentage, 0.001f)
        assertEquals(25f, pollPresentation.options[1].percentage, 0.001f)
    }

    @Test
    fun testPollWithPollIdAndIsMyVoteFromSupabaseRpc() {
        val pollJson = JSONObject().apply {
            put("poll_id", "poll_supabase_uuid")
            put("notice_id", "notice_uuid")
            put("question", "Which room for review?")
            put("total_votes", 10)
            put("total_voters", 8)
            put("is_multiple_choice", true)
            put("options", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "opt_101")
                    put("option_text", "Room 401")
                    put("votes_count", 6)
                    put("is_my_vote", true)
                })
                put(JSONObject().apply {
                    put("id", "opt_102")
                    put("option_text", "Room 402")
                    put("votes_count", 4)
                    put("is_my_vote", false)
                })
            })
        }

        val pollPresentation = NoticePresentationMapper.mapPoll(pollJson, "user_1")
        assertNotNull(pollPresentation)
        assertEquals("poll_supabase_uuid", pollPresentation!!.id)
        assertEquals(10, pollPresentation.totalVotes)
        assertEquals(true, pollPresentation.isMultipleChoice)
        assertEquals(true, pollPresentation.options[0].isSelectedByMe)
        assertEquals(false, pollPresentation.options[1].isSelectedByMe)
        assertEquals(60f, pollPresentation.options[0].percentage, 0.001f)
        assertEquals(40f, pollPresentation.options[1].percentage, 0.001f)
    }

    @Test
    fun testEngagementMappingAndPinnedState() {
        val rawItem = JSONObject().apply {
            put("id", "notice_999")
            put("title", "Important Notice")
            put("body", "Please read this update.")
            put("published_at", "2026-11-22T04:00:00Z")
        }

        val engagementMap = mapOf(
            "notice_999" to JSONObject().apply {
                put("is_liked", true)
                put("like_count", 42)
                put("comment_count", 7)
                put("is_pinned", true)
            }
        )

        val presentation = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = engagementMap,
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = emptyMap(),
            translatingIds = emptySet(),
            translatedIds = emptySet(),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false
        )

        assertTrue(presentation.isLikedByMe)
        assertEquals(42, presentation.reactionCount)
        assertEquals(7, presentation.commentsCount)
        assertTrue(presentation.isPinned)
    }

    @Test
    fun testNoticeTranslationMapping() {
        val rawItem = JSONObject().apply {
            put("id", "notice_trans")
            put("title", "কালকের ক্লাস সংক্রান্ত")
            put("body", "আগামীকাল কোনো ক্লাস হবে না। [assessment_id:test_123]")
            put("published_at", "2026-11-22T04:00:00Z")
        }

        val translationsMap = mapOf(
            "notice_trans" to JSONObject().apply {
                put("title", "Regarding Tomorrow's Class")
                put("body", "There will be no class tomorrow. [assessment_id:test_123]")
                put("source", "কালকের ক্লাস সংক্রান্ত\nআগামীকাল কোনো ক্লাস হবে না। [assessment_id:test_123]")
            }
        )

        // 1. Translated state
        val translatedModel = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = translationsMap,
            translatingIds = emptySet(),
            translatedIds = setOf("notice_trans"),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false
        )

        assertTrue(translatedModel.isTranslated)
        assertFalse(translatedModel.isTranslating)
        assertEquals("Regarding Tomorrow's Class", translatedModel.title)
        assertEquals("There will be no class tomorrow.", translatedModel.cleanBody.toString())
        assertEquals("test_123", translatedModel.assessmentId)

        // 2. Untranslated state
        val originalModel = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = translationsMap,
            translatingIds = emptySet(),
            translatedIds = emptySet(), // Not translated
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false
        )

        assertFalse(originalModel.isTranslated)
        assertEquals("কালকের ক্লাস সংক্রান্ত", originalModel.title)
        assertEquals("আগামীকাল কোনো ক্লাস হবে না।", originalModel.cleanBody.toString())

        // 3. Translating state
        val translatingModel = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = emptyMap(),
            translatingIds = setOf("notice_trans"),
            translatedIds = emptySet(),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false
        )

        assertTrue(translatingModel.isTranslating)
        assertFalse(translatingModel.isTranslated)
        assertEquals("কালকের ক্লাস সংক্রান্ত", translatingModel.title)
    }

    @Test
    fun testNoticeHighlightMapping() {
        val rawItem = JSONObject().apply {
            put("id", "notice_target_999")
            put("title", "Exam Venue Change")
            put("body", "Shifted to Room 402")
        }

        val highlightedModel = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = emptyMap(),
            translatingIds = emptySet(),
            translatedIds = emptySet(),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false,
            highlightedId = "notice_target_999"
        )
        assertTrue(highlightedModel.isHighlighted)

        val notHighlightedModel = NoticePresentationMapper.mapNotice(
            item = rawItem,
            authorMap = emptyMap(),
            engagementMap = emptyMap(),
            readsMap = emptyMap(),
            previewsMap = emptyMap(),
            pollsMap = emptyMap(),
            attachmentsMap = emptyMap(),
            translationsMap = emptyMap(),
            translatingIds = emptySet(),
            translatedIds = emptySet(),
            expandedIds = emptySet(),
            currentUserId = "user_1",
            currentRole = "student",
            isCr = false,
            highlightedId = "other_notice"
        )
        assertFalse(notHighlightedModel.isHighlighted)
    }
}
