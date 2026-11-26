package com.shuaib.classmate.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.shuaib.classmate.R
import com.shuaib.classmate.workers.LibraryUploadWorker

/**
 * Single source of truth for ClassMate notification channels and default sound.
 * Channel IDs use a `_v2` suffix so existing installs migrate cleanly off OS-cached
 * system default sounds onto ClassMate's custom notification tone.
 */
object ClassMateNotificationChannels {

    const val CHANNEL_GENERAL = "classmate_notifications_v2"
    const val CHANNEL_NOTICES = "classmate_notices_v2"
    const val CHANNEL_CANCELLATIONS = "classmate_cancellations_v2"
    const val CHANNEL_ASSESSMENTS = "classmate_assessments_v2"
    const val CHANNEL_NOTICE_REMINDERS = "notice_reminders_v2"
    const val CHANNEL_CHAT = "chat_messages_v2"

    private val LEGACY_CHANNEL_IDS = listOf(
        "classmate_notifications",
        "classmate_notices",
        "classmate_cancellations",
        "classmate_assessments",
        "notice_reminders",
        "chat_messages"
    )

    fun soundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.classmate_notification}")

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        LEGACY_CHANNEL_IDS.forEach { legacyId ->
            runCatching { manager.deleteNotificationChannel(legacyId) }
        }

        val toneUri = soundUri(context)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun buildHighChannel(
            id: String,
            name: String,
            desc: String,
            lightsAndBadge: Boolean = false
        ): NotificationChannel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = desc
            enableVibration(true)
            setSound(toneUri, audioAttributes)
            if (lightsAndBadge) {
                enableLights(true)
                setShowBadge(true)
            }
        }

        val channels = listOf(
            buildHighChannel(
                CHANNEL_CHAT,
                "Chat Messages",
                "ClassMate chat message notifications",
                lightsAndBadge = true
            ),
            buildHighChannel(
                CHANNEL_GENERAL,
                "ClassMate Notifications",
                "Notifications for class schedules, notices, and polls"
            ),
            buildHighChannel(
                CHANNEL_NOTICES,
                "ClassMate Notices",
                "Notifications for ClassMate updates"
            ),
            buildHighChannel(
                CHANNEL_CANCELLATIONS,
                "ClassMate Cancellations",
                "Notifications for cancelled classes"
            ),
            buildHighChannel(
                CHANNEL_ASSESSMENTS,
                "Upcoming Assessments & Deadlines",
                "Reminders for upcoming class tests, quizzes, and assignments",
                lightsAndBadge = true
            ),
            buildHighChannel(
                CHANNEL_NOTICE_REMINDERS,
                "Notice reminders",
                "Scheduled reminders for saved ClassMate notices"
            ),
            NotificationChannel(
                LibraryUploadWorker.CHANNEL_ID,
                "Library Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress notifications for background library file uploads"
            }
        )

        manager.createNotificationChannels(channels)
    }

    fun isGeneralChannelDisabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_GENERAL)
                ?.importance == NotificationManager.IMPORTANCE_NONE
}
