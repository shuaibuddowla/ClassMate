package com.shuaib.classmate.chat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.notices.NoticeForwardManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ChatNotificationHelper {
    const val CHANNEL_ID = "chat_messages"
    const val CHANNEL_NAME = "Chat Messages"
    const val GROUP_KEY = "com.shuaib.classmate.CHAT"
    const val REPLY_ACTION = "com.shuaib.classmate.REPLY_ACTION"
    const val KEY_REPLY_TEXT = "key_reply_text"
    const val KEY_ROOM_ID = "key_room_id"
    const val KEY_ROOM_TYPE = "key_room_type"
    const val KEY_SENDER_NAME = "key_sender_name"

    private val styles = mutableMapOf<String, NotificationCompat.MessagingStyle>()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun showMessageNotification(
        context: Context,
        roomId: String,
        roomType: String,
        senderId: String,
        senderName: String,
        senderAvatar: String,
        messageText: String,
        timestamp: Long
    ) {
        scope.launch {
            val avatarBitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    if (senderAvatar.isNotBlank()) {
                        Glide.with(context)
                            .asBitmap()
                            .load(senderAvatar)
                            .circleCrop()
                            .submit(64, 64)
                            .get()
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_classmate_notification)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_KEY)
                .setGroupSummary(false)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(KEY_ROOM_ID, roomId)
                putExtra(KEY_ROOM_TYPE, roomType)
                putExtra(KEY_SENDER_NAME, senderName)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, roomId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)

            val user = Person.Builder().setName("You").build()
            val style = styles.getOrPut(roomId) {
                NotificationCompat.MessagingStyle(user)
            }

            val personBuilder = Person.Builder().setName(senderName)
            avatarBitmap?.let {
                personBuilder.setIcon(IconCompat.createWithBitmap(it))
            }
            val person = personBuilder.build()

            style.addMessage(NoticeForwardManager.previewText(messageText), timestamp, person)
            builder.setStyle(style)

            // RemoteInput Inline Reply
            val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel("Reply...")
                .build()

            val replyIntent = Intent(context, ChatReplyReceiver::class.java).apply {
                action = REPLY_ACTION
                putExtra(KEY_ROOM_ID, roomId)
                putExtra(KEY_ROOM_TYPE, roomType)
                putExtra(KEY_SENDER_NAME, senderName)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                roomId.hashCode() + 1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            )

            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_send,
                "Reply",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            builder.addAction(replyAction)

            // Mark as Read Action
            val markReadIntent = Intent(context, ChatReplyReceiver::class.java).apply {
                action = "MARK_READ"
                putExtra(KEY_ROOM_ID, roomId)
            }
            val markReadPending = PendingIntent.getBroadcast(
                context, roomId.hashCode() + 2, markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(NotificationCompat.Action(0, "Mark as Read", markReadPending))

            val manager = NotificationManagerCompat.from(context)
            try {
                manager.notify(roomId.hashCode(), builder.build())
            } catch (e: SecurityException) { }
        }
    }

    fun clearRoom(roomId: String) {
        styles.remove(roomId)
    }
}
