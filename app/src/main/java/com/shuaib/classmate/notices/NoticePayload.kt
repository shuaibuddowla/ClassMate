package com.shuaib.classmate.notices

sealed interface NoticePayload {
    data class Like(
        val isLiked: Boolean,
        val count: Int
    ) : NoticePayload

    data class Read(
        val isRead: Boolean,
        val count: Int,
        val seenAvatars: List<String>
    ) : NoticePayload

    data class Poll(
        val poll: PollPresentationModel
    ) : NoticePayload

    data class Translation(
        val isTranslating: Boolean,
        val isTranslated: Boolean,
        val translatedTitle: String?,
        val translatedBody: CharSequence?
    ) : NoticePayload

    data class Expand(
        val isExpanded: Boolean
    ) : NoticePayload

    data class Engagement(
        val likeCount: Int,
        val isLiked: Boolean,
        val commentCount: Int
    ) : NoticePayload
}
