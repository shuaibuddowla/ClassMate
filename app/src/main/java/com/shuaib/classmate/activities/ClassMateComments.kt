package com.shuaib.classmate.activities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Facebook-style premium BottomSheet discussion popup layout for ClassMate notices.
 * Includes inline threaded replies, compact composer, real-time likes, and full edit/delete.
 */
internal object ClassMateComments {

    fun show(
        activity: AppCompatActivity,
        notice: JSONObject,
        changed: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_notice_comments, null, false)
        bottomSheet.setContentView(dialogView)

        val behavior = bottomSheet.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        val noticeTitle = notice.optString("title")
        val noticeId = notice.getString("id")
        val authorId = notice.optString("author_id")

        val tvNoticeSubtitle = dialogView.findViewById<TextView>(R.id.tvNoticeSubtitle)
        val tvCommentCountBadge = dialogView.findViewById<TextView>(R.id.tvCommentCountBadge)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
        val rvComments = dialogView.findViewById<RecyclerView>(R.id.rvComments)
        val tvEmptyComments = dialogView.findViewById<TextView>(R.id.tvEmptyComments)
        val pbLoading = dialogView.findViewById<ProgressBar>(R.id.pbLoading)

        val replyContextBar = dialogView.findViewById<LinearLayout>(R.id.replyContextBar)
        val tvReplyContext = dialogView.findViewById<TextView>(R.id.tvReplyContext)
        val btnCancelReply = dialogView.findViewById<TextView>(R.id.btnCancelReply)

        val ivComposerAvatar = dialogView.findViewById<ImageView>(R.id.ivComposerAvatar)
        val etComment = dialogView.findViewById<EditText>(R.id.etComment)
        val btnSendComment = dialogView.findViewById<ImageButton>(R.id.btnSendComment)

        tvNoticeSubtitle.text = noticeTitle
        btnClose.setOnClickListener { bottomSheet.dismiss() }

        // Setup composer user avatar
        val googleAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(activity)
        googleAccount?.photoUrl?.toString()?.takeIf { it.startsWith("https://") }?.let {
            com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(ivComposerAvatar)
        }

        rvComments.layoutManager = LinearLayoutManager(activity)

        var replyingToComment: JSONObject? = null
        var editingComment: JSONObject? = null
        var isBusy = false
        var requestUuid = UUID.randomUUID().toString()
        var attemptedText: String? = null

        val commentsList = mutableListOf<JSONObject>()
        lateinit var adapter: RecyclerView.Adapter<*>

        fun resetReplyOrEditMode() {
            replyingToComment = null
            editingComment = null
            replyContextBar.visibility = View.GONE
            etComment.hint = "Write a comment..."
        }

        btnCancelReply.setOnClickListener {
            resetReplyOrEditMode()
            etComment.setText("")
        }

        fun executeAsync(block: suspend () -> Unit) {
            if (isBusy) return
            isBusy = true
            btnSendComment.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    block()
                } catch (e: Exception) {
                    if (bottomSheet.isShowing) {
                        Toast.makeText(activity, e.message ?: "Action failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isBusy = false
                    btnSendComment.isEnabled = true
                }
            }
        }

