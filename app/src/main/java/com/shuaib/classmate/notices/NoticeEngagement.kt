package com.shuaib.classmate.notices

data class NoticeEngagement(
    val noticeId: String,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val isLiked: Boolean = false,
    val isPinned: Boolean = false
)

