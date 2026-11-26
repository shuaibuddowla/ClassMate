package com.shuaib.classmate.notices

import org.json.JSONObject

sealed interface NoticeFeedRow {
    val stableKey: String

    data class DateHeader(
        val title: String,
        val isFirst: Boolean
    ) : NoticeFeedRow {
        override val stableKey: String = "header:$title"
    }

    data class NoticeCard(
        val model: NoticePresentationModel
    ) : NoticeFeedRow {
        override val stableKey: String = "notice:${model.id}"
    }

    data class PagingFooter(
        val isLoading: Boolean,
        val hasError: Boolean
    ) : NoticeFeedRow {
        override val stableKey: String = "paging"
    }
}

data class NoticeAttachmentItem(
    val id: String,
    val storageKey: String,
    val fileName: String,
    val fileType: String,
    val url: String,
    val sizeBytes: Long
)

data class PollOptionPresentationModel(
    val id: String,
    val text: String,
    val votesCount: Int,
    val percentage: Float,
    val isSelectedByMe: Boolean
) {
    val percentageFormatted: String get() = "${percentage.toInt()}%"
    val votesFormatted: String get() = "$votesCount ${if (votesCount == 1) "vote" else "votes"}"
    val isMyVote: Boolean get() = isSelectedByMe
}

data class PollPresentationModel(
    val id: String,
    val noticeId: String = "",
    val question: String,
    val isMultipleChoice: Boolean,
    val isAnonymous: Boolean = false,
    val isClosed: Boolean,
    val totalVoters: Int,
    val totalVotes: Int,
    val canViewVotes: Boolean = false,
    val mySelectedOptionIds: Set<String>,
    val options: List<PollOptionPresentationModel>
) {
    val isMultiple: Boolean get() = isMultipleChoice
}

data class NoticePresentationModel(
    val id: String,
    val title: String,
    val cleanBody: CharSequence,
    val rawBody: String,
    val rawPublishedAt: String,
    val publishedAtText: String,
    val authorId: String,
    val authorName: String,
    val authorRole: String,
    val authorIsCr: Boolean,
    val authorAvatarUrl: String?,
    val isResource: Boolean,
    val isCancellation: Boolean,
    val isPoll: Boolean,
    val assessmentId: String?,
    val resourceId: String?,
    val resourceFileType: String? = null,
    val resourceLinkUrl: String? = null,
    val classChangeId: String?,
    val isReadByMe: Boolean,
    val readCount: Int,
    val readerAvatars: List<String>,
    val reactionCount: Int,
    val isLikedByMe: Boolean,
    val commentsCount: Int,
    val isPinned: Boolean,
    val translationText: String?,
    val translatedTitle: String? = null,
    val translatedBody: String? = null,
    val isTranslating: Boolean,
    val isExpanded: Boolean,
    val isHighlighted: Boolean = false,
    val poll: PollPresentationModel?,
    val attachments: List<NoticeAttachmentItem>,
    val rawJson: JSONObject
) {
    val likeCount: Int get() = reactionCount
    val isLiked: Boolean get() = isLikedByMe
    val isRead: Boolean get() = isReadByMe
    val commentCount: Int get() = commentsCount
    val seenAvatars: List<String> get() = readerAvatars

    val isTranslated: Boolean get() = !translationText.isNullOrBlank()

    val imageAttachments: List<NoticeAttachmentItem>
        get() = attachments.filter {
            it.fileType.startsWith("image/") ||
                it.fileName.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE))
        }

    val fileAttachments: List<NoticeAttachmentItem>
        get() = attachments.filterNot {
            it.fileType.startsWith("image/") ||
                it.fileName.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE))
        }
}
