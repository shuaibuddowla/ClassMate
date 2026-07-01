// com/shuaib/classmate/adapters/NoticeAdapter.kt
package com.shuaib.classmate.adapters

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.CreatePollActivity
import com.shuaib.classmate.activities.ImageViewerActivity
import com.shuaib.classmate.databinding.ItemNoticeGroupHeaderBinding
import com.shuaib.classmate.databinding.ItemNoticeModernBinding
import com.shuaib.classmate.databinding.ItemPollBinding
import com.shuaib.classmate.databinding.ItemPollOptionBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.models.Poll
import com.shuaib.classmate.notices.NoticeEngagement
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.notices.NoticeUi
import com.shuaib.classmate.chat.AvatarUtils
import com.shuaib.classmate.services.AIService
import com.shuaib.classmate.utils.HapticHelper
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class NoticeGroupHeader(
    val key: String,
    val title: String,
    val count: Int,
    val isCollapsed: Boolean = false,
    val isCollapsible: Boolean = false
)

class NoticeAdapter(
    private val currentUserId: String = "",
    private val isAdminProvider: () -> Boolean = { false },
    private val onNoticeClick: (Notice) -> Unit = {},
    private val onLikeClick: (Notice) -> Unit = {},
    private val onCommentClick: (Notice) -> Unit = {},
    private val onPinClick: (Notice) -> Unit = {},
    private val onForwardClick: (Notice) -> Unit = {},
    private val onReminderClick: (Notice) -> Unit = {},
    private val onNoticeLongClick: (Notice) -> Unit = {},
    private val onCopyClick: (Notice) -> Unit = {},
    private val onPollVote: (pollId: String, option: String) -> Unit = { _, _ -> },
    private val onPollMultiVote: (poll: Poll, option: String) -> Unit = { _, _ -> },
    private val onPollDelete: (pollId: String) -> Unit = { _ -> },
    private val onGroupToggle: (NoticeGroupHeader) -> Unit = {},
    private val onNoticeViewed: (Notice) -> Unit = {},
    private val onReadReceiptsClick: (Notice) -> Unit = {}
) : ListAdapter<Any, RecyclerView.ViewHolder>(DiffCallback) {

    private var engagementByNoticeId: Map<String, NoticeEngagement> = emptyMap()
    private var searchQuery: String = ""
    private var highlightedNoticeId: String? = null
    private val expandedNoticeIds = mutableSetOf<String>()
    private val animatedNoticeIds = mutableSetOf<String>()
    private val aiSummaryCache = mutableMapOf<String, String>()
    private val aiSummaryVisibleIds = mutableSetOf<String>()
    private val aiSummaryLoadingIds = mutableSetOf<String>()
    private val authorAvatarCache = mutableMapOf<String, String?>()
    private val authorNameCache = mutableMapOf<String, String?>()
    private val libraryPdfCache = mutableMapOf<String, PdfFile?>()
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        setHasStableIds(true)
    }

    fun setSearchQuery(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SEARCH)
    }

    fun setHighlightedNotice(noticeId: String?) {
        val oldId = highlightedNoticeId
        highlightedNoticeId = noticeId

        if (oldId != null && oldId != noticeId) {
            val oldPos = currentList.indexOfFirst { it is Notice && it.id == oldId }
            if (oldPos != -1) notifyItemChanged(oldPos)
        }

        if (noticeId != null) {
            val newPos = currentList.indexOfFirst { it is Notice && it.id == noticeId }
            if (newPos != -1) {
                notifyItemChanged(newPos, PAYLOAD_HIGHLIGHT)
            }
        }
    }

    fun setEngagementState(state: Map<String, NoticeEngagement>) {
        val previousState = engagementByNoticeId
        engagementByNoticeId = state
        val changedNoticeIds = (previousState.keys + state.keys)
            .filter { previousState[it] != state[it] }
            .toSet()
        if (changedNoticeIds.isEmpty()) return

        currentList.forEachIndexed { index, item ->
            if (item is Notice && item.id in changedNoticeIds) {
                notifyItemChanged(index, PAYLOAD_LIKE)
            }
        }
    }

    fun clearAnimationRegistry() {
        animatedNoticeIds.clear()
    }

    fun expandNotice(noticeId: String) {
        expandedNoticeIds.add(noticeId)
        val pos = currentList.indexOfFirst { it is Notice && it.id == noticeId }
        if (pos != -1) notifyItemChanged(pos)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is NoticeGroupHeader -> stableId("header:${item.key}")
            is Notice -> stableId("notice:${item.id}")
            is Poll -> stableId("poll:${item.id}")
            else -> RecyclerView.NO_ID
        }
    }



    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is NoticeGroupHeader -> TYPE_HEADER
            is Notice -> TYPE_NOTICE
            is Poll -> TYPE_POLL
            else -> error("Unsupported notice item")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_NOTICE -> NoticeViewHolder(ItemNoticeModernBinding.inflate(inflater, parent, false))
            TYPE_POLL -> PollViewHolder(ItemPollBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderViewHolder(ItemNoticeGroupHeaderBinding.inflate(inflater, parent, false))
            else -> error("Unsupported view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NoticeViewHolder -> holder.bind(getItem(position) as Notice, emptyList())
            is PollViewHolder -> {
                holder.bind(getItem(position) as Poll)
            }
            is HeaderViewHolder -> {
                holder.bind(getItem(position) as NoticeGroupHeader)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is NoticeViewHolder) {
            val notice = getItem(position) as Notice
            if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
                holder.startHighlightAnimation(holder.binding.cardRoot)
                return
            }
            if (payloads.contains(PAYLOAD_LIKE)) {
                holder.bindInteractiveState(notice)
                return
            }
            if (payloads.contains(PAYLOAD_SEARCH)) {
                holder.bind(notice, payloads)
                return
            }
            if (payloads.contains(PAYLOAD_PIN)) {
                val accent = NoticeUi.accent(holder.itemView.context, notice.displayType)
                val cardFill = themedNoticeCardFill(holder.itemView.context, accent)
                holder.bindPinState(notice, cardFill)
                return
            }
            holder.bind(notice, payloads)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    inner class HeaderViewHolder(private val binding: ItemNoticeGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: NoticeGroupHeader) {
            binding.tvGroupTitle.text = header.title
            binding.tvGroupCount.text = if (header.count == 1) "1 update" else "${header.count} updates"
            binding.ivGroupArrow.isVisible = header.isCollapsible
            binding.ivGroupArrow.rotation = if (header.isCollapsed) 0f else 90f
            binding.root.setOnClickListener { if (header.isCollapsible) onGroupToggle(header) }
        }
    }

    inner class NoticeViewHolder(val binding: ItemNoticeModernBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(notice: Notice, payloads: List<Any>) {
            val type = notice.displayType
            val accent = NoticeUi.accent(itemView.context, type)
            val cardFill = themedNoticeCardFill(itemView.context, accent)
            val cardStroke = if (ThemeColors.isDark(itemView.context)) {
                ColorUtils.setAlphaComponent(accent, 118)
            } else {
                ColorUtils.blendARGB(ThemeColors.divider(itemView.context), accent, 0.10f)
            }
            binding.cardRoot.setStrokeColor(ColorStateList.valueOf(cardStroke))
            binding.cardRoot.setCardBackgroundColor(ColorStateList.valueOf(cardFill))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                binding.cardRoot.outlineAmbientShadowColor = ColorUtils.setAlphaComponent(accent, 72)
                binding.cardRoot.outlineSpotShadowColor = ColorUtils.setAlphaComponent(accent, 92)
            }
            binding.tvTypeLabel.text = NoticeUi.typeLabel(type)
            binding.tvTypeLabel.setTextColor(accent)

            // Dynamic User Profile Fetching and Binding for Notice Card Author
            val authorId = notice.createdBy.ifBlank { notice.postedBy }
            if (authorId.isNotBlank()) {
                val cachedAvatar = authorAvatarCache[authorId]
                val cachedName = authorNameCache[authorId]
                if (cachedAvatar != null || authorAvatarCache.containsKey(authorId)) {
                    AvatarUtils.bind(binding.ivAuthorAvatar, binding.tvAuthorAvatarLetter, authorId, cachedName ?: notice.displayAuthor, cachedAvatar)
                    binding.tvAuthorName.text = cachedName ?: notice.displayAuthor
                } else {
                    binding.tvAuthorName.text = notice.displayAuthor
                    AvatarUtils.bind(binding.ivAuthorAvatar, binding.tvAuthorAvatarLetter, authorId, notice.displayAuthor, null)
                    
                    authorAvatarCache[authorId] = null
                    authorNameCache[authorId] = notice.displayAuthor
                    
                    FirebaseFirestore.getInstance().collection("users").document(authorId).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                val photoUrl = doc.getString("photoUrl")
                                val fullName = doc.getString("fullName")?.takeIf { it.isNotBlank() } 
                                    ?: doc.getString("name")?.takeIf { it.isNotBlank() }
                                if (!photoUrl.isNullOrBlank()) {
                                    authorAvatarCache[authorId] = photoUrl
                                }
                                if (!fullName.isNullOrBlank()) {
                                    authorNameCache[authorId] = fullName
                                }
                                val pos = currentList.indexOfFirst { it is Notice && (it.createdBy == authorId || it.postedBy == authorId) }
                                if (pos != -1) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                }
            } else {
                binding.tvAuthorName.text = notice.displayAuthor
                AvatarUtils.bind(binding.ivAuthorAvatar, binding.tvAuthorAvatarLetter, "", notice.displayAuthor, null)
            }

            val isGeneralNotice = NoticeUi.typeLabel(notice.displayType) == "GENERAL NOTICE"
            val shouldShowSubject = NoticeUi.shouldShowHeaderSubject(notice)
            binding.tvBullet.isVisible = false
            if (shouldShowSubject) {
                binding.tvSubject.text = NoticeUi.headerSubject(notice)
                binding.tvSubject.isVisible = true
                binding.tvSubject.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 24))
                binding.tvSubject.setTextColor(accent)
            } else if (isGeneralNotice) {
                binding.tvSubject.text = "Announcement"
                binding.tvSubject.isVisible = true
                binding.tvSubject.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 24))
                binding.tvSubject.setTextColor(accent)
            } else {
                binding.tvSubject.isVisible = false
            }

            // Title with Search Highlighting
            val title = notice.title.ifBlank { "Untitled notice" }
            if (searchQuery.isNotBlank() && title.contains(searchQuery, ignoreCase = true)) {
                binding.tvTitle.text = highlightSearchText(title, searchQuery, ThemeColors.primarySoft(itemView.context))
            } else {
                binding.tvTitle.text = title
            }

            // Image Preview Upgrade
            val imageUrl = notice.attachments.firstOrNull { it["type"] == "image" }?.get("url")?.toString()
                ?: notice.attachmentUrl.takeIf { notice.attachmentType == "image" }

            val hasImage = !imageUrl.isNullOrBlank()
            binding.imagePreviewContainer.isVisible = hasImage
            if (hasImage) {
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_notice_modern_card)
                    .centerCrop()
                    .into(binding.ivPreview)

                // Launch full-screen image viewer on tap
                binding.imagePreviewContainer.setOnClickListener {
                    val intent = Intent(itemView.context, ImageViewerActivity::class.java)
                        .putExtra(ImageViewerActivity.EXTRA_IMAGE_URL, imageUrl)
                    itemView.context.startActivity(intent)
                }
            } else {
                binding.imagePreviewContainer.setOnClickListener(null)
            }

            // Document / File Attachments Binding (PDF/Doc/PPT)
            val fileAttachment = notice.attachments.firstOrNull { it["type"] != "image" }
                ?: if (notice.attachmentType != "none" && notice.attachmentType != "image") {
                    mapOf("name" to notice.attachmentName, "url" to notice.attachmentUrl, "type" to notice.attachmentType)
                } else null

            if (fileAttachment != null) {
                binding.fileAttachmentContainer.visibility = View.VISIBLE
                val fileName = fileAttachment["name"]?.toString() ?: "Attachment"
                val fileUrl = fileAttachment["url"]?.toString().orEmpty()
                val fileType = (fileAttachment["type"]?.toString() ?: "pdf").lowercase()

                binding.tvFileName.text = fileName
                binding.tvFileExtension.text = fileType.uppercase()
                
                val (iconBg, iconColor) = when (fileType) {
                    "pdf" -> Pair(R.drawable.bg_library_file_icon_red, R.color.cm_file_pdf_text)
                    "doc", "docx" -> Pair(R.drawable.bg_library_file_icon_blue, R.color.cm_file_doc_text)
                    "ppt", "pptx" -> Pair(R.drawable.bg_library_file_icon_yellow, R.color.cm_file_ppt_text)
                    else -> Pair(R.drawable.bg_library_file_icon_purple, R.color.cm_file_other)
                }
                binding.fileIconContainer.setBackgroundResource(iconBg)
                binding.tvFileExtension.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, iconColor))
                binding.tvFileSize.text = "Tap to open ${fileType.uppercase()} file"

                val openAction = View.OnClickListener {
                    if (fileUrl.isNotBlank()) {
                        try {
                            HapticHelper.lightPop(it)
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fileUrl))
                            itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(itemView.context, "No app available to open this link.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                binding.fileAttachmentContainer.setOnClickListener(openAction)
                binding.btnOpenFile.setOnClickListener(openAction)
            } else if (notice.isResource && notice.pdfId.isNotBlank()) {
                val pdfId = notice.pdfId
                if (libraryPdfCache.containsKey(pdfId)) {
                    val pdfFile = libraryPdfCache[pdfId]
                    if (pdfFile != null) {
                        binding.fileAttachmentContainer.visibility = View.VISIBLE
                        binding.tvFileName.text = pdfFile.title
                        val fileType = pdfFile.fileType.lowercase()
                        binding.tvFileExtension.text = fileType.uppercase()
                        
                        val (iconBg, iconColor) = when (fileType) {
                            "pdf" -> Pair(R.drawable.bg_library_file_icon_red, R.color.cm_file_pdf_text)
                            "doc", "docx" -> Pair(R.drawable.bg_library_file_icon_blue, R.color.cm_file_doc_text)
                            "ppt", "pptx" -> Pair(R.drawable.bg_library_file_icon_yellow, R.color.cm_file_ppt_text)
                            else -> Pair(R.drawable.bg_library_file_icon_purple, R.color.cm_file_other)
                        }
                        binding.fileIconContainer.setBackgroundResource(iconBg)
                        binding.tvFileExtension.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, iconColor))
                        
                        val sizeStr = formatBytes(pdfFile.sizeBytes)
                        binding.tvFileSize.text = if (sizeStr.isNotBlank()) "Tap to open · $sizeStr" else "Tap to open attachment"
                        
                        val openAction = View.OnClickListener {
                            val activity = itemView.context as? android.app.Activity
                            if (activity != null) {
                                com.shuaib.classmate.utils.PdfDialogHelper.showPdfOptions(activity, itemView.context, pdfFile)
                            } else {
                                val fileUrl = pdfFile.downloadUrl.ifBlank { pdfFile.driveUrl }
                                if (fileUrl.isNotBlank()) {
                                    try {
                                        HapticHelper.lightPop(it)
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fileUrl))
                                        itemView.context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(itemView.context, "No app available to open this link.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        binding.fileAttachmentContainer.setOnClickListener(openAction)
                        binding.btnOpenFile.setOnClickListener(openAction)
                    } else {
                        binding.fileAttachmentContainer.visibility = View.GONE
                    }
                } else {
                    binding.fileAttachmentContainer.visibility = View.GONE
                    // Trigger Firestore fetch
                    libraryPdfCache[pdfId] = null
                    FirebaseFirestore.getInstance().collection("library_files").document(pdfId).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists() && doc.getBoolean("isDeleted") != true) {
                                val pdfFile = doc.toPdfFile()
                                libraryPdfCache[pdfId] = pdfFile
                                currentList.forEachIndexed { index, item ->
                                    if (item is Notice && item.pdfId == pdfId) {
                                        notifyItemChanged(index)
                                    }
                                }
                            } else {
                                libraryPdfCache[pdfId] = null
                            }
                        }
                        .addOnFailureListener {
                            libraryPdfCache[pdfId] = null
                        }
                }
            } else {
                binding.fileAttachmentContainer.visibility = View.GONE
            }

            val noticeText = normalizedNoticeText(notice)
            bindPreview(binding.tvPreview, notice, noticeText, accent)
            bindCardAiSummary(notice, noticeText)
            binding.tvMeta.text = "${NoticeUi.formatDate(notice.createdAt)} · ${NoticeUi.formatTime(notice.createdAt)}"

            val priorityLabel = NoticeUi.priorityLabel(notice)
            binding.tvCountdown.isVisible = notice.deadlineAt != null
            binding.tvCountdown.text = priorityLabel
            val countdownBase = countdownColor(priorityLabel, notice.displayPriority)
            binding.tvCountdown.background = countdownChip(countdownBase)
            binding.tvCountdown.setTextColor(ColorUtils.blendARGB(Color.WHITE, countdownBase, 0.22f))

            val actionMuted = themedActionMuted(itemView.context, cardFill)
            bindEngagement(notice, actionMuted)

            bindPinState(notice, cardFill)
            val isAdmin = isAdminProvider()
            binding.btnOptions.isVisible = isAdmin
            binding.btnReadReceipts.isVisible = isAdmin
            if (isAdmin) {
                binding.tvReadCount.text = notice.readCount.toString()
                binding.btnReadReceipts.setOnClickListener {
                    HapticHelper.lightPop(it)
                    it.animateSpringScale(1.1f)
                    onReadReceiptsClick(notice)
                }
            }

            if (isAdmin) {
                binding.btnOptions.setOnClickListener {
                    HapticHelper.mediumThud(it)
                    it.animateSpringScale(1.15f)
                    onNoticeLongClick(notice)
                }
            }

            binding.cardRoot.setOnClickListener {
                HapticHelper.mediumThud(it)
                it.animateSpringScale(0.97f)
                toggleExpansion(notice)
            }
            binding.cardRoot.setOnLongClickListener(null)

            binding.tvTitle.setOnClickListener(null)
            binding.tvTitle.setOnLongClickListener {
                HapticHelper.heavyClick(it)
                onCopyClick(notice)
                true
            }

            binding.tvPreview.setOnClickListener(null)
            binding.tvPreview.setOnLongClickListener {
                HapticHelper.heavyClick(it)
                onCopyClick(notice)
                true
            }

            binding.btnLike.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.15f)
                onLikeClick(notice)
            }
            binding.btnComment.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.1f)
                onCommentClick(notice)
            }
            binding.btnForward.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.1f)
                onForwardClick(notice)
            }
            binding.btnReminder.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.1f)
                onReminderClick(notice)
            }
            binding.btnPin.setOnClickListener {
                if (!isAdminProvider()) return@setOnClickListener
                HapticHelper.mediumThud(it)
                it.animateSpringScale(1.2f)
                onPinClick(notice)
            }

            onNoticeViewed(notice)

            binding.cardRoot.animate().cancel()
            val isPartialUpdate = payloads.isNotEmpty()
            val shouldAnimate = !isPartialUpdate && !animatedNoticeIds.contains(notice.id)

            if (notice.id == highlightedNoticeId) {
                startHighlightAnimation(binding.cardRoot)
            }

            if (shouldAnimate) {
                animatedNoticeIds.add(notice.id)
                binding.cardRoot.alpha = 0f
                binding.cardRoot.translationY = dp(24).toFloat()
                binding.cardRoot.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                binding.cardRoot.alpha = 1f
                binding.cardRoot.translationY = 0f
            }
        }

        fun bindInteractiveState(notice: Notice) {
            binding.cardRoot.animate().cancel()
            binding.cardRoot.alpha = 1f
            binding.cardRoot.translationY = 0f
            val cardFill = themedNoticeCardFill(itemView.context, NoticeUi.accent(itemView.context, notice.displayType))
            val actionMuted = themedActionMuted(itemView.context, cardFill)
            bindEngagement(notice, actionMuted)
            bindPinState(notice, cardFill)
        }

        private fun bindEngagement(notice: Notice, actionMuted: Int) {
            val engagement = engagementByNoticeId[notice.id] ?: NoticeEngagement(noticeId = notice.id, isPinned = notice.isPinned)
            val ctx = itemView.context
            
            // Like Pill
            if (engagement.isLiked) {
                val likeActiveColor = ThemeColors.error(ctx)
                binding.btnLike.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(likeActiveColor, 32))
                binding.ivLikeIcon.setImageResource(R.drawable.ic_heart_filled)
                binding.ivLikeIcon.setColorFilter(likeActiveColor)
                binding.tvLikeCount.setTextColor(likeActiveColor)
            } else {
                binding.btnLike.backgroundTintList = null
                binding.ivLikeIcon.setImageResource(R.drawable.ic_heart_outline)
                binding.ivLikeIcon.setColorFilter(actionMuted)
                binding.tvLikeCount.setTextColor(actionMuted)
            }
            binding.tvLikeCount.text = compactCount(engagement.likeCount)

            // Comment Pill (Disabled)
            binding.btnComment.visibility = View.GONE
            binding.btnComment.backgroundTintList = null
            binding.ivCommentIcon.setColorFilter(actionMuted)
            binding.tvCommentCount.text = "0"
            binding.tvCommentCount.setTextColor(actionMuted)

            // Reminder Pill
            binding.btnReminder.backgroundTintList = null
            binding.ivReminderIcon.setColorFilter(actionMuted)

            // Share Pill
            binding.btnForward.backgroundTintList = null
            binding.ivShareIcon.setColorFilter(actionMuted)
            binding.tvShareCount.text = compactCount(engagement.shareCount)
            binding.tvShareCount.setTextColor(actionMuted)
            binding.tvShareCount.isVisible = engagement.shareCount > 0
        }

        fun bindPinState(notice: Notice, cardFill: Int) {
            val engagement = engagementByNoticeId[notice.id] ?: NoticeEngagement(noticeId = notice.id, isPinned = notice.isPinned)
            val pinned = engagement.isPinned || notice.isPinned
            binding.btnPin.isVisible = true
            binding.btnPin.setImageResource(if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline)
            binding.btnPin.setColorFilter(
                if (pinned) ThemeColors.warning(itemView.context)
                else ColorUtils.blendARGB(ThemeColors.textSecondary(itemView.context), cardFill, 0.24f)
            )
            binding.btnPin.setBackgroundResource(if (pinned) R.drawable.bg_notice_pin_active else R.drawable.bg_notice_icon_circle)
            binding.btnPin.alpha = if (pinned || isAdminProvider()) 1f else 0.62f
        }

        fun startHighlightAnimation(view: View) {
            view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(500)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(500)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }.start()

            if (view is com.google.android.material.card.MaterialCardView) {
                val highlightColor = ThemeColors.primary(view.context)
                val originalStrokeColor = view.strokeColorStateList?.defaultColor ?: Color.TRANSPARENT
                val originalStrokeWidth = view.strokeWidth

                android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                    duration = 2000
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        val color = ColorUtils.blendARGB(originalStrokeColor, highlightColor, fraction)
                        view.setStrokeColor(ColorStateList.valueOf(color))

                        // Also pulse the width slightly
                        val widthScale = 1f + (fraction * 0.5f)
                        view.strokeWidth = (originalStrokeWidth * widthScale).toInt()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            view.setStrokeColor(ColorStateList.valueOf(originalStrokeColor))
                            view.strokeWidth = originalStrokeWidth
                            // Clear highlight ID so it doesn't re-animate on scroll
                            if (highlightedNoticeId != null) {
                                val currentItem = getItem(bindingAdapterPosition)
                                if (currentItem is Notice && currentItem.id == highlightedNoticeId) {
                                    highlightedNoticeId = null
                                }
                            }
                        }
                    })
                    start()
                }
            }
        }

        private fun countdownColor(label: String, priority: String): Int {
            return when {
                label.contains("today", ignoreCase = true) -> ThemeColors.warning(itemView.context)
                priority == "high" -> ThemeColors.error(itemView.context)
                label.contains("day", ignoreCase = true) -> ThemeColors.success(itemView.context)
                else -> ThemeColors.info(itemView.context)
            }
        }

        private fun countdownChip(base: Int): GradientDrawable {
            val fill = ColorUtils.setAlphaComponent(base, 0x24)
            return GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(base, 0x92))
            }
        }

        private fun actionStripScrim(): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
        }

        private fun isLongNotice(text: String): Boolean {
            return text.length > 240 || hasPreviewLineOverflow(text)
        }

        private fun bindPreview(target: TextView, notice: Notice, fullText: String, linkColor: Int) {
            val isExpanded = notice.id in expandedNoticeIds
            target.tag = notice.id
            target.highlightColor = Color.TRANSPARENT
            target.setTextIsSelectable(false)
            target.isVerticalScrollBarEnabled = false

            if (fullText.isBlank()) {
                target.movementMethod = null
                target.maxLines = Int.MAX_VALUE
                target.ellipsize = null
                target.text = "No description provided."
                target.setOnClickListener(null)
                return
            }

            val context = itemView.context
            val isLong = isLongNotice(fullText)

            // Forward text clicks to card root for unified haptic feedback and spring animations
            target.setOnClickListener {
                binding.cardRoot.performClick()
            }

            if (isExpanded) {
                target.maxLines = Int.MAX_VALUE
                target.ellipsize = null
                
                val formattedText = NoticeTextFormatter.format(context, fullText)
                val highlightedText = highlightedPreviewText(context, formattedText)
                val suffix = "  see less"
                val spannable = SpannableStringBuilder(highlightedText)
                android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                spannable.append(suffix)
                
                val start = spannable.length - suffix.length
                spannable.setSpan(
                    ClickableActionSpan(linkColor) { binding.cardRoot.performClick() },
                    start,
                    spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                target.text = spannable
                target.movementMethod = LinkMovementMethodWithBubble.getInstance()
            } else {
                if (isLong) {
                    target.maxLines = Int.MAX_VALUE
                    target.ellipsize = null
                    
                    val hasLineOverflow = hasPreviewLineOverflow(fullText)
                    val visibleEnd = if (hasLineOverflow) {
                        previewEndForExplicitLines(fullText).coerceAtMost(240)
                    } else {
                        cleanPreviewEnd(fullText, 240)
                    }
                    
                    val truncatedText = fullText.substring(0, visibleEnd).trimEnd()
                    val formattedText = NoticeTextFormatter.format(context, truncatedText)
                    val highlightedText = highlightedPreviewText(context, formattedText)
                    
                    val suffix = "... see more"
                    val spannable = SpannableStringBuilder(highlightedText)
                    android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                    spannable.append(suffix)
                    
                    val start = spannable.length - suffix.length
                    spannable.setSpan(
                        ClickableActionSpan(linkColor) { binding.cardRoot.performClick() },
                        start,
                        spannable.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    
                    target.text = spannable
                    target.movementMethod = LinkMovementMethodWithBubble.getInstance()
                } else {
                    target.maxLines = Int.MAX_VALUE
                    target.ellipsize = null
                    
                    val formattedText = NoticeTextFormatter.format(context, fullText)
                    val highlightedText = highlightedPreviewText(context, formattedText)
                    val spannable = SpannableStringBuilder(highlightedText)
                    android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                    
                    target.text = spannable
                    target.movementMethod = LinkMovementMethodWithBubble.getInstance()
                }
            }
        }

        private fun highlightedPreviewText(context: Context, text: CharSequence): CharSequence {
            return if (searchQuery.isNotBlank() && text.toString().contains(searchQuery, ignoreCase = true)) {
                highlightSearchText(text, searchQuery, ThemeColors.primarySoft(context))
            } else {
                text
            }
        }

        private fun bindCardAiSummary(notice: Notice, fullText: String) {
            binding.btnCardAiSummary.isVisible = true

            val summary = aiSummaryCache[notice.id]
            val isLoading = notice.id in aiSummaryLoadingIds
            val isVisible = notice.id in aiSummaryVisibleIds && !summary.isNullOrBlank()

            binding.aiSummaryContainer.isVisible = isVisible
            binding.tvCardAiSummary.isVisible = isVisible

            val formatted = summary
                ?.takeIf { it.isNotBlank() }
                ?.let { NoticeTextFormatter.format(itemView.context, it) }
            if (formatted != null) {
                val spannable = SpannableStringBuilder(formatted)
                android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                binding.tvCardAiSummary.text = spannable
                binding.tvCardAiSummary.movementMethod = LinkMovementMethodWithBubble.getInstance()
            } else {
                binding.tvCardAiSummary.text = ""
                binding.tvCardAiSummary.movementMethod = null
            }

            binding.pbCardAiSummary.isVisible = isLoading
            binding.btnCardAiSummary.isEnabled = !isLoading

            val type = notice.displayType
            val accent = NoticeUi.accent(itemView.context, type)
            if (isLoading) {
                binding.pbCardAiSummary.indeterminateTintList = ColorStateList.valueOf(accent)
            }

            val rawText = when {
                isLoading -> "✨ ..."
                isVisible -> "✨ Hide"
                else -> "✨ AI"
            }
            val ssb = SpannableStringBuilder(rawText)
            val spaceIndex = rawText.indexOf(' ')
            if (spaceIndex != -1) {
                ssb.setSpan(
                    ForegroundColorSpan(accent),
                    spaceIndex + 1,
                    rawText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                ssb.setSpan(
                    ForegroundColorSpan(accent),
                    0,
                    rawText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.tvCardAiSummaryAction.text = ssb
            binding.tvCardAiSummaryAction.setTextColor(ThemeColors.textMuted(itemView.context))

            val badgeBg = NoticeUi.badgeBackground(itemView.context, type)
            if (isVisible) {
                binding.btnCardAiSummary.background = pill(
                    ColorUtils.setAlphaComponent(accent, 45),
                    accent,
                    13
                )
            } else {
                binding.btnCardAiSummary.background = pill(
                    badgeBg,
                    ColorUtils.setAlphaComponent(accent, 76),
                    13
                )
            }

            binding.btnCardAiSummary.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(0.95f)
                when {
                    isLoading -> return@setOnClickListener
                    summary != null && isVisible -> {
                        aiSummaryVisibleIds.remove(notice.id)
                        notifyCurrentNotice(notice.id)
                    }
                    summary != null -> {
                        aiSummaryVisibleIds.add(notice.id)
                        notifyCurrentNotice(notice.id)
                    }
                    else -> generateCardSummary(notice)
                }
            }
        }

        private fun generateCardSummary(notice: Notice) {
            aiSummaryLoadingIds.add(notice.id)
            aiSummaryVisibleIds.add(notice.id)
            notifyCurrentNotice(notice.id)
            adapterScope.launch {
                val result = AIService.summarizeNotice(
                    notice.title, notice.content, notice.displayType, notice.subject.ifBlank { null }
                )
                val summary = result.getOrNull()
                aiSummaryLoadingIds.remove(notice.id)
                if (summary.isNullOrBlank()) {
                    aiSummaryVisibleIds.remove(notice.id)
                    Toast.makeText(itemView.context, "AI summary unavailable. Try again.", Toast.LENGTH_SHORT).show()
                } else {
                    aiSummaryCache[notice.id] = summary
                    aiSummaryVisibleIds.add(notice.id)
                }
                notifyCurrentNotice(notice.id)
            }
        }

        private fun toggleExpansion(notice: Notice) {
            val text = normalizedNoticeText(notice)
            if (!isLongNotice(text)) return

            HapticHelper.lightPop(itemView)
            if (notice.id in expandedNoticeIds) {
                expandedNoticeIds.remove(notice.id)
            } else {
                expandedNoticeIds.add(notice.id)
            }
            val recyclerView = itemView.parent as? RecyclerView
            if (recyclerView != null) {
                TransitionManager.beginDelayedTransition(recyclerView)
            } else {
                TransitionManager.beginDelayedTransition(binding.cardRoot)
            }
            notifyItemChanged(bindingAdapterPosition)
        }
    }

    private fun notifyCurrentNotice(noticeId: String) {
        val index = currentList.indexOfFirst { it is Notice && it.id == noticeId }
        if (index >= 0) notifyItemChanged(index)
    }

    inner class PollViewHolder(private val binding: ItemPollBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(poll: Poll) {
            val hasVoted = poll.hasVoted(currentUserId)
            val selectedOptions = poll.selectedOptionsFor(currentUserId)

            // Poll Question with Search Highlighting
            if (searchQuery.isNotBlank() && poll.question.contains(searchQuery, ignoreCase = true)) {
                binding.tvQuestion.text = highlightSearchText(poll.question, searchQuery, ThemeColors.primarySoft(itemView.context))
            } else {
                binding.tvQuestion.text = poll.question
            }

            binding.tvTotalVotes.text = if (poll.totalVoters == 1) "1 voter" else "${poll.totalVoters} voters"
            binding.tvPollMode.text = if (poll.allowMultipleAnswers) {
                if (hasVoted) "Tap options to update your choices" else "Select one or more options"
            } else {
                if (hasVoted) "Tap another option to change your vote" else "Select one option"
            }
            val expired = poll.expiresAt?.toDate()?.before(Date()) ?: false
            binding.tvStatus.text = if (expired) "Expired" else "Active"
            val statusColor = if (expired) ThemeColors.error(itemView.context) else ThemeColors.success(itemView.context)
            binding.tvStatus.setTextColor(statusColor)
            binding.tvStatus.background = countdownChip(statusColor)
            val winner = poll.getWinner()
            binding.tvVerdict.isVisible = winner != null && (hasVoted || expired)
            binding.tvVerdict.text = winner?.let { "Winner: $it" }.orEmpty()
            binding.btnViewVoters.isVisible = true
            binding.btnViewVoters.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.05f)
                showVotersDialog(itemView.context, poll)
            }

            val childCount = binding.optionsContainer.childCount
            val requiredCount = poll.options.size
            if (childCount > requiredCount) {
                binding.optionsContainer.removeViews(requiredCount, childCount - requiredCount)
            }
            poll.options.forEachIndexed { idx, option ->
                val optionBinding = if (idx < childCount) {
                    ItemPollOptionBinding.bind(binding.optionsContainer.getChildAt(idx))
                } else {
                    val ob = ItemPollOptionBinding.inflate(LayoutInflater.from(itemView.context), binding.optionsContainer, false)
                    binding.optionsContainer.addView(ob.root)
                    ob
                }
                val percentage = poll.percentageFor(option)
                val voteCount = poll.votesFor(option)
                val selected = selectedOptions.contains(option)
                val showResults = hasVoted || expired

                optionBinding.tvOptionText.text = option
                optionBinding.tvOptionPercent.text = "$percentage%"
                optionBinding.tvOptionVotes.text = if (voteCount == 1) "1 vote" else "$voteCount votes"

                optionBinding.optionProgress.isVisible = showResults
                optionBinding.tvOptionPercent.isVisible = showResults
                optionBinding.tvOptionVotes.isVisible = showResults
                optionBinding.ivCheck.isVisible = selected

                if (showResults) {
                    optionBinding.optionProgress.progress = percentage
                } else {
                    optionBinding.optionProgress.progress = 0
                }

                optionBinding.tvOptionText.setTextColor(
                    if (selected) ThemeColors.primary(itemView.context)
                    else ThemeColors.textPrimary(itemView.context)
                )
                
                optionBinding.optionRoot.setBackgroundResource(
                    if (selected) R.drawable.bg_poll_option_selected else R.drawable.bg_poll_option
                )

                optionBinding.optionRoot.alpha = if (showResults && !selected) 0.82f else 1f
                optionBinding.optionRoot.isEnabled = !expired

                if (!expired) {
                    optionBinding.optionRoot.setOnClickListener {
                        HapticHelper.heavyClick(it)
                        it.animateSpringScale(0.98f)
                        if (poll.allowMultipleAnswers) onPollMultiVote(poll, option) else onPollVote(poll.id, option)
                    }
                } else {
                    optionBinding.optionRoot.setOnClickListener(null)
                }
            }

            binding.btnDelete.isVisible = isAdminProvider()
            binding.btnDelete.setOnClickListener {
                HapticHelper.heavyClick(it)
                it.animateSpringScale(1.1f)
                AlertDialog.Builder(itemView.context, R.style.Theme_ClassMate_Dialog)
                    .setTitle("Delete Poll?")
                    .setMessage("This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> onPollDelete(poll.id) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            binding.btnEdit.isVisible = isAdminProvider()
            binding.btnEdit.setOnClickListener {
                HapticHelper.mediumThud(it)
                it.animateSpringScale(1.1f)
                itemView.context.startActivity(
                    Intent(itemView.context, CreatePollActivity::class.java).apply {
                        putExtra("POLL_ID", poll.id)
                        putExtra("QUESTION", poll.question)
                        putStringArrayListExtra("OPTIONS", ArrayList(poll.options))
                        putExtra("ALLOW_MULTIPLE", poll.allowMultipleAnswers)
                    }
                )
            }
        }

        private fun countdownChip(base: Int): GradientDrawable {
            val fill = ColorUtils.setAlphaComponent(base, 0x24)
            return GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(base, 0x92))
            }
        }
    }

    private fun showVotersDialog(context: Context, poll: Poll) {
        val voterIds = poll.votes.keys.toList()
        if (voterIds.isEmpty()) {
            AlertDialog.Builder(context, R.style.Theme_ClassMate_Dialog)
                .setTitle("Voters")
                .setMessage("No votes yet.")
                .setPositiveButton("Close", null)
                .show()
            return
        }
        val loading = AlertDialog.Builder(context, R.style.Theme_ClassMate_Dialog)
            .setTitle("Voters")
            .setMessage("Loading names...")
            .setCancelable(false)
            .show()
        val db = FirebaseFirestore.getInstance()
        val tasks = voterIds.chunked(30).map { chunk ->
            db.collection("users").whereIn(FieldPath.documentId(), chunk).get()
        }
        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener { results ->
                loading.dismiss()
                val names = mutableMapOf<String, String>()
                results.forEach { task ->
                    if (task.isSuccessful) {
                        val snapshot = task.result as QuerySnapshot
                        snapshot.documents.forEach { names[it.id] = it.getString("name") ?: "Unknown User" }
                    }
                }
                val body = voterIds.joinToString("\n") { uid ->
                    val selected = poll.selectedOptionsFor(uid).joinToString(", ")
                    "${names[uid] ?: "Unknown User"} - $selected"
                }
                AlertDialog.Builder(context, R.style.Theme_ClassMate_Dialog)
                    .setTitle("Voters")
                    .setMessage(body)
                    .setPositiveButton("Close", null)
                    .show()
            }
            .addOnFailureListener {
                loading.dismiss()
                Toast.makeText(context, "Could not load voters", Toast.LENGTH_SHORT).show()
            }
    }

    private fun pill(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }

    private fun themedNoticeCardFill(context: Context, accent: Int): Int {
        val base = ThemeColors.card(context)
        if (!ThemeColors.isDark(context)) {
            return ColorUtils.blendARGB(base, accent, 0.025f)
        }
        return ColorUtils.blendARGB(base, accent, 0.055f)
    }

    private fun themedActionMuted(context: Context, cardFill: Int): Int {
        val baseText = ThemeColors.textSecondary(context)
        if (!ThemeColors.isDark(context)) return baseText
        return ColorUtils.blendARGB(baseText, cardFill, 0.18f)
    }

    private fun compactCount(count: Int): String {
        return when {
            count <= 0 -> "0"
            count < 1_000 -> count.toString()
            count < 1_000_000 -> "${count / 1_000}k"
            else -> "${count / 1_000_000}m"
        }
    }

    private fun normalizedNoticeText(notice: Notice): String {
        return notice.content.ifBlank { notice.topic }.trim(' ', '\t', '\r')
    }

    private fun hasPreviewLineOverflow(text: String): Boolean {
        return text.count { it == '\n' } + 1 > NOTICE_CARD_PREVIEW_MAX_LINES
    }

    private fun previewEndForExplicitLines(text: String): Int {
        var lineCount = 1
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                if (lineCount >= NOTICE_CARD_PREVIEW_MAX_LINES) return index
                lineCount += 1
            }
        }
        return text.length
    }

    private fun cleanPreviewEnd(text: String, maxLength: Int): Int {
        if (text.length <= maxLength) return text.trimEnd().length
        var safeEnd = maxLength.coerceAtMost(text.length)
        if (safeEnd < text.length && safeEnd > 0 && Character.isLowSurrogate(text[safeEnd])) {
            safeEnd -= 1
        }
        val raw = text.take(safeEnd)
        val lastSpace = raw.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        val end = if (lastSpace >= maxLength * 0.72f) lastSpace else raw.length
        return raw.take(end).trimEnd().length
    }

    private fun stableId(key: String): Long {
        return key.hashCode().toLong()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return if (unit == 0) "${bytes}B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toPdfFile(): PdfFile {
        return PdfFile(
            id = id,
            title = getString("title") ?: "No Title",
            subject = getString("subject") ?: "",
            description = getString("description") ?: "",
            uploadedBy = getString("uploadedByName") ?: getString("uploadedBy") ?: "",
            telegramUrl = getString("telegramUrl") ?: "",
            driveUrl = getString("driveUrl") ?: "",
            fileId = getString("fileId") ?: "",
            timestamp = getTimestamp("timestamp") ?: getTimestamp("createdAt"),
            courseCode = getString("courseCode") ?: "",
            courseType = getString("courseType") ?: "",
            fileType = getString("fileType") ?: "other",
            mimeType = getString("mimeType") ?: "application/octet-stream",
            sizeBytes = getLong("sizeBytes") ?: 0L,
            provider = getString("provider") ?: "",
            downloadUrl = getString("downloadUrl") ?: "",
            githubAssetId = getLong("githubAssetId") ?: 0L,
            githubAssetName = getString("githubAssetName") ?: getString("title") ?: "",
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt"),
            downloadCount = getLong("downloadCount") ?: 0L,
            isDeleted = getBoolean("isDeleted") ?: false
        )
    }

    companion object {
        private const val TYPE_NOTICE = 0
        private const val TYPE_POLL = 1
        private const val TYPE_HEADER = 2
        private const val PAYLOAD_LIKE = "payload_like"
        private const val PAYLOAD_SEARCH = "payload_search"
        private const val PAYLOAD_HIGHLIGHT = "payload_highlight"
        private const val PAYLOAD_PIN = "payload_pin"
        private const val NOTICE_CARD_PREVIEW_MAX_LINES = 6

        private val DiffCallback = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is NoticeGroupHeader && newItem is NoticeGroupHeader -> oldItem.key == newItem.key
                    oldItem is Notice && newItem is Notice -> oldItem.id == newItem.id
                    oldItem is Poll && newItem is Poll -> oldItem.id == newItem.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
                return when {
                    oldItem is NoticeGroupHeader && newItem is NoticeGroupHeader -> oldItem == newItem
                    oldItem is Notice && newItem is Notice -> oldItem == newItem
                    oldItem is Poll && newItem is Poll -> oldItem == newItem
                    else -> false
                }
            }

            override fun getChangePayload(oldItem: Any, newItem: Any): Any? {
                if (oldItem is Notice && newItem is Notice) {
                    if (oldItem.isPinned != newItem.isPinned &&
                        oldItem.attachments == newItem.attachments &&
                        oldItem.title == newItem.title &&
                        oldItem.body == newItem.body &&
                        oldItem.subject == newItem.subject &&
                        oldItem.type == newItem.type &&
                        oldItem.priority == newItem.priority
                    ) {
                        return PAYLOAD_PIN
                    }
                }
                return super.getChangePayload(oldItem, newItem)
            }
        }
    }
}