        fun showEditDialog(comment: JSONObject, onUpdated: () -> Unit) {
            val editForm = ClassMateFormUi(activity)
            val field = editForm.field("Comment", true).apply { setText(comment.optString("body")) }
            val editStatus = editForm.status()
            val editor = MaterialAlertDialogBuilder(activity)
                .setTitle("Edit comment")
                .setBackground(ClassMateFeatureUi.surface(activity))
                .setView(editForm.scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .show()

            editor.getButton(-1).setOnClickListener {
                val body = field.text.toString().trim()
                if (body.length !in 1..2000) {
                    field.error = "Use 1–2,000 characters"
                    return@setOnClickListener
                }
                editor.getButton(-1).isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        ClassMateAuthApi.rpcText(
                            "save_notice_comment",
                            JSONObject()
                                .put("target_notice", noticeId)
                                .put("target_body", body)
                                .put("target_id", comment.getString("id"))
                        )
                        comment.put("body", body)
                        editor.dismiss()
                        changed()
                        onUpdated()
                    } catch (e: Exception) {
                        editStatus.visibility = View.VISIBLE
                        editStatus.text = e.message
                        editor.getButton(-1).isEnabled = true
                    }
                }
            }
        }

        fun deleteComment(comment: JSONObject, onDeleted: () -> Unit) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Delete comment?")
                .setMessage("This comment and its replies will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    executeAsync {
                        ClassMateAuthApi.rpcText(
                            "delete_notice_comment",
                            JSONObject().put("target_id", comment.getString("id"))
                        )
                        changed()
                        onDeleted()
                    }
                }
                .show()
        }

        fun toggleLike(comment: JSONObject, onDone: (Boolean, Int) -> Unit) {
            val currentLiked = comment.optBoolean("liked_by_me")
            val nextLiked = !currentLiked
            val currentLikes = comment.optInt("like_count")
            val nextLikes = (currentLikes + if (nextLiked) 1 else -1).coerceAtLeast(0)

            comment.put("liked_by_me", nextLiked)
            comment.put("like_count", nextLikes)
            onDone(nextLiked, nextLikes)

            activity.lifecycleScope.launch {
                runCatching {
                    ClassMateAuthApi.rpcText(
                        "set_comment_like",
                        JSONObject()
                            .put("target_id", comment.getString("id"))
                            .put("target_liked", nextLiked)
                    )
                }
            }
        }

        // Inner Adapter for top-level comments
        class CommentViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
            val tvUserName: TextView = view.findViewById(R.id.tvUserName)
            val tvAuthorBadge: TextView = view.findViewById(R.id.tvAuthorBadge)
            val tvContent: TextView = view.findViewById(R.id.tvContent)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val btnCommentLike: TextView = view.findViewById(R.id.btnCommentLike)
            val btnReply: TextView = view.findViewById(R.id.btnReply)
            val layoutLikesBadge: LinearLayout = view.findViewById(R.id.layoutLikesBadge)
            val tvLikeCount: TextView = view.findViewById(R.id.tvLikeCount)
            val btnViewReplies: TextView = view.findViewById(R.id.btnViewReplies)
            val repliesContainer: LinearLayout = view.findViewById(R.id.repliesContainer)
        }

        fun bindReplyView(parentContainer: LinearLayout, reply: JSONObject) {
            val replyView = LayoutInflater.from(activity).inflate(R.layout.item_notice_reply, parentContainer, false)
            val ivAvatar = replyView.findViewById<ImageView>(R.id.ivAvatar)
            val tvUserName = replyView.findViewById<TextView>(R.id.tvUserName)
            val tvAuthorBadge = replyView.findViewById<TextView>(R.id.tvAuthorBadge)
            val tvContent = replyView.findViewById<TextView>(R.id.tvContent)
            val tvTime = replyView.findViewById<TextView>(R.id.tvTime)
            val btnReplyLike = replyView.findViewById<TextView>(R.id.btnReplyLike)
            val btnReply = replyView.findViewById<TextView>(R.id.btnReply)
            val layoutLikesBadge = replyView.findViewById<LinearLayout>(R.id.layoutLikesBadge)
            val tvLikeCount = replyView.findViewById<TextView>(R.id.tvLikeCount)

            reply.optString("avatar_url").takeIf { it.startsWith("https://") }?.let {
                com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(ivAvatar)
            } ?: ivAvatar.setImageResource(R.drawable.ic_default_avatar)

            tvUserName.text = reply.optString("author_name", "Member")
            tvAuthorBadge.visibility = if (reply.optString("author_id") == authorId) View.VISIBLE else View.GONE
            tvContent.text = reply.optString("body")
            tvTime.text = formatRelativeTime(reply.optString("created_at"))

            val liked = reply.optBoolean("liked_by_me")
            val likes = reply.optInt("like_count")
            btnReplyLike.text = if (liked) "Liked" else "Like"
            btnReplyLike.setTextColor(activity.getColor(if (liked) R.color.cm_primary else R.color.cm_text_secondary))
            if (likes > 0) {
                layoutLikesBadge.visibility = View.VISIBLE
                tvLikeCount.text = likes.toString()
            } else {
                layoutLikesBadge.visibility = View.GONE
            }

            btnReplyLike.setOnClickListener {
                toggleLike(reply) { nextLiked, nextLikes ->
                    btnReplyLike.text = if (nextLiked) "Liked" else "Like"
                    btnReplyLike.setTextColor(activity.getColor(if (nextLiked) R.color.cm_primary else R.color.cm_text_secondary))
                    layoutLikesBadge.visibility = if (nextLikes > 0) View.VISIBLE else View.GONE
                    tvLikeCount.text = nextLikes.toString()
                }
            }

            btnReply.setOnClickListener {
                replyingToComment = reply
                replyContextBar.visibility = View.VISIBLE
                tvReplyContext.text = "Replying to ${reply.optString("author_name")}"
                etComment.hint = "Write a reply…"
                etComment.requestFocus()
            }

            replyView.setOnLongClickListener {
                if (reply.optBoolean("can_edit") || reply.optBoolean("can_delete")) {
                    val popup = PopupMenu(activity, replyView)
                    if (reply.optBoolean("can_edit")) popup.menu.add("Edit")
                    if (reply.optBoolean("can_delete")) popup.menu.add("Delete")
                    popup.setOnMenuItemClickListener { item ->
                        if (item.title == "Edit") {
                            showEditDialog(reply) {
                                tvContent.text = reply.optString("body")
                            }
                        } else {
                            deleteComment(reply) {
                                parentContainer.removeView(replyView)
                            }
                        }
                        true
                    }
                    popup.show()
                }
                true
            }

            parentContainer.addView(replyView)
        }

        adapter = object : RecyclerView.Adapter<CommentViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notice_comment, parent, false)
                return CommentViewHolder(v)
            }

            override fun getItemCount() = commentsList.size

            override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
                val item = commentsList[position]
                val itemAuthorId = item.optString("author_id")

                item.optString("avatar_url").takeIf { it.startsWith("https://") }?.let {
                    com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(holder.ivAvatar)
                } ?: holder.ivAvatar.setImageResource(R.drawable.ic_default_avatar)

                holder.tvUserName.text = item.optString("author_name", "Member")
                holder.tvAuthorBadge.visibility = if (itemAuthorId == authorId) View.VISIBLE else View.GONE
                holder.tvContent.text = item.optString("body")
                holder.tvTime.text = formatRelativeTime(item.optString("created_at"))

                val liked = item.optBoolean("liked_by_me")
                val likes = item.optInt("like_count")
                holder.btnCommentLike.text = if (liked) "Liked" else "Like"
                holder.btnCommentLike.setTextColor(activity.getColor(if (liked) R.color.cm_primary else R.color.cm_text_secondary))
                if (likes > 0) {
                    holder.layoutLikesBadge.visibility = View.VISIBLE
                    holder.tvLikeCount.text = likes.toString()
                } else {
                    holder.layoutLikesBadge.visibility = View.GONE
                }

                holder.btnCommentLike.setOnClickListener {
                    toggleLike(item) { nextLiked, nextLikes ->
                        holder.btnCommentLike.text = if (nextLiked) "Liked" else "Like"
                        holder.btnCommentLike.setTextColor(activity.getColor(if (nextLiked) R.color.cm_primary else R.color.cm_text_secondary))
                        holder.layoutLikesBadge.visibility = if (nextLikes > 0) View.VISIBLE else View.GONE
                        holder.tvLikeCount.text = nextLikes.toString()
                    }
                }

                holder.btnReply.setOnClickListener {
                    replyingToComment = item
                    replyContextBar.visibility = View.VISIBLE
                    tvReplyContext.text = "Replying to ${item.optString("author_name")}"
                    etComment.hint = "Write a reply…"
                    etComment.requestFocus()
                }

                val replyCount = item.optInt("reply_count")
                if (replyCount > 0) {
                    holder.btnViewReplies.visibility = View.VISIBLE
                    val isRepliesOpen = holder.repliesContainer.visibility == View.VISIBLE
                    holder.btnViewReplies.text = if (isRepliesOpen) "Hide replies" else "View $replyCount ${if (replyCount == 1) "reply" else "replies"}"
                } else {
                    holder.btnViewReplies.visibility = View.GONE
                    holder.repliesContainer.visibility = View.GONE
                }

                holder.btnViewReplies.setOnClickListener {
                    if (holder.repliesContainer.visibility == View.VISIBLE) {
                        holder.repliesContainer.visibility = View.GONE
                        holder.btnViewReplies.text = "View $replyCount ${if (replyCount == 1) "reply" else "replies"}"
                    } else {
                        holder.repliesContainer.visibility = View.VISIBLE
                        holder.repliesContainer.removeAllViews()
                        holder.btnViewReplies.text = "Loading replies…"

                        activity.lifecycleScope.launch {
                            try {
                                val replyRows = JSONArray(
                                    ClassMateAuthApi.rpcText(
                                        "comment_page",
                                        JSONObject()
                                            .put("target_notice", noticeId)
                                            .put("target_parent", item.getString("id"))
                                    )
                                )
                                holder.btnViewReplies.text = "Hide replies"
                                for (i in 0 until replyRows.length()) {
                                    bindReplyView(holder.repliesContainer, replyRows.getJSONObject(i))
                                }
                            } catch (e: Exception) {
                                holder.btnViewReplies.text = "View $replyCount replies"
                            }
                        }
                    }
                }

                holder.view.setOnLongClickListener {
                    if (item.optBoolean("can_edit") || item.optBoolean("can_delete")) {
                        val popup = PopupMenu(activity, holder.view)
                        if (item.optBoolean("can_edit")) popup.menu.add("Edit")
                        if (item.optBoolean("can_delete")) popup.menu.add("Delete")
                        popup.setOnMenuItemClickListener { choice ->
                            if (choice.title == "Edit") {
                                showEditDialog(item) {
                                    notifyItemChanged(holder.bindingAdapterPosition)
                                }
                            } else {
                                deleteComment(item) {
                                    val pos = holder.bindingAdapterPosition
                                    if (pos != RecyclerView.NO_POSITION) {
                                        commentsList.removeAt(pos)
                                        notifyItemRemoved(pos)
                                        tvEmptyComments.visibility = if (commentsList.isEmpty()) View.VISIBLE else View.GONE
                                        tvCommentCountBadge.text = commentsList.size.toString()
                                    }
                                }
                            }
                            true
                        }
                        popup.show()
                    }
                    true
                }
            }
        }

        rvComments.adapter = adapter

        fun loadComments() {
            pbLoading.visibility = View.VISIBLE
            activity.lifecycleScope.launch {
                try {
                    val rows = JSONArray(
                        ClassMateAuthApi.rpcText(
                            "comment_page",
                            JSONObject()
                                .put("target_notice", noticeId)
                                .put("target_parent", JSONObject.NULL)
                        )
                    )
                    commentsList.clear()
                    for (i in 0 until rows.length()) {
                        commentsList.add(rows.getJSONObject(i))
                    }
                    adapter.notifyDataSetChanged()
                    tvEmptyComments.visibility = if (commentsList.isEmpty()) View.VISIBLE else View.GONE
                    if (commentsList.isNotEmpty()) {
                        tvCommentCountBadge.visibility = View.VISIBLE
                        tvCommentCountBadge.text = commentsList.size.toString()
                    } else {
                        tvCommentCountBadge.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    if (bottomSheet.isShowing) {
                        Toast.makeText(activity, "Failed to load comments", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    pbLoading.visibility = View.GONE
                }
            }
        }

        btnSendComment.setOnClickListener {
            val text = etComment.text.toString().trim()
            if (text.length !in 1..2000) {
                etComment.error = "Use 1–2,000 characters"
                return@setOnClickListener
            }
            if (attemptedText != text) {
                requestUuid = UUID.randomUUID().toString()
                attemptedText = text
            }
            val parentId = replyingToComment?.optString("id")

            executeAsync {
                ClassMateAuthApi.rpcText(
                    "save_notice_comment",
                    JSONObject()
                        .put("target_notice", noticeId)
                        .put("target_body", text)
                        .put("target_parent", parentId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        .put("target_request", requestUuid)
                )
                etComment.setText("")
                attemptedText = null
                resetReplyOrEditMode()
                changed()
                loadComments()
            }
        }

        bottomSheet.show()
        loadComments()
    }

    private fun formatRelativeTime(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "Just now"
        return runCatching {
            val parsed = java.time.Instant.parse(timestamp)
            val minutes = java.time.Duration.between(parsed, java.time.Instant.now()).toMinutes()
            when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "${minutes}m"
                minutes < 1440 -> "${minutes / 60}h"
                minutes < 10080 -> "${minutes / 1440}d"
                else -> java.time.format.DateTimeFormatter.ofPattern("dd MMM")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(parsed)
            }
        }.getOrElse { "Recently" }
    }
}
