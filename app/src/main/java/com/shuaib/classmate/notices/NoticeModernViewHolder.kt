package com.shuaib.classmate.notices

import android.content.res.ColorStateList
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.NoticeImageCarouselAdapter

interface NoticeCardCallbacks {
    fun onCardClick(model: NoticePresentationModel)
    fun onOptionsClick(model: NoticePresentationModel, anchor: View)
    fun onOpenAssessment(assessmentId: String)
    fun onOpenFile(attachment: NoticeAttachmentItem)
    fun onOpenProtectedResource(resourceId: String)
    fun onOpenImages(imageUrls: List<String>, initialIndex: Int)
    fun onTranslateClick(model: NoticePresentationModel)
    fun onLikeClick(model: NoticePresentationModel)
    fun onCommentClick(model: NoticePresentationModel)
    fun onShareClick(model: NoticePresentationModel)
    fun onReadReceiptsClick(model: NoticePresentationModel)
    fun onPollVoteClick(noticeId: String, pollId: String, optionId: String)
    fun onPollViewVotesClick(noticeId: String, pollId: String)
}

class NoticeModernViewHolder(
    itemView: View,
    private val callbacks: NoticeCardCallbacks
) : RecyclerView.ViewHolder(itemView) {

    private var currentModel: NoticePresentationModel? = null

    // View References Cached Once - Zero findViewById on bind!
    val cardRoot: MaterialCardView = itemView.findViewById(R.id.cardRoot)
    val noticeCardSurface: View = itemView.findViewById(R.id.noticeCardSurface)
    val noticeAccent: View = itemView.findViewById(R.id.noticeAccent)
    val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
    val ivNoticeIllustration: ImageView = itemView.findViewById(R.id.ivNoticeIllustration)
    val btnOptions: ImageView = itemView.findViewById(R.id.btnOptions)
    val tvPreview: TextView = itemView.findViewById(R.id.tvPreview)

    // Poll Container
    val pollContainer: LinearLayout = itemView.findViewById(R.id.pollContainer)
    val tvPollChip: TextView = itemView.findViewById(R.id.tvPollChip)
    val tvPollSubtext: TextView = itemView.findViewById(R.id.tvPollSubtext)
    val pollOptionsList: LinearLayout = itemView.findViewById(R.id.pollOptionsList)
    val tvPollTotalVotes: TextView = itemView.findViewById(R.id.tvPollTotalVotes)
    val tvPollViewVotes: TextView = itemView.findViewById(R.id.tvPollViewVotes)

    // Image Previews
    val imagePreviewContainer: FrameLayout = itemView.findViewById(R.id.imagePreviewContainer)
    val ivPreview: ShapeableImageView = itemView.findViewById(R.id.ivPreview)
    val imageCarouselPager: ViewPager2 = itemView.findViewById(R.id.imageCarouselPager)
    val tvCarouselIndicator: TextView = itemView.findViewById(R.id.tvCarouselIndicator)
    val tvTapToView: TextView = itemView.findViewById(R.id.tvTapToView)

    // Assessment Attachment
    val assessmentAttachmentContainer: MaterialCardView = itemView.findViewById(R.id.assessmentAttachmentContainer)
    val tvAssessmentBadge: TextView = itemView.findViewById(R.id.tvAssessmentBadge)
    val tvAssessmentName: TextView = itemView.findViewById(R.id.tvAssessmentName)
    val tvAssessmentSubtext: TextView = itemView.findViewById(R.id.tvAssessmentSubtext)
    val btnOpenAssessment: ImageView = itemView.findViewById(R.id.btnOpenAssessment)

    // File Attachment
    val fileAttachmentContainer: MaterialCardView = itemView.findViewById(R.id.fileAttachmentContainer)
    val tvFileExtension: TextView = itemView.findViewById(R.id.tvFileExtension)
    val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
    val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
    val btnOpenFile: ImageView = itemView.findViewById(R.id.btnOpenFile)

    // Metadata
    val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
    val btnTranslate: TextView = itemView.findViewById(R.id.btnTranslate)
    val tvPinnedBadge: TextView = itemView.findViewById(R.id.tvPinnedBadge)

    // Actions & Social
    val btnLike: LinearLayout = itemView.findViewById(R.id.btnLike)
    val ivLikeIcon: ImageView = itemView.findViewById(R.id.ivLikeIcon)
    val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)
    val btnComment: LinearLayout = itemView.findViewById(R.id.btnComment)
    val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)
    val btnShare: FrameLayout = itemView.findViewById(R.id.btnShare)
    val btnReadReceipts: LinearLayout = itemView.findViewById(R.id.btnReadReceipts)
    val seenAvatars: FrameLayout = itemView.findViewById(R.id.seenAvatars)
    val tvReadCount: TextView = itemView.findViewById(R.id.tvReadCount)

    init {
        tvPreview.movementMethod = LinkMovementMethod.getInstance()
    }

    fun bind(model: NoticePresentationModel) {
        val context = itemView.context

        // Background styling & illustrations
        val (bgRes, iconRes, accentColor) = when {
            model.isPoll -> Triple(R.drawable.bg_notice_premium_general, R.drawable.ic_notice_poll_art, R.color.cm_primary)
            model.isResource -> Triple(R.drawable.bg_notice_premium_resource, R.drawable.ic_notice_resource_art, R.color.cm_notice_premium_resource)
            model.isCancellation -> Triple(R.drawable.bg_notice_premium_cancel, R.drawable.ic_notice_calendar_cancel_art, R.color.cm_notice_premium_cancel)
            else -> Triple(R.drawable.bg_notice_premium_general, R.drawable.ic_notice_megaphone_art, R.color.cm_notice_premium_general)
        }

        noticeCardSurface.setBackgroundResource(bgRes)
        noticeAccent.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, accentColor))

        // Visual indicator when targeted via shared deep link or push notification
        val density = itemView.resources.displayMetrics.density
        if (model.isHighlighted) {
            val primaryColor = ContextCompat.getColor(context, R.color.cm_primary)
            cardRoot.strokeColor = primaryColor
            cardRoot.strokeWidth = (2.5f * density).toInt()

            cardRoot.animate().cancel()
            cardRoot.scaleX = 0.98f
            cardRoot.scaleY = 0.98f
            cardRoot.animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setDuration(260)
                .withEndAction {
                    cardRoot.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .start()
                }
                .start()
        } else {
            cardRoot.animate().cancel()
            cardRoot.scaleX = 1.0f
            cardRoot.scaleY = 1.0f
            cardRoot.strokeColor = ContextCompat.getColor(context, R.color.cm_notice_premium_stroke)
            cardRoot.strokeWidth = (1f * density).toInt()
        }

        val titleContainer = tvTitle.parent as? android.view.ViewGroup
        val titleLp = titleContainer?.layoutParams as? android.view.ViewGroup.MarginLayoutParams

        if (model.isPoll) {
            ivNoticeIllustration.visibility = View.GONE
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            if (titleLp != null && titleLp.marginEnd != 0) {
                titleLp.marginEnd = 0
                titleContainer.layoutParams = titleLp
            }
        } else {
            ivNoticeIllustration.visibility = View.VISIBLE
            ivNoticeIllustration.setImageResource(iconRes)
            ivNoticeIllustration.imageTintList = null
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            val defaultMargin = (8 * density).toInt()
            if (titleLp != null && titleLp.marginEnd != defaultMargin) {
                titleLp.marginEnd = defaultMargin
                titleContainer.layoutParams = titleLp
            }
        }

        // Title and Body
        currentModel = model
        tvTitle.text = model.title
        val bodyText = model.cleanBody.toString()
        com.shuaib.classmate.activities.ClassMateNoticeText.bind(
            view = tvPreview,
            body = bodyText,
            query = "",
            isExpanded = model.isExpanded,
            onToggleExpand = { callbacks.onCardClick(model) }
        )

        // Expand/Collapse click
        noticeCardSurface.setOnClickListener {
            callbacks.onCardClick(model)
        }

        // Options Menu
        btnOptions.visibility = View.VISIBLE
        btnOptions.setOnClickListener {
            callbacks.onOptionsClick(model, btnOptions)
        }

        // Poll
        if (model.poll != null) {
            applyPoll(model.poll)
        } else {
            pollContainer.visibility = View.GONE
        }

        // Images Preview / Carousel
        val images = model.imageAttachments
        if (images.isNotEmpty()) {
            imagePreviewContainer.visibility = View.VISIBLE
            val imageUrls = images.map { it.url }

            if (imageUrls.size > 1) {
                ivPreview.visibility = View.GONE
                imageCarouselPager.visibility = View.VISIBLE
                tvCarouselIndicator.visibility = View.VISIBLE
                tvTapToView.visibility = View.VISIBLE
                tvCarouselIndicator.text = "1 / ${imageUrls.size}"

                imageCarouselPager.adapter = NoticeImageCarouselAdapter(imageUrls) { pos ->
                    callbacks.onOpenImages(imageUrls, pos)
                }
                imageCarouselPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        tvCarouselIndicator.text = "${position + 1} / ${imageUrls.size}"
                    }
                })
            } else {
                imageCarouselPager.visibility = View.GONE
                tvCarouselIndicator.visibility = View.GONE
                ivPreview.visibility = View.VISIBLE
                tvTapToView.visibility = View.VISIBLE

                Glide.with(itemView)
                    .load(imageUrls.first())
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(ivPreview)

                ivPreview.setOnClickListener {
                    callbacks.onOpenImages(imageUrls, 0)
                }
            }
        } else {
            imagePreviewContainer.visibility = View.GONE
        }

        // Assessment Link Container
        if (!model.assessmentId.isNullOrBlank()) {
            assessmentAttachmentContainer.visibility = View.VISIBLE
            tvAssessmentBadge.text = "TEST"
            tvAssessmentName.text = "Assessment attached"
            tvAssessmentSubtext.text = "Tap to view test details, syllabus & schedule"

            val openAssessment = View.OnClickListener {
                callbacks.onOpenAssessment(model.assessmentId)
            }
            assessmentAttachmentContainer.setOnClickListener(openAssessment)
            btnOpenAssessment.setOnClickListener(openAssessment)
        } else {
            assessmentAttachmentContainer.visibility = View.GONE
        }

        // File Attachment Container
        val files = model.fileAttachments
        if (model.resourceId != null) {
            fileAttachmentContainer.visibility = View.VISIBLE
            tvFileName.text = model.title.removePrefix("Resource: ")
            tvFileSize.text = "Tap to open protected file"
            val ext = model.title.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 } ?: "FILE"
            tvFileExtension.text = ext

            val openAction = View.OnClickListener {
                callbacks.onOpenProtectedResource(model.resourceId)
            }
            fileAttachmentContainer.setOnClickListener(openAction)
            btnOpenFile.setOnClickListener(openAction)
        } else if (files.isNotEmpty()) {
            val file = files.first()
            fileAttachmentContainer.visibility = View.VISIBLE
            tvFileName.text = file.fileName.ifBlank { "Attachment" }
            tvFileSize.text = formatFileSize(file.sizeBytes)
            val ext = file.fileName.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 } ?: "FILE"
            tvFileExtension.text = ext

            val openAction = View.OnClickListener {
                callbacks.onOpenFile(file)
            }
            fileAttachmentContainer.setOnClickListener(openAction)
            btnOpenFile.setOnClickListener(openAction)
        } else {
            fileAttachmentContainer.visibility = View.GONE
        }

        // Metadata & Translation
        tvMeta.text = model.publishedAtText
        tvPinnedBadge.visibility = if (model.isPinned) View.VISIBLE else View.GONE

        applyTranslation(
            isTranslating = model.isTranslating,
            isTranslated = model.isTranslated,
            translatedTitle = model.title,
            translatedBody = model.cleanBody
        )
        btnTranslate.setOnClickListener {
            val m = currentModel ?: model
            if (m.isTranslating) return@setOnClickListener
            callbacks.onTranslateClick(m)
        }

        // Social Reactions & Read Receipts
        applyLike(model.isLiked, model.likeCount)
        applyEngagement(model.likeCount, model.isLiked, model.commentCount)
        applyRead(model.isRead, model.readCount, model.seenAvatars)

        btnLike.setOnClickListener {
            callbacks.onLikeClick(model)
        }
        btnComment.setOnClickListener {
            callbacks.onCommentClick(model)
        }
        btnShare.setOnClickListener {
            callbacks.onShareClick(model)
        }
        btnReadReceipts.setOnClickListener {
            callbacks.onReadReceiptsClick(model)
        }
    }

    fun bindPayloads(payloads: List<Any>, model: NoticePresentationModel) {
        for (payload in payloads) {
            when (payload) {
                is NoticePayload.Like -> applyLike(payload.isLiked, payload.count)
                is NoticePayload.Read -> applyRead(payload.isRead, payload.count, payload.seenAvatars)
                is NoticePayload.Poll -> applyPoll(payload.poll)
                is NoticePayload.Translation -> {
                    currentModel = model
                    applyTranslation(
                        payload.isTranslating,
                        payload.isTranslated,
                        payload.translatedTitle,
                        payload.translatedBody
                    )
                }
                is NoticePayload.Expand -> applyExpand(payload.isExpanded)
                is NoticePayload.Engagement -> applyEngagement(
                    payload.likeCount,
                    payload.isLiked,
                    payload.commentCount
                )
            }
        }
    }

    fun applyLike(isLiked: Boolean, count: Int) {
        val context = itemView.context
        if (isLiked) {
            ivLikeIcon.setImageResource(R.drawable.ic_heart_filled)
            ivLikeIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.cm_notice_like))
            ivLikeIcon.animate().cancel()
            ivLikeIcon.scaleX = 0.2f
            ivLikeIcon.scaleY = 0.2f
            ivLikeIcon.alpha = 0.8f
            ivLikeIcon.animate().scaleX(1.35f).scaleY(1.35f).alpha(1f).setDuration(160)
                .setInterpolator(android.view.animation.OvershootInterpolator(3.5f))
                .withEndAction {
                    ivLikeIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }.start()
            runCatching { ivLikeIcon.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        } else {
            ivLikeIcon.setImageResource(R.drawable.ic_heart_outline)
            ivLikeIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.cm_text_muted))
            ivLikeIcon.animate().cancel()
            ivLikeIcon.scaleX = 0.8f
            ivLikeIcon.scaleY = 0.8f
            ivLikeIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }

        if (count > 0) {
            tvLikeCount.text = NoticePresentationMapper.compactCount(count.toLong())
            tvLikeCount.setTextColor(ContextCompat.getColor(context, if (isLiked) R.color.cm_notice_like else R.color.cm_text_secondary))
            tvLikeCount.visibility = View.VISIBLE
        } else {
            tvLikeCount.visibility = View.GONE
        }
    }

    fun applyEngagement(likeCount: Int, isLiked: Boolean, commentCount: Int) {
        applyLike(isLiked, likeCount)
        if (commentCount > 0) {
            tvCommentCount.text = NoticePresentationMapper.compactCount(commentCount.toLong())
            tvCommentCount.visibility = View.VISIBLE
        } else {
            tvCommentCount.visibility = View.GONE
        }
    }

    fun applyRead(isRead: Boolean, count: Int, seenAvatarsList: List<String>) {
        if (count <= 0 && seenAvatarsList.isEmpty()) {
            seenAvatars.tag = null
            seenAvatars.removeAllViews()
            seenAvatars.visibility = View.GONE
            tvReadCount.visibility = View.GONE
            return
        }

        seenAvatars.visibility = View.VISIBLE
        val fingerprint = "$count-${seenAvatarsList.joinToString(",")}"
        if (seenAvatars.tag != fingerprint) {
            seenAvatars.tag = fingerprint
            seenAvatars.removeAllViews()

            val density = itemView.resources.displayMetrics.density
            val dp = { n: Int -> (n * density).toInt() }
            val avatarOverlap = dp(14)
            val avatarSize = dp(22)

            // Strictly show at most 3 avatar photos
            val maxPhotos = 3
            val photosToShow = seenAvatarsList.take(minOf(maxPhotos, count))
            val numPhotos = photosToShow.size

            photosToShow.forEachIndexed { index, avatarUrl ->
                val image = de.hdodenhof.circleimageview.CircleImageView(itemView.context).apply {
                    borderWidth = dp(1)
                    borderColor = ContextCompat.getColor(itemView.context, R.color.cm_surface)
                    setImageResource(R.drawable.ic_default_avatar)
                }
                seenAvatars.addView(
                    image,
                    FrameLayout.LayoutParams(avatarSize, avatarSize).apply {
                        marginStart = index * avatarOverlap
                    }
                )

                if (avatarUrl.startsWith("https://")) {
                    Glide.with(itemView)
                        .load(avatarUrl)
                        .override(avatarSize, avatarSize)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_default_avatar)
                        .error(R.drawable.ic_default_avatar)
                        .into(image)
                }
            }

            // 4th slot: White circular avatar counter badge shown when count > 3 and there are more readers
            val remainingCount = (count - numPhotos).coerceAtLeast(0)
            val showBadge = count > 3 && remainingCount > 0
            if (showBadge) {
                val counterBadge = TextView(itemView.context).apply {
                    textSize = if (remainingCount > 99) 7.5f else 9f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(0xFF0F172A.toInt())
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFFFFFFF.toInt())
                        setStroke(dp(1), 0x33000000.toInt())
                    }
                    background = shape
                    elevation = 2 * density
                    text = if (remainingCount > 999) "${remainingCount / 1000}k+" else "+$remainingCount"
                }
                seenAvatars.addView(
                    counterBadge,
                    FrameLayout.LayoutParams(avatarSize, avatarSize).apply {
                        marginStart = numPhotos * avatarOverlap
                    }
                )
            }

            // Lock exact width on seenAvatars to permanently stick in the bottom right corner without overflowing
            val totalBubbles = numPhotos + (if (showBadge) 1 else 0)
            val exactWidth = if (totalBubbles > 0) ((totalBubbles - 1) * avatarOverlap) + avatarSize else 0
            val lp = seenAvatars.layoutParams
            if (lp != null && lp.width != exactWidth) {
                lp.width = exactWidth
                seenAvatars.layoutParams = lp
            }
        }
    }

    fun applyExpand(isExpanded: Boolean) {
        val model = currentModel
        if (model != null) {
            val bodyText = if (model.isTranslated) model.translationText?.toString().orEmpty() else model.cleanBody.toString()
            com.shuaib.classmate.activities.ClassMateNoticeText.bind(
                view = tvPreview,
                body = bodyText,
                query = "",
                isExpanded = isExpanded,
                onToggleExpand = { callbacks.onCardClick(model) }
            )
        } else {
            if (isExpanded) {
                tvPreview.maxLines = Int.MAX_VALUE
                tvPreview.ellipsize = null
            } else {
                tvPreview.maxLines = 5
                tvPreview.ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    fun applyTranslation(
        isTranslating: Boolean,
        isTranslated: Boolean,
        translatedTitle: String?,
        translatedBody: CharSequence?
    ) {
        val model = currentModel
        if (isTranslating) {
            btnTranslate.text = "Translating…"
            btnTranslate.isEnabled = false
            btnTranslate.alpha = 0.6f
        } else if (isTranslated) {
            btnTranslate.text = "Original"
            btnTranslate.isEnabled = true
            btnTranslate.alpha = 1.0f
            if (!translatedTitle.isNullOrBlank()) {
                tvTitle.text = translatedTitle
            }
            if (translatedBody != null) {
                if (model != null) {
                    com.shuaib.classmate.activities.ClassMateNoticeText.bind(
                        view = tvPreview,
                        body = translatedBody.toString(),
                        query = "",
                        isExpanded = model.isExpanded,
                        onToggleExpand = { callbacks.onCardClick(model) }
                    )
                } else {
                    tvPreview.text = translatedBody
                }
            }
        } else {
            btnTranslate.text = "Translate"
            btnTranslate.isEnabled = true
            btnTranslate.alpha = 1.0f
            if (!translatedTitle.isNullOrBlank()) {
                tvTitle.text = translatedTitle
            }
            if (translatedBody != null) {
                if (model != null) {
                    com.shuaib.classmate.activities.ClassMateNoticeText.bind(
                        view = tvPreview,
                        body = translatedBody.toString(),
                        query = "",
                        isExpanded = model.isExpanded,
                        onToggleExpand = { callbacks.onCardClick(model) }
                    )
                } else {
                    tvPreview.text = translatedBody
                }
            }
        }
    }

    fun applyPoll(poll: PollPresentationModel) {
        pollContainer.visibility = View.VISIBLE
        tvPollSubtext.text = if (poll.isMultiple) "Select one or more options" else "Select one option"
        tvPollTotalVotes.text = "${poll.totalVoters} student${if (poll.totalVoters == 1) "" else "s"} voted (${poll.totalVotes} vote${if (poll.totalVotes == 1) "" else "s"})"

        if (poll.canViewVotes) {
            tvPollViewVotes.visibility = View.VISIBLE
            tvPollViewVotes.setOnClickListener {
                callbacks.onPollViewVotesClick(poll.noticeId, poll.id)
            }
        } else {
            tvPollViewVotes.visibility = View.GONE
        }

        pollOptionsList.removeAllViews()
        val inflater = LayoutInflater.from(itemView.context)
        val density = itemView.resources.displayMetrics.density

        poll.options.forEach { opt ->
            val optView = inflater.inflate(R.layout.item_poll_option_m3, pollOptionsList, false)
            val cardOption = optView.findViewById<MaterialCardView>(R.id.cardPollOption)
            val track = optView.findViewById<View>(R.id.viewProgressTrack)
            val indicator = optView.findViewById<ImageView>(R.id.ivPollIndicator)
            val tvOptText = optView.findViewById<TextView>(R.id.tvOptionText)
            val tvPct = optView.findViewById<TextView>(R.id.tvOptionPercentage)
            val tvVotes = optView.findViewById<TextView>(R.id.tvOptionVotes)

            tvOptText.text = opt.text
            tvPct.text = opt.percentageFormatted
            tvVotes.text = opt.votesFormatted

            if (poll.isMultiple) {
                indicator.setImageResource(if (opt.isMyVote) R.drawable.ic_poll_square_selected else R.drawable.ic_poll_square_unselected)
            } else {
                indicator.setImageResource(if (opt.isMyVote) R.drawable.ic_poll_circle_selected else R.drawable.ic_poll_circle_unselected)
            }

            if (opt.isMyVote) {
                cardOption.strokeColor = 0xFF8B5CF6.toInt()
                cardOption.strokeWidth = (1.5f * density).toInt()
                tvOptText.setTextColor(0xFF7C3AED.toInt())
                tvPct.setTextColor(0xFF7C3AED.toInt())
                track.setBackgroundColor(0x358B5CF6.toInt())
            } else {
                cardOption.strokeColor = ContextCompat.getColor(itemView.context, R.color.cm_notice_premium_stroke)
                cardOption.strokeWidth = (1.2f * density).toInt()
                tvOptText.setTextColor(ContextCompat.getColor(itemView.context, R.color.cm_text_primary))
                tvPct.setTextColor(ContextCompat.getColor(itemView.context, R.color.cm_text_primary))
                track.setBackgroundColor(0x1F8B5CF6.toInt())
            }

            val applyTrackWidth = {
                val totalW = cardOption.width
                if (totalW > 0) {
                    val targetW = (totalW * (opt.percentage / 100f)).toInt()
                    val lp = track.layoutParams
                    if (lp.width != targetW) {
                        lp.width = targetW
                        track.layoutParams = lp
                    }
                }
            }
            if (cardOption.width > 0) {
                applyTrackWidth()
            } else {
                cardOption.post { applyTrackWidth() }
            }

            cardOption.setOnClickListener {
                callbacks.onPollVoteClick(poll.noticeId, poll.id, opt.id)
            }

            pollOptionsList.addView(optView)
        }
    }

    private fun formatFileSize(sizeBytes: Long): String {
        return if (sizeBytes > 0) {
            if (sizeBytes >= 1024 * 1024) {
                String.format(java.util.Locale.ENGLISH, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
            } else {
                "${sizeBytes / 1024} KB"
            }
        } else {
            "Tap to open file"
        }
    }
}
