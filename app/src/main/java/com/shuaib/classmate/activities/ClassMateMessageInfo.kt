package com.shuaib.classmate.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WhatsApp-style "Message info" reader receipt view for ClassMate notices.
 * Renders an outgoing notice message preview bubble on chat wallpaper,
 * followed by a clean "Read by" card with blue double ticks (✓✓),
 * contact avatars (or colored initials), and WhatsApp-formatted timestamps (Today, 12:11 am).
 */
internal object ClassMateMessageInfo {

    private val AVATAR_PALETTE = intArrayOf(
        Color.parseColor("#00A884"), // WhatsApp Teal
        Color.parseColor("#3B82F6"), // Blue
        Color.parseColor("#8B5CF6"), // Purple
        Color.parseColor("#EC4899"), // Pink
        Color.parseColor("#F59E0B"), // Amber
        Color.parseColor("#10B981"), // Emerald
        Color.parseColor("#06B6D4"), // Cyan
        Color.parseColor("#F97316")  // Orange
    )

    fun show(
        activity: AppCompatActivity,
        notice: JSONObject,
        currentUserId: String,
        isAdmin: Boolean,
        receiptsEnabled: Boolean,
        initialReadCount: Long = 0
    ) {
        val sheet = BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_notice_message_info, null, false)
        sheet.setContentView(view)

        val behavior = sheet.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        val displayMetrics = activity.resources.displayMetrics
        behavior.peekHeight = (displayMetrics.heightPixels * 0.88f).toInt()

        val noticeId = notice.getString("id")
        val noticeTitle = notice.optString("title").trim()
        val noticeBody = notice.optString("body").trim()
        val noticeCreatedAt = notice.optString("created_at")

        // 1. Setup Toolbar
        val ivBack = view.findViewById<ImageView>(R.id.ivBack)
        val ivRefresh = view.findViewById<ImageView>(R.id.ivRefresh)
        ivBack.setOnClickListener { sheet.dismiss() }

        // 2. Setup ClassMate Notice Preview Card
        val tvNoticeTitle = view.findViewById<TextView>(R.id.tvNoticeTitle)
        val tvNoticeBody = view.findViewById<TextView>(R.id.tvNoticeBody)
        val tvNoticeTime = view.findViewById<TextView>(R.id.tvNoticeTime)
        val tvNoticeKindTag = view.findViewById<TextView>(R.id.tvNoticeKindTag)
        val viewNoticeAccent = view.findViewById<View>(R.id.viewNoticeAccent)

        val isCancelled = !notice.isNull("class_change_id") && notice.optString("class_change_id").isNotBlank()
        val hasResource = !notice.isNull("resource_id") && notice.optString("resource_id").isNotBlank()

        if (isCancelled) {
            tvNoticeKindTag.text = "CLASS CANCELLATION"
            tvNoticeKindTag.setTextColor(activity.getColor(R.color.cm_notice_premium_cancel))
            viewNoticeAccent.setBackgroundColor(activity.getColor(R.color.cm_notice_premium_cancel))
        } else if (hasResource) {
            tvNoticeKindTag.text = "RESOURCE NOTICE"
            tvNoticeKindTag.setTextColor(activity.getColor(R.color.cm_notice_premium_resource))
            viewNoticeAccent.setBackgroundColor(activity.getColor(R.color.cm_notice_premium_resource))
        } else {
            tvNoticeKindTag.text = "GENERAL NOTICE"
            tvNoticeKindTag.setTextColor(activity.getColor(R.color.cm_primary))
            viewNoticeAccent.setBackgroundColor(activity.getColor(R.color.cm_primary))
        }

        if (noticeTitle.isNotBlank()) {
            tvNoticeTitle.visibility = View.VISIBLE
            tvNoticeTitle.text = noticeTitle
        } else {
            tvNoticeTitle.visibility = View.GONE
        }

        tvNoticeBody.text = noticeBody
        Linkify.addLinks(tvNoticeBody, Linkify.WEB_URLS)

        tvNoticeTime.text = formatBubbleTime(noticeCreatedAt)

        // 3. Setup "Read by" Section
        val tvReadCountBadge = view.findViewById<TextView>(R.id.tvReadCountBadge)
        val readersContainer = view.findViewById<LinearLayout>(R.id.readersListContainer)
        val pbLoading = view.findViewById<ProgressBar>(R.id.pbLoading)
        val tvEmptyReaders = view.findViewById<TextView>(R.id.tvEmptyReaders)
        val btnLoadMore = view.findViewById<MaterialButton>(R.id.btnLoadMore)

        if (initialReadCount > 0) {
            tvReadCountBadge.text = "($initialReadCount)"
        } else {
            tvReadCountBadge.text = ""
        }

        var cursorTime: String? = null
        var cursorId: String? = null
        var loading = false
        var loadedCount = 0

