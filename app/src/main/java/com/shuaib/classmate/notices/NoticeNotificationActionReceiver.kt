package com.shuaib.classmate.notices

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAuthActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.ClassMateNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Handles inline Like and Comment actions directly from ClassMate notice notifications.
 */
class NoticeNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_LIKE && action != ACTION_COMMENT) return

        val noticeId = intent.getStringExtra(EXTRA_NOTICE_ID)?.takeIf { it.isNotBlank() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, noticeId.hashCode())
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ClassMateNotificationChannels.CHANNEL_NOTICES
        val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "ClassMate Notice"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val alreadyLiked = intent.getBooleanExtra(EXTRA_LIKED, false)

        val commentText = if (action == ACTION_COMMENT) {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_COMMENT_TEXT)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        } else null

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ClassMateAuthApi.attach(appContext)
                if (!ClassMateAuthApi.hasSavedSession()) {
                    renderUpdatedNotification(
                        context = appContext,
                        manager = manager,
                        noticeId = noticeId,
                        notificationId = notificationId,
                        channelId = channelId,
                        groupKey = groupKey,
                        title = title,
                        body = body,
                        statusLine = "Sign in to ClassMate to interact",
                        liked = alreadyLiked
                    )
                    return@launch
                }

                if (action == ACTION_LIKE) {
                    // Optimistically confirm in the shade right away
                    renderUpdatedNotification(
                        context = appContext,
                        manager = manager,
                        noticeId = noticeId,
                        notificationId = notificationId,
                        channelId = channelId,
                        groupKey = groupKey,
                        title = title,
                        body = body,
                        statusLine = "♥ Like sent",
                        liked = true
                    )

                    val profileId = ClassMateAuthApi.notificationIdentity()?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?: ClassMateAuthApi.initializeProfile()?.optString("id")
                        ?: throw IllegalStateException("Profile unavailable")

                    val existing = runCatching {
                        ClassMateAuthApi.rows(
                            "notice_reactions",
                            "select=pinned,reminder_at&profile_id=eq.$profileId&notice_id=eq.$noticeId&limit=1"
                        ).optJSONObject(0)
                    }.getOrNull()

                    val row = JSONObject()
                        .put("profile_id", profileId)
                        .put("notice_id", noticeId)
                        .put("liked", true)
                        .put("pinned", existing?.optBoolean("pinned", false) ?: false)
                    existing?.optString("reminder_at")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                        row.put("reminder_at", it)
                    }
                    ClassMateAuthApi.upsert("notice_reactions", row)
                } else if (action == ACTION_COMMENT && commentText != null) {
                    // Immediately update notification to clear RemoteInput spinner and show confirmation
                    renderUpdatedNotification(
                        context = appContext,
                        manager = manager,
                        noticeId = noticeId,
                        notificationId = notificationId,
                        channelId = channelId,
                        groupKey = groupKey,
                        title = title,
                        body = body,
                        statusLine = "💬 Sending comment…",
                        liked = alreadyLiked
                    )

                    ClassMateAuthApi.rpcText(
                        "save_notice_comment",
                        JSONObject()
                            .put("target_notice", noticeId)
                            .put("target_body", commentText)
                            .put("target_parent", JSONObject.NULL)
                            .put("target_request", UUID.randomUUID().toString())
                    )

                    val preview = if (commentText.length > 80) commentText.take(80) + "…" else commentText
                    renderUpdatedNotification(
                        context = appContext,
                        manager = manager,
                        noticeId = noticeId,
                        notificationId = notificationId,
                        channelId = channelId,
                        groupKey = groupKey,
                        title = title,
                        body = body,
                        statusLine = "✓ Comment updated: \"$preview\"",
                        liked = alreadyLiked
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice notification action failed: ${e.message}")
                val failMsg = if (action == ACTION_LIKE) {
                    "Couldn't send like · Tap to open notice"
                } else {
                    "Couldn't post comment · Tap to open notice"
                }
                renderUpdatedNotification(
                    context = appContext,
                    manager = manager,
                    noticeId = noticeId,
                    notificationId = notificationId,
                    channelId = channelId,
                    groupKey = groupKey,
                    title = title,
                    body = body,
                    statusLine = failMsg,
                    liked = alreadyLiked
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NoticeNotifAction"
        const val ACTION_LIKE = "com.shuaib.classmate.ACTION_NOTICE_LIKE"
        const val ACTION_COMMENT = "com.shuaib.classmate.ACTION_NOTICE_COMMENT"
        const val KEY_COMMENT_TEXT = "key_notice_comment_text"

        const val EXTRA_NOTICE_ID = "extra_notice_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_GROUP_KEY = "extra_group_key"
        const val EXTRA_TITLE = "extra_notice_title"
        const val EXTRA_BODY = "extra_notice_body"
        const val EXTRA_LIKED = "extra_notice_liked"

        fun attachNoticeActions(
            context: Context,
            builder: NotificationCompat.Builder,
            noticeId: String,
            notificationId: Int,
            channelId: String,
            groupKey: String?,
            title: String,
            body: String,
            liked: Boolean = false
        ) {
            // 1. Inline Comment (RemoteInput)
            val remoteInput = RemoteInput.Builder(KEY_COMMENT_TEXT)
                .setLabel("Write a comment…")
                .build()

            val commentIntent = Intent(context, NoticeNotificationActionReceiver::class.java).apply {
                action = ACTION_COMMENT
                putExtra(EXTRA_NOTICE_ID, noticeId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_GROUP_KEY, groupKey)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_LIKED, liked)
            }
            val commentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val commentPending = PendingIntent.getBroadcast(
                context,
                (noticeId + "_comment").hashCode(),
                commentIntent,
                commentFlags
            )
            val commentAction = NotificationCompat.Action.Builder(
                R.drawable.ic_send,
                "Comment",
                commentPending
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()
            builder.addAction(commentAction)

            // 2. Like Action
            val likeIntent = Intent(context, NoticeNotificationActionReceiver::class.java).apply {
                action = ACTION_LIKE
                putExtra(EXTRA_NOTICE_ID, noticeId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_GROUP_KEY, groupKey)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_LIKED, liked)
            }
            val likePending = PendingIntent.getBroadcast(
                context,
                (noticeId + "_like").hashCode(),
                likeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val likeLabel = if (liked) "Liked ✓" else "Like"
            val likeIcon = if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            builder.addAction(likeIcon, likeLabel, likePending)
        }

        private fun renderUpdatedNotification(
            context: Context,
            manager: NotificationManager?,
            noticeId: String,
            notificationId: Int,
            channelId: String,
            groupKey: String?,
            title: String,
            body: String,
            statusLine: String,
            liked: Boolean
        ) {
            if (manager == null) return
            val openIntent = Intent(context, ClassMateAuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("OPEN_TAB", "notices")
                putExtra("TARGET_NOTICE_ID", noticeId)
                putExtra("noticeId", noticeId)
                putExtra("record_id", noticeId)
            }
            val openPending = PendingIntent.getActivity(
                context,
                noticeId.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fullBody = if (body.isNotBlank()) "$body\n\n$statusLine" else statusLine
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_classmate_notification)
                .setColor(0xFF3B82F6.toInt())
                .setContentTitle(title)
                .setContentText(statusLine)
                .setSubText(statusLine)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
                .setContentIntent(openPending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)

            if (!groupKey.isNullOrBlank()) {
                builder.setGroup(groupKey)
                builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            }

            attachNoticeActions(
                context = context,
                builder = builder,
                noticeId = noticeId,
                notificationId = notificationId,
                channelId = channelId,
                groupKey = groupKey,
                title = title,
                body = body,
                liked = liked
            )

            runCatching { manager.notify(notificationId, builder.build()) }
        }
    }
}
