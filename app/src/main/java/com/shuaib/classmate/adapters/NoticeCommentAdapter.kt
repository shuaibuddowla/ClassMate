package com.shuaib.classmate.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemNoticeCommentBinding
import com.shuaib.classmate.notices.NoticeComment
import com.shuaib.classmate.notices.NoticeUi
import com.shuaib.classmate.utils.ThemeColors

data class NoticeCommentThread(
    val root: NoticeComment,
    val replies: List<NoticeComment> = emptyList()
)

class NoticeCommentAdapter(
    private val currentUserId: String,
    private val isAdminProvider: () -> Boolean,
    private val onLikeClick: (NoticeComment) -> Unit,
    private val onReplyClick: (NoticeComment) -> Unit,
    private val onDeleteClick: (NoticeComment) -> Unit
) : ListAdapter<NoticeCommentThread, NoticeCommentAdapter.CommentViewHolder>(DiffCallback) {

    private var likedCommentIds: Set<String> = emptySet()
    private val expandedCommentIds = mutableSetOf<String>()

    fun setLikedCommentIds(ids: Set<String>) {
        likedCommentIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        return CommentViewHolder(ItemNoticeCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CommentViewHolder(private val binding: ItemNoticeCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(thread: NoticeCommentThread) {
            val comment = thread.root
            bindAvatar(binding.ivAvatar, binding.tvAvatarLetter, comment)
            binding.tvUserName.text = comment.userName
            binding.tvTime.text = NoticeUi.relative(comment.createdAt)
            bindContent(binding.tvContent, comment)
            bindLike(binding.btnCommentLike, comment)
            binding.btnReply.setOnClickListener { onReplyClick(comment) }

            // Highlighting own comment background slightly
            binding.commentRoot.setBackgroundResource(R.drawable.bg_notice_elevated_card)

            binding.commentRoot.setOnLongClickListener {
                if (canDelete(comment)) onDeleteClick(comment)
                true
            }

            val expanded = comment.commentId in expandedCommentIds
            binding.btnViewReplies.isVisible = thread.replies.isNotEmpty()
            binding.btnViewReplies.text = if (expanded) {
                "Hide replies"
            } else {
                "View ${thread.replies.size} ${if (thread.replies.size == 1) "reply" else "replies"}"
            }
            binding.btnViewReplies.setOnClickListener {
                if (expanded) expandedCommentIds.remove(comment.commentId) else expandedCommentIds.add(comment.commentId)
                notifyItemChanged(bindingAdapterPosition)
            }

            binding.repliesContainer.removeAllViews()
            binding.repliesContainer.isVisible = expanded && thread.replies.isNotEmpty()
            if (expanded) {
                thread.replies.forEach { reply ->
                    binding.repliesContainer.addView(createReplyView(reply))
                }
            }
        }

        private fun createReplyView(reply: NoticeComment): View {
            val replyBinding = com.shuaib.classmate.databinding.ItemNoticeReplyBinding.inflate(
                LayoutInflater.from(itemView.context),
                binding.repliesContainer,
                false
            )
            bindAvatar(replyBinding.ivAvatar, replyBinding.tvAvatarLetter, reply)

            replyBinding.tvUserName.text = reply.userName
            replyBinding.tvTime.text = NoticeUi.relative(reply.createdAt)
            bindContent(replyBinding.tvContent, reply)
            bindLike(replyBinding.btnReplyLike, reply)
            replyBinding.btnReply.setOnClickListener { onReplyClick(reply) }

            replyBinding.root.setOnLongClickListener {
                if (canDelete(reply)) onDeleteClick(reply)
                true
            }

            val depth = 1
            (replyBinding.root.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.marginStart = depth * dp(10)
                replyBinding.root.layoutParams = it
            }
            return replyBinding.root
        }

        private fun bindAvatar(imageView: ImageView, textLetter: TextView, comment: NoticeComment) {
            val letter = comment.userName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C"
            textLetter.text = letter

            if (comment.userAvatar.isNotBlank()) {
                textLetter.isVisible = false
                imageView.isVisible = true
                Glide.with(imageView.context)
                    .load(comment.userAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.bg_notice_icon_circle)
                    .error(R.drawable.bg_notice_icon_circle)
                    .into(imageView)
            } else {
                imageView.isVisible = false
                textLetter.isVisible = true
            }
        }

        private fun bindContent(target: TextView, comment: NoticeComment) {
            if (comment.isDeleted) {
                target.text = "This comment was deleted"
                target.setTextColor(ThemeColors.textSecondary(target.context))
                target.setTypeface(null, Typeface.ITALIC)
            } else {
                target.text = comment.content
                target.setTextColor(ThemeColors.textPrimary(target.context))
                target.setTypeface(null, Typeface.NORMAL)
            }
        }

        private fun bindLike(target: TextView, comment: NoticeComment) {
            val liked = comment.commentId in likedCommentIds
            target.text = "${if (liked) "♥" else "♡"} ${comment.likeCount}"
            target.setTextColor(if (liked) Color.parseColor("#FF4D5E") else ThemeColors.textSecondary(target.context))
            target.setOnClickListener {
                it.animate().scaleX(1.2f).scaleY(1.2f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    onLikeClick(comment)
                }.start()
            }
        }

        private fun canDelete(comment: NoticeComment): Boolean {
            return !comment.isDeleted && (comment.userId == currentUserId || isAdminProvider())
        }

        private fun dp(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<NoticeCommentThread>() {
            override fun areItemsTheSame(oldItem: NoticeCommentThread, newItem: NoticeCommentThread): Boolean {
                return oldItem.root.commentId == newItem.root.commentId
            }

            override fun areContentsTheSame(oldItem: NoticeCommentThread, newItem: NoticeCommentThread): Boolean {
                return oldItem == newItem
            }
        }
    }
}