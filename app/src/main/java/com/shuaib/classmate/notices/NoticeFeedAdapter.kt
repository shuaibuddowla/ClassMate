package com.shuaib.classmate.notices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R

class NoticeFeedAdapter(
    private val callbacks: NoticeCardCallbacks,
    private val onRetryPaging: () -> Unit = {}
) : ListAdapter<NoticeFeedRow, RecyclerView.ViewHolder>(NoticeDiffCallback) {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    fun noticeId(position: Int): String? {
        val item = currentList.getOrNull(position)
        return when (item) {
            is NoticeFeedRow.NoticeCard -> item.model.id
            else -> null
        }
    }

    companion object {
        const val VIEW_TYPE_DATE_HEADER = 1
        const val VIEW_TYPE_NOTICE = 2
        const val VIEW_TYPE_PAGING = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is NoticeFeedRow.DateHeader -> VIEW_TYPE_DATE_HEADER
            is NoticeFeedRow.NoticeCard -> VIEW_TYPE_NOTICE
            is NoticeFeedRow.PagingFooter -> VIEW_TYPE_PAGING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val view = inflater.inflate(R.layout.item_notice_date_header, parent, false)
                DateHeaderViewHolder(view)
            }
            VIEW_TYPE_NOTICE -> {
                val view = inflater.inflate(R.layout.item_notice_modern, parent, false)
                NoticeModernViewHolder(view, callbacks)
            }
            VIEW_TYPE_PAGING -> {
                val view = inflater.inflate(R.layout.item_notice_paging, parent, false)
                PagingFooterViewHolder(view, onRetryPaging)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && holder is NoticeModernViewHolder) {
            val item = getItem(position) as? NoticeFeedRow.NoticeCard ?: return
            holder.bindPayloads(payloads, item.model)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is NoticeFeedRow.DateHeader -> (holder as? DateHeaderViewHolder)?.bind(item)
            is NoticeFeedRow.NoticeCard -> (holder as? NoticeModernViewHolder)?.bind(item.model)
            is NoticeFeedRow.PagingFooter -> (holder as? PagingFooterViewHolder)?.bind(item)
        }
    }

    class DateHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvDateHeaderTitle)
        private val dividerLine: View = itemView.findViewById(R.id.dateDividerLine)

        fun bind(header: NoticeFeedRow.DateHeader) {
            tvTitle.text = header.title
            dividerLine.visibility = if (header.isFirst) View.GONE else View.VISIBLE
            val density = itemView.resources.displayMetrics.density
            val topPad = if (header.isFirst) (4 * density).toInt() else (4 * density).toInt()
            val bottomPad = (12 * density).toInt()
            itemView.setPadding(0, topPad, 0, bottomPad)
        }
    }

    class PagingFooterViewHolder(
        itemView: View,
        private val onRetry: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val shimmer: ShimmerFrameLayout = itemView.findViewById(R.id.shimmerOlderNotices)
        private val btnRetry: MaterialButton = itemView.findViewById(R.id.btnNoticePageRetry)

        init {
            btnRetry.setOnClickListener { onRetry() }
        }

        fun bind(paging: NoticeFeedRow.PagingFooter) {
            if (paging.isLoading) {
                shimmer.visibility = View.VISIBLE
                shimmer.startShimmer()
                btnRetry.visibility = View.GONE
            } else if (paging.hasError) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
            } else {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
                btnRetry.visibility = View.GONE
            }
        }
    }

    object NoticeDiffCallback : DiffUtil.ItemCallback<NoticeFeedRow>() {
        override fun areItemsTheSame(oldItem: NoticeFeedRow, newItem: NoticeFeedRow): Boolean {
            return oldItem.stableKey == newItem.stableKey
        }

        override fun areContentsTheSame(oldItem: NoticeFeedRow, newItem: NoticeFeedRow): Boolean {
            return when {
                oldItem is NoticeFeedRow.DateHeader && newItem is NoticeFeedRow.DateHeader -> {
                    oldItem.title == newItem.title && oldItem.isFirst == newItem.isFirst
                }
                oldItem is NoticeFeedRow.PagingFooter && newItem is NoticeFeedRow.PagingFooter -> {
                    oldItem.isLoading == newItem.isLoading && oldItem.hasError == newItem.hasError
                }
                oldItem is NoticeFeedRow.NoticeCard && newItem is NoticeFeedRow.NoticeCard -> {
                    val o = oldItem.model
                    val n = newItem.model
                    o.id == n.id &&
                        o.title == n.title &&
                        o.cleanBody.toString() == n.cleanBody.toString() &&
                        o.publishedAtText == n.publishedAtText &&
                        o.authorName == n.authorName &&
                        o.authorRole == n.authorRole &&
                        o.authorIsCr == n.authorIsCr &&
                        o.authorAvatarUrl == n.authorAvatarUrl &&
                        o.isResource == n.isResource &&
                        o.isCancellation == n.isCancellation &&
                        o.isPoll == n.isPoll &&
                        o.assessmentId == n.assessmentId &&
                        o.resourceId == n.resourceId &&
                        o.resourceFileType == n.resourceFileType &&
                        o.resourceLinkUrl == n.resourceLinkUrl &&
                        o.classChangeId == n.classChangeId &&
                        o.isReadByMe == n.isReadByMe &&
                        o.readCount == n.readCount &&
                        o.readerAvatars == n.readerAvatars &&
                        o.reactionCount == n.reactionCount &&
                        o.isLikedByMe == n.isLikedByMe &&
                        o.commentsCount == n.commentsCount &&
                        o.isPinned == n.isPinned &&
                        o.translationText == n.translationText &&
                        o.isTranslating == n.isTranslating &&
                        o.isExpanded == n.isExpanded &&
                        o.isHighlighted == n.isHighlighted &&
                        o.poll == n.poll &&
                        o.attachments == n.attachments
                }
                else -> false
            }
        }

        override fun getChangePayload(oldItem: NoticeFeedRow, newItem: NoticeFeedRow): Any? {
            if (oldItem !is NoticeFeedRow.NoticeCard || newItem !is NoticeFeedRow.NoticeCard) {
                return null
            }

            val o = oldItem.model
            val n = newItem.model

            // Check if non-text structural properties are unchanged
            val structuralUnchanged = o.publishedAtText == n.publishedAtText &&
                o.authorName == n.authorName &&
                o.authorRole == n.authorRole &&
                o.isResource == n.isResource &&
                o.isCancellation == n.isCancellation &&
                o.isPoll == n.isPoll &&
                o.assessmentId == n.assessmentId &&
                o.resourceId == n.resourceId &&
                o.classChangeId == n.classChangeId &&
                o.attachments == n.attachments &&
                o.isPinned == n.isPinned

            if (!structuralUnchanged) return null

            // 1. Check if only like changed
            val likeChanged = o.isLiked != n.isLiked || o.likeCount != n.likeCount
            val commentsChanged = o.commentCount != n.commentCount
            val readChanged = o.isRead != n.isRead || o.readCount != n.readCount || o.seenAvatars != n.seenAvatars
            val pollChanged = o.poll != n.poll
            val translationChanged = o.isTranslating != n.isTranslating ||
                o.translationText != n.translationText ||
                o.isTranslated != n.isTranslated
            val expandChanged = o.isExpanded != n.isExpanded
            val textChanged = o.title != n.title || o.cleanBody.toString() != n.cleanBody.toString()

            if (translationChanged && !likeChanged && !commentsChanged && !readChanged && !pollChanged && !expandChanged) {
                return NoticePayload.Translation(
                    isTranslating = n.isTranslating,
                    isTranslated = n.isTranslated,
                    translatedTitle = n.title,
                    translatedBody = n.cleanBody
                )
            }

            if (textChanged) return null

            if (likeChanged && !commentsChanged && !readChanged && !pollChanged && !translationChanged && !expandChanged) {
                return NoticePayload.Like(isLiked = n.isLiked, count = n.likeCount)
            }

            if ((likeChanged || commentsChanged) && !readChanged && !pollChanged && !translationChanged && !expandChanged) {
                return NoticePayload.Engagement(
                    likeCount = n.likeCount,
                    isLiked = n.isLiked,
                    commentCount = n.commentCount
                )
            }

            if (readChanged && !likeChanged && !commentsChanged && !pollChanged && !translationChanged && !expandChanged) {
                return NoticePayload.Read(
                    isRead = n.isRead,
                    count = n.readCount,
                    seenAvatars = n.seenAvatars
                )
            }

            if (pollChanged && !likeChanged && !commentsChanged && !readChanged && !translationChanged && !expandChanged && n.poll != null) {
                return NoticePayload.Poll(poll = n.poll)
            }

            if (translationChanged && !likeChanged && !commentsChanged && !readChanged && !pollChanged && !expandChanged) {
                return NoticePayload.Translation(
                    isTranslating = n.isTranslating,
                    isTranslated = n.isTranslated,
                    translatedTitle = n.title,
                    translatedBody = n.translationText
                )
            }

            if (expandChanged && !likeChanged && !commentsChanged && !readChanged && !pollChanged && !translationChanged) {
                return NoticePayload.Expand(isExpanded = n.isExpanded)
            }

            return null
        }
    }
}
