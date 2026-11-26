package com.shuaib.classmate.notices

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object NoticePresentationMapper {

    private val assessmentIdRegex = Regex("""\[assessment_id:([^\]]*)\]""")
    private val assessmentTagStripRegex = Regex("""\s*\[assessment_id:[^\]]*\]\s*""")

    fun mapNotice(
        item: JSONObject,
        authorMap: Map<String, JSONObject>,
        engagementMap: Map<String, JSONObject>,
        readsMap: Map<String, JSONObject>,
        previewsMap: Map<String, List<JSONObject>>,
        pollsMap: Map<String, JSONObject>,
        attachmentsMap: Map<String, List<JSONObject>>,
        translationsMap: Map<String, JSONObject>,
        translatingIds: Set<String>,
        translatedIds: Set<String>,
        expandedIds: Set<String>,
        currentUserId: String,
        currentRole: String,
        isCr: Boolean,
        highlightedId: String? = null,
        resourcesMap: Map<String, JSONObject> = emptyMap()
    ): NoticePresentationModel {
        val id = item.optString("id")
        val rawTitle = item.optString("title")
        val rawBody = item.optString("body")

        // 1. Assessment ID extraction & body cleaning
        val assessmentMatch = assessmentIdRegex.find(rawBody)
        val assessmentId = assessmentMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val cleanBodyText = rawBody.replace(assessmentTagStripRegex, "").trim()

        // 2. Publication date parsing
        val rawPublishedAt = item.optString("published_at").ifBlank { item.optString("created_at") }
        val publishedAtText = formatNoticeDate(rawPublishedAt)

        // 3. Author details
        val authorObj = authorMap[id]
        val authorId = item.optString("author_id")
        val authorName = authorObj?.optString("full_name")?.ifBlank { "Class Notice" }
            ?: if (authorId.isNotBlank()) "Class Notice" else "Official Notice"
        val authorRole = authorObj?.optString("role") ?: "student"
        val authorIsCr = authorObj?.optBoolean("is_cr") ?: false
        val authorAvatarUrl = authorObj?.optString("avatar_url")?.takeIf { it.isNotBlank() && it != "null" }

        // 4. Category / Type
        val resourceId = item.optString("resource_id").takeUnless { it.isBlank() || it == "null" }
        val resObj = resourcesMap[id]
        val resourceFileType = resObj?.optString("file_type")?.takeUnless { it.isBlank() || it == "null" }
        val resourceLinkUrl = resObj?.optString("link_url")?.takeUnless { it.isBlank() || it == "null" }
        val classChangeId = item.optString("class_change_id").takeUnless { it.isBlank() || it == "null" }
        val isResource = resourceId != null || rawTitle.contains("Resource:", ignoreCase = true)
        val isCancellation = !isResource && (classChangeId != null || rawTitle.contains("Cancelled", ignoreCase = true) || rawTitle.contains("Class Cancel", ignoreCase = true))

        // 5. Poll
        val pollJson = pollsMap[id] ?: item.optJSONObject("poll")
        val pollModel = pollJson?.let { mapPoll(it, currentUserId) }
        val isPoll = !isResource && !isCancellation && (pollModel != null || (item.optString("poll_id").isNotBlank() && item.optString("poll_id") != "null"))

        // 6. Reads & Engagement
        val readsObj = readsMap[id]
        val isReadByMe = readsObj?.optBoolean("read_by_me") ?: false
        val readCount = readsObj?.optInt("read_count") ?: 0
        val previewList = previewsMap[id] ?: emptyList()
        val readerAvatars = previewList.mapNotNull {
            it.optString("avatar_url").takeIf { u -> u.isNotBlank() && u != "null" }
        }

        val engagementObj = engagementMap[id]
        val isLikedByMe = engagementObj?.let {
            if (it.has("is_liked")) it.optBoolean("is_liked") else it.optBoolean("liked_by_me", false)
        } ?: false
        val reactionCount = engagementObj?.let {
            if (it.has("like_count")) it.optInt("like_count") else it.optInt("likes_count", 0)
        } ?: 0
        val commentsCount = engagementObj?.let {
            if (it.has("comment_count")) it.optInt("comment_count") else it.optInt("comments_count", 0)
        } ?: 0
        val isPinned = engagementObj?.optBoolean("is_pinned", false) == true || item.optBoolean("is_pinned", false)

        // 7. Translation & Expansion state
        val isTranslating = id in translatingIds
        val isTranslated = id in translatedIds && translationsMap.containsKey(id)
        val transObj = if (isTranslated) translationsMap[id] else null
        val transTitle = transObj?.optString("title")?.takeIf { it.isNotBlank() }
        val rawTransBody = transObj?.optString("body")?.takeIf { it.isNotBlank() }
        val transBody = rawTransBody?.replace(assessmentTagStripRegex, "")?.trim()

        val displayTitle = if (isTranslated && !transTitle.isNullOrBlank()) transTitle else rawTitle
        val displayBodyText = if (isTranslated && !transBody.isNullOrBlank()) transBody else cleanBodyText
        val isExpanded = id in expandedIds

        // 8. Attachments
        val rawAtts = attachmentsMap[id] ?: emptyList()
        val attachments = rawAtts.map { att ->
            NoticeAttachmentItem(
                id = att.optString("id"),
                storageKey = att.optString("storage_key"),
                fileName = att.optString("file_name").ifBlank { "Attachment" },
                fileType = att.optString("mime_type").ifBlank { att.optString("file_type") },
                url = att.optString("url"),
                sizeBytes = if (att.has("size_bytes")) att.optLong("size_bytes", 0L) else att.optLong("file_size", 0L)
            )
        }

        return NoticePresentationModel(
            id = id,
            title = displayTitle,
            cleanBody = displayBodyText,
            rawBody = rawBody,
            rawPublishedAt = rawPublishedAt,
            publishedAtText = publishedAtText,
            authorId = authorId,
            authorName = authorName,
            authorRole = authorRole,
            authorIsCr = authorIsCr,
            authorAvatarUrl = authorAvatarUrl,
            isResource = isResource,
            isCancellation = isCancellation,
            isPoll = isPoll,
            assessmentId = assessmentId,
            resourceId = resourceId,
            resourceFileType = resourceFileType,
            resourceLinkUrl = resourceLinkUrl,
            classChangeId = classChangeId,
            isReadByMe = isReadByMe,
            readCount = readCount,
            readerAvatars = readerAvatars,
            reactionCount = reactionCount,
            isLikedByMe = isLikedByMe,
            commentsCount = commentsCount,
            isPinned = isPinned,
            translationText = transBody,
            translatedTitle = transTitle,
            translatedBody = transBody,
            isTranslating = isTranslating,
            isExpanded = isExpanded,
            isHighlighted = !highlightedId.isNullOrBlank() && id.equals(highlightedId.trim(), ignoreCase = true),
            poll = pollModel,
            attachments = attachments,
            rawJson = item
        )
    }

    fun mapPoll(pollJson: JSONObject, currentUserId: String): PollPresentationModel? {
        val id = pollJson.optString("poll_id").ifBlank { pollJson.optString("id") }
        if (id.isBlank()) return null
        val question = pollJson.optString("question")
        val isMultipleChoice = pollJson.optBoolean("is_multiple_choice", false)
        val isAnonymous = pollJson.optBoolean("is_anonymous", false)
        val isClosed = pollJson.optBoolean("is_closed", false)
        val totalVotes = pollJson.optInt("total_votes", 0)
        val totalVoters = pollJson.optInt("total_voters", 0)

        val myVotesArray = pollJson.optJSONArray("my_votes") ?: JSONArray()
        val mySelectedOptionIds = mutableSetOf<String>()
        for (i in 0 until myVotesArray.length()) {
            val optId = myVotesArray.optString(i)
            if (optId.isNotBlank()) mySelectedOptionIds.add(optId)
        }

        val optionsArray = pollJson.optJSONArray("options") ?: JSONArray()
        val optionsList = mutableListOf<PollOptionPresentationModel>()
        for (i in 0 until optionsArray.length()) {
            val opt = optionsArray.optJSONObject(i) ?: continue
            val optId = opt.optString("id")
            val optText = opt.optString("option_text")
            val votesCount = opt.optInt("votes_count", 0)
            val isSelectedByMe = optId in mySelectedOptionIds || opt.optBoolean("is_my_vote", false) || opt.optBoolean("selected_by_me", false)
            if (isSelectedByMe) mySelectedOptionIds.add(optId)
            val percentage = if (totalVotes > 0) {
                (votesCount.toFloat() / totalVotes.toFloat()) * 100f
            } else 0f

            optionsList.add(
                PollOptionPresentationModel(
                    id = optId,
                    text = optText,
                    votesCount = votesCount,
                    percentage = percentage,
                    isSelectedByMe = isSelectedByMe
                )
            )
        }

        val noticeId = pollJson.optString("notice_id")
        return PollPresentationModel(
            id = id,
            noticeId = noticeId,
            question = question,
            isMultipleChoice = isMultipleChoice,
            isAnonymous = isAnonymous,
            isClosed = isClosed,
            totalVoters = totalVoters,
            totalVotes = totalVotes,
            canViewVotes = totalVotes > 0 && !isAnonymous,
            mySelectedOptionIds = mySelectedOptionIds,
            options = optionsList
        )
    }

    fun compactCount(count: Long): String = when {
        count >= 1_000_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000_000.0)}m"
        count >= 1_000 -> "${java.text.DecimalFormat("0.#").format(count / 1_000.0)}k"
        else -> count.toString()
    }

    fun formatNoticeDate(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val clean = if (raw.contains(' ') && !raw.contains('T')) {
                val parts = raw.trim().split(" ")
                if (parts.size >= 2) "${parts[0]}T${parts[1]}" else raw
            } else raw
            val instant = Instant.parse(clean)
            val zdt = instant.atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("dd MMM, hh:mm a", Locale.ENGLISH).format(zdt)
        }.getOrDefault(raw)
    }
}