private fun highlightSearchText(text: CharSequence, query: String, color: Int): SpannableStringBuilder {
    val spannable = SpannableStringBuilder(text)
    if (query.isBlank()) return spannable
    val renderedText = spannable.toString()
    var start = renderedText.indexOf(query, ignoreCase = true)
    while (start >= 0) {
        val end = start + query.length
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(Color.parseColor("#FFEB3B")),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(Color.BLACK),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        start = renderedText.indexOf(query, start + 1, ignoreCase = true)
    }
    return spannable
}

private fun View.animateSpringScale(targetScale: Float) {
    val springX = SpringAnimation(this, SpringAnimation.SCALE_X, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
    }
    val springY = SpringAnimation(this, SpringAnimation.SCALE_Y, 1f).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
    }

    this.scaleX = targetScale
    this.scaleY = targetScale
    springX.start()
    springY.start()
}

private class ClickableActionSpan(
    private val color: Int,
    private val onClickAction: () -> Unit
) : android.text.style.ClickableSpan() {
    override fun onClick(widget: View) {
        onClickAction()
    }

    override fun updateDrawState(ds: android.text.TextPaint) {
        super.updateDrawState(ds)
        ds.color = color
        ds.isUnderlineText = false
        ds.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}

private class LinkMovementMethodWithBubble private constructor() : android.text.method.LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: android.view.MotionEvent): Boolean {
        val action = event.action
        if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_DOWN) {
            var x = event.x.toInt()
            var y = event.y.toInt()

            x -= widget.totalPaddingLeft
            y -= widget.totalPaddingTop

            x += widget.scrollX
            y += widget.scrollY

            val layout = widget.layout
            if (layout != null) {
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val links = buffer.getSpans(off, off, android.text.style.ClickableSpan::class.java)

                if (links.isNotEmpty()) {
                    val link = links[0]
                    val linkStart = buffer.getSpanStart(link)
                    val linkEnd = buffer.getSpanEnd(link)
                    
                    // Precise boundary check: verify if the offset off falls inside the link boundaries
                    if (off >= linkStart && off <= linkEnd) {
                        val lineStart = layout.getLineStart(line)
                        val lineEnd = layout.getLineEnd(line)
                        
                        // Clamp link bounds to the current line boundaries
                        val clampStart = Math.max(linkStart, lineStart)
                        val clampEnd = Math.min(linkEnd, lineEnd)
                        
                        val xStart = layout.getPrimaryHorizontal(clampStart)
                        val xEnd = layout.getPrimaryHorizontal(clampEnd)
                        
                        val minX = Math.min(xStart, xEnd)
                        val maxX = Math.max(xStart, xEnd)
                        
                        // Verify that the horizontal touch coordinate is actually within character boundaries
                        if (x.toFloat() >= minX && x.toFloat() <= maxX) {
                            if (action == android.view.MotionEvent.ACTION_UP) {
                                link.onClick(widget)
                            } else if (action == android.view.MotionEvent.ACTION_DOWN) {
                                android.text.Selection.setSelection(buffer, linkStart, linkEnd)
                            }
                            return true
                        }
                    }
                }
                android.text.Selection.removeSelection(buffer)
            }
        }
        return false
    }

    companion object {
        private var instance: LinkMovementMethodWithBubble? = null
        fun getInstance(): LinkMovementMethodWithBubble {
            if (instance == null) {
                instance = LinkMovementMethodWithBubble()
            }
            return instance!!
        }
    }
}