        fun loadReaders(reset: Boolean = true) {
            if (loading) return
            loading = true
            if (reset) {
                cursorTime = null
                cursorId = null
                loadedCount = 0
                readersContainer.removeAllViews()
                tvEmptyReaders.visibility = View.GONE
            }

            pbLoading.visibility = if (reset) View.VISIBLE else View.GONE
            btnLoadMore.isEnabled = false
            ivRefresh.isEnabled = false

            activity.lifecycleScope.launch {
                try {
                    val arguments = JSONObject()
                        .put("target_notice", noticeId)
                        .put("page_size", 50)
                    cursorTime?.let {
                        arguments.put("before_time", it).put("before_id", cursorId)
                    }

                    val responseText = ClassMateAuthApi.rpcText("notice_readers_page", arguments)
                    val rawArray = JSONArray(responseText)
                    val rawReaders = mutableListOf<JSONObject>()
                    for (i in 0 until rawArray.length()) {
                        rawReaders.add(rawArray.getJSONObject(i))
                    }

                    // Admin Privacy Filter
                    val adminOptedOut = isAdmin && !receiptsEnabled
                    val filteredReaders = if (adminOptedOut) {
                        rawReaders.filterNot { it.optString("profile_id") == currentUserId }
                    } else {
                        rawReaders
                    }

                    if (!sheet.isShowing || activity.isFinishing) return@launch

                    loadedCount += filteredReaders.size
                    val totalDisplayCount = maxOf(initialReadCount, loadedCount.toLong())
                    tvReadCountBadge.text = if (totalDisplayCount > 0) "($totalDisplayCount)" else ""

                    rawReaders.lastOrNull()?.let {
                        cursorTime = it.optString("read_at")
                        cursorId = it.optString("profile_id")
                    }

                    btnLoadMore.visibility = if (rawReaders.size == 50) View.VISIBLE else View.GONE
                    btnLoadMore.isEnabled = true

                    tvEmptyReaders.visibility = if (loadedCount == 0) View.VISIBLE else View.GONE

                    // Bind reader rows
                    val inflater = LayoutInflater.from(activity)
                    filteredReaders.forEachIndexed { index, reader ->
                        val rowView = inflater.inflate(R.layout.item_notice_message_info_reader, readersContainer, false)
                        val ivAvatar = rowView.findViewById<ImageView>(R.id.ivAvatar)
                        val tvAvatarLetter = rowView.findViewById<TextView>(R.id.tvAvatarLetter)
                        val tvReaderName = rowView.findViewById<TextView>(R.id.tvReaderName)
                        val tvReadTime = rowView.findViewById<TextView>(R.id.tvReadTime)
                        val itemDivider = rowView.findViewById<View>(R.id.itemDivider)

                        val readerName = reader.optString("reader_name", "ClassMate Member").trim()
                        val avatarUrl = reader.optString("avatar_url").trim()
                        val readAt = reader.optString("read_at")

                        tvReaderName.text = readerName
                        tvReadTime.text = formatWhatsAppTime(readAt)

                        // Avatar binding with colored initial fallback
                        if (avatarUrl.startsWith("https://")) {
                            tvAvatarLetter.visibility = View.GONE
                            ivAvatar.visibility = View.VISIBLE
                            Glide.with(activity)
                                .load(avatarUrl)
                                .circleCrop()
                                .placeholder(R.drawable.ic_default_avatar)
                                .error(R.drawable.ic_default_avatar)
                                .into(ivAvatar)
                        } else {
                            ivAvatar.visibility = View.GONE
                            tvAvatarLetter.visibility = View.VISIBLE
                            tvAvatarLetter.text = getInitialLetters(readerName)

                            val color = getAvatarColor(readerName)
                            val bg = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(color)
                            }
                            tvAvatarLetter.background = bg
                        }

                        // Last row hides divider
                        if (index == filteredReaders.lastIndex && rawReaders.size < 50) {
                            itemDivider.visibility = View.GONE
                        }

                        readersContainer.addView(rowView)
                    }
                } catch (e: Exception) {
                    if (sheet.isShowing && loadedCount == 0) {
                        tvEmptyReaders.visibility = View.VISIBLE
                        tvEmptyReaders.text = "Could not load readers. Tap refresh to retry."
                    }
                } finally {
                    loading = false
                    pbLoading.visibility = View.GONE
                    btnLoadMore.isEnabled = true
                    ivRefresh.isEnabled = true
                }
            }
        }

        btnLoadMore.setOnClickListener { loadReaders(reset = false) }
        ivRefresh.setOnClickListener { loadReaders(reset = true) }

        sheet.show()
        loadReaders(reset = true)
    }

    /**
     * Formats timestamp inside WhatsApp bubble, e.g. "12:10 am"
     */
    private fun formatBubbleTime(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "Now"
        return runCatching {
            val instant = Instant.parse(timestamp)
            val zonedDateTime = instant.atZone(ZoneId.systemDefault())
            zonedDateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH)
        }.getOrElse { "Now" }
    }

    /**
     * Formats timestamp in WhatsApp reader receipt style:
     * - "Today, 12:11 am"
     * - "Yesterday, 10:15 pm"
     * - "07 Oct, 9:20 am"
     */
    private fun formatWhatsAppTime(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "Recently"
        return runCatching {
            val instant = Instant.parse(timestamp)
            val zdt = instant.atZone(ZoneId.systemDefault())
            val date = zdt.toLocalDate()
            val today = LocalDate.now(ZoneId.systemDefault())
            val timeStr = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH)

            when {
                date == today -> "Today, $timeStr"
                date == today.minusDays(1) -> "Yesterday, $timeStr"
                date.year == today.year -> {
                    val dateStr = zdt.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                    "$dateStr, $timeStr"
                }
                else -> {
                    val dateStr = zdt.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                    "$dateStr, $timeStr"
                }
            }
        }.getOrElse { "Recently" }
    }

    /**
     * Extracts 1-2 uppercase initial letters (e.g. "Akash CS" -> "A", "Ismile Baskota" -> "IB")
     */
    private fun getInitialLetters(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "C"
            parts.size == 1 -> parts[0].take(1).uppercase(Locale.ENGLISH)
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.ENGLISH)
        }
    }

    private fun getAvatarColor(name: String): Int {
        val hash = kotlin.math.abs(name.hashCode())
        return AVATAR_PALETTE[hash % AVATAR_PALETTE.size]
    }
}
