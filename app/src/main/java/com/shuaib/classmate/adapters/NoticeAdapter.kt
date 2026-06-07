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
import com.shuaib.classmate.databinding.ItemNoticeGroupHeaderBinding
import com.shuaib.classmate.databinding.ItemNoticeModernBinding
import com.shuaib.classmate.databinding.ItemPollBinding
import com.shuaib.classmate.databinding.ItemPollOptionBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.models.Poll
import com.shuaib.classmate.notices.NoticeEngagement
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.notices.NoticeUi
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
    private val onPollVote: (pollId: String, option: String) -> Unit = { _, _ -> },
    private val onPollMultiVote: (poll: Poll, option: String) -> Unit = { _, _ -> },
    private val onPollDelete: (pollId: String) -> Unit = { _ -> },
    private val onGroupToggle: (NoticeGroupHeader) -> Unit = {}
) : ListAdapter<Any, RecyclerView.ViewHolder>(DiffCallback) {

    private var engagementByNoticeId: Map<String, NoticeEngagement> = emptyMap()
    private var searchQuery: String = ""
    private var highlightedNoticeId: String? = null
    private val expandedNoticeIds = mutableSetOf<String>()
    private val animatedNoticeIds = mutableSetOf<String>()
    private val aiSummaryCache = mutableMapOf<String, String>()
    private val aiSummaryVisibleIds = mutableSetOf<String>()
    private val aiSummaryLoadingIds = mutableSetOf<String>()
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
            val shouldShowSubject = NoticeUi.shouldShowHeaderSubject(notice)
            binding.tvSubject.text = NoticeUi.headerSubject(notice)
            binding.tvSubject.isVisible = shouldShowSubject
            binding.tvBullet.isVisible = shouldShowSubject
            binding.tvBullet.setTextColor(ColorUtils.setAlphaComponent(accent, 140))

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

            binding.ivPreview.isVisible = !imageUrl.isNullOrBlank()
            if (!imageUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_notice_modern_card)
                    .centerCrop()
                    .into(binding.ivPreview)
            }

            val noticeText = normalizedNoticeText(notice)
            bindPreview(binding.tvPreview, notice, noticeText, accent)
            bindCardAiSummary(notice, noticeText)
            binding.tvMeta.text = "${NoticeUi.formatDate(notice.createdAt)} - ${NoticeUi.formatTime(notice.createdAt)}"

            val priorityLabel = NoticeUi.priorityLabel(notice)
            binding.tvCountdown.isVisible = notice.deadlineAt != null
            binding.tvCountdown.text = priorityLabel
            val countdownBase = countdownColor(priorityLabel, notice.displayPriority)
            binding.tvCountdown.background = countdownChip(countdownBase)
            binding.tvCountdown.setTextColor(ColorUtils.blendARGB(Color.WHITE, countdownBase, 0.22f))

            val actionMuted = themedActionMuted(itemView.context, cardFill)
            bindEngagement(notice, actionMuted)

            bindPinState(notice, cardFill)

            binding.cardRoot.setOnClickListener {
                HapticHelper.mediumThud(it)
                it.animateSpringScale(0.97f)
                toggleExpansion(notice)
            }
            binding.cardRoot.setOnLongClickListener {
                if (isAdminProvider()) {
                    HapticHelper.heavyClick(it)
                    onNoticeLongClick(notice)
                }
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
            val likeColor = if (engagement.isLiked) ThemeColors.error(itemView.context) else actionMuted
            binding.ivLikeIcon.setImageResource(if (engagement.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            binding.ivLikeIcon.setColorFilter(likeColor)
            binding.tvLikeCount.text = compactCount(engagement.likeCount)
            binding.tvLikeCount.setTextColor(likeColor)
            binding.ivCommentIcon.setColorFilter(actionMuted)
            binding.tvCommentCount.text = compactCount(engagement.commentCount)
            binding.tvCommentCount.setTextColor(actionMuted)
            binding.ivReminderIcon.setColorFilter(actionMuted)
            binding.ivShareIcon.setColorFilter(actionMuted)
            binding.tvShareCount.text = compactCount(engagement.shareCount)
            binding.tvShareCount.setTextColor(actionMuted)
            binding.tvShareCount.isVisible = engagement.shareCount > 0
        }

        private fun bindPinState(notice: Notice, cardFill: Int) {
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

        private fun bindPreview(target: TextView, notice: Notice, fullText: String, linkColor: Int) {
            val isExpanded = notice.id in expandedNoticeIds
            target.tag = notice.id
            target.highlightColor = Color.TRANSPARENT
            target.setTextIsSelectable(false)
            target.isVerticalScrollBarEnabled = false
            target.setOnClickListener(null)

            if (fullText.isBlank()) {
                target.movementMethod = null
                target.maxLines = Int.MAX_VALUE
                target.ellipsize = null
                target.text = "No description provided."
                return
            }

            val context = itemView.context
            if (isExpanded) {
                target.maxLines = Int.MAX_VALUE
                target.ellipsize = null
                val formattedText = highlightedPreviewText(context, fullText)
                val spannableContent = SpannableStringBuilder(formattedText)
                val suffix = "\n\nSee less"
                spannableContent.append(suffix)
                val start = spannableContent.length - suffix.length
                styleInlineAction(spannableContent, start, spannableContent.length, linkColor)
                target.text = spannableContent
                target.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                target.setOnClickListener { toggleExpansion(notice) }
            } else {
                target.movementMethod = null
                target.maxLines = NOTICE_CARD_PREVIEW_MAX_LINES
                target.ellipsize = android.text.TextUtils.TruncateAt.END
                val formattedFullText = highlightedPreviewText(context, fullText)
                target.text = formattedFullText

                target.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        target.viewTreeObserver.removeOnPreDrawListener(this)
                        if (target.tag == notice.id) {
                            val layout = target.layout
                            if (layout != null) {
                                val displayedText = target.text
                                val displayedLength = displayedText.length
                                val lastVisibleLine = (NOTICE_CARD_PREVIEW_MAX_LINES - 1).coerceAtMost(layout.lineCount - 1)
                                val lineEnd = if (lastVisibleLine >= 0) layout.getLineEnd(lastVisibleLine) else displayedLength
                                val isLayoutTruncated = lastVisibleLine >= 0 &&
                                    layout.lineCount >= NOTICE_CARD_PREVIEW_MAX_LINES &&
                                    (layout.getEllipsisCount(lastVisibleLine) > 0 || lineEnd < displayedLength)
                                val hasExplicitLineOverflow = hasPreviewLineOverflow(fullText)
                                val visibleEnd = if (isLayoutTruncated) {
                                    lineEnd
                                } else {
                                    previewEndForExplicitLines(displayedText.toString())
                                }

                                if (isLayoutTruncated || hasExplicitLineOverflow) {
                                    bindTruncatedPreview(target, notice, displayedText, linkColor, visibleEnd)
                                } else {
                                    // Not truncated, clear click listener so it falls through to cardRoot
                                    target.setOnClickListener(null)
                                }
                            }
                        }
                        return true
                    }
                })
            }
        }

        private fun bindTruncatedPreview(
            target: TextView,
            notice: Notice,
            displayedText: CharSequence,
            linkColor: Int,
            visibleEnd: Int
        ) {
            val suffix = "... see more"
            val renderedText = displayedText.toString()
            val previewLimit = (visibleEnd - suffix.length)
                .coerceAtLeast(0)
                .coerceAtMost(renderedText.length)
            val previewEnd = cleanPreviewEnd(renderedText, previewLimit)
            val preview = displayedText.subSequence(0, previewEnd)

            target.maxLines = NOTICE_CARD_PREVIEW_MAX_LINES
            target.ellipsize = null
            val spannable = SpannableStringBuilder(preview)
            spannable.append(suffix)

            val start = spannable.length - suffix.length
            styleInlineAction(spannable, start, spannable.length, linkColor)
            target.text = spannable
            target.setOnClickListener { toggleExpansion(notice) }
        }

        private fun highlightedPreviewText(context: Context, text: String): CharSequence {
            return if (searchQuery.isNotBlank() && text.contains(searchQuery, ignoreCase = true)) {
                highlightSearchText(text, searchQuery, ThemeColors.primarySoft(context))
            } else {
                text
            }
        }

        private fun styleInlineAction(text: SpannableStringBuilder, start: Int, end: Int, color: Int) {
            text.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun bindCardAiSummary(notice: Notice, fullText: String) {
            binding.btnCardAiSummary.isVisible = true

            val summary = aiSummaryCache[notice.id]
            val isLoading = notice.id in aiSummaryLoadingIds
            val isVisible = notice.id in aiSummaryVisibleIds && !summary.isNullOrBlank()

            binding.aiSummaryContainer.isVisible = isVisible
            binding.tvCardAiSummary.isVisible = isVisible
            binding.tvCardAiSummary.text = summary
                ?.takeIf { it.isNotBlank() }
                ?.let { NoticeTextFormatter.format(itemView.context, it) }
                ?: ""

            binding.pbCardAiSummary.isVisible = isLoading
            binding.btnCardAiSummary.isEnabled = !isLoading

            val type = notice.displayType
            val accent = NoticeUi.accent(itemView.context, type)
            if (isLoading) {
                binding.pbCardAiSummary.indeterminateTintList = ColorStateList.valueOf(accent)
            }

            binding.tvCardAiSummaryAction.text = when {
                isLoading -> "Summarizing..."
                isVisible -> "✨ Hide Summary"
                else -> "✨ Show AI Summary"
            }

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
            binding.tvCardAiSummaryAction.setTextColor(accent)

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
                val summary = AIService.summarizeNotice(
                    notice.title, notice.content, notice.displayType, notice.subject.ifBlank { null }
                )
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

        private fun isLongNoticeForCard(text: String): Boolean {
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            val explicitLines = text.count { it == '\n' } + 1
            return words > 45 || text.length > 260 || explicitLines > NOTICE_CARD_PREVIEW_MAX_LINES
        }

        private fun toggleExpansion(notice: Notice) {
            HapticHelper.lightPop(itemView)
            if (notice.id in expandedNoticeIds) {
                expandedNoticeIds.remove(notice.id)
            } else {
                expandedNoticeIds.add(notice.id)
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
            binding.tvStatus.text = if (expired) "Poll Expired" else "Poll Active"
            binding.tvStatus.setTextColor(if (expired) ThemeColors.error(itemView.context) else ThemeColors.success(itemView.context))
            val winner = poll.getWinner()
            binding.tvVerdict.isVisible = winner != null && (hasVoted || expired)
            binding.tvVerdict.text = winner?.let { "Winner: $it" }.orEmpty()
            binding.btnViewVoters.isVisible = true
            binding.btnViewVoters.setOnClickListener {
                HapticHelper.lightPop(it)
                it.animateSpringScale(1.05f)
                showVotersDialog(itemView.context, poll)
            }

            binding.optionsContainer.removeAllViews()
            poll.options.forEach { option ->
                val optionBinding = ItemPollOptionBinding.inflate(LayoutInflater.from(itemView.context), binding.optionsContainer, false)
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
                    // Animated Progress Bar
                    android.animation.ValueAnimator.ofInt(0, percentage).apply {
                        duration = 750
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { animator ->
                            optionBinding.optionProgress.progress = animator.animatedValue as Int
                        }
                        start()
                    }
                } else {
                    optionBinding.optionProgress.progress = 0
                }

                optionBinding.tvOptionText.setTextColor(
                    if (selected) ThemeColors.primarySoft(itemView.context)
                    else ThemeColors.textPrimary(itemView.context)
                )

                optionBinding.optionRoot.alpha = if (showResults && !selected) 0.85f else 1f
                optionBinding.optionRoot.isEnabled = !expired

                if (!expired) {
                    optionBinding.optionRoot.setOnClickListener {
                        HapticHelper.heavyClick(it)
                        it.animateSpringScale(0.98f)
                        if (poll.allowMultipleAnswers) onPollMultiVote(poll, option) else onPollVote(poll.id, option)
                    }
                }
                binding.optionsContainer.addView(optionBinding.root)
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

    companion object {
        private const val TYPE_NOTICE = 0
        private const val TYPE_POLL = 1
        private const val TYPE_HEADER = 2
        private const val PAYLOAD_LIKE = "payload_like"
        private const val PAYLOAD_SEARCH = "payload_search"
        private const val PAYLOAD_HIGHLIGHT = "payload_highlight"
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
            ForegroundColorSpan(color),
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
