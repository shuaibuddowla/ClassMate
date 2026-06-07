package com.shuaib.classmate.chat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.shuaib.classmate.R

class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(ChatNotificationHelper.KEY_ROOM_ID) ?: return

        if (intent.action == "MARK_READ") {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(roomId.hashCode())
            ChatNotificationHelper.clearRoom(roomId)
            return
        }

        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotificationHelper.KEY_REPLY_TEXT)
            ?.toString()?.trim() ?: return

        // Send via ChatRepository
        ChatRepository.sendMessage(roomId, replyText)

        // Update notification to show "Message sent" and then cancel it
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val updatedNotif = NotificationCompat.Builder(context, ChatNotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_classmate_notification)
            .setContentText("Message sent")
            .setAutoCancel(true)
            .build()
        manager.notify(roomId.hashCode(), updatedNotif)
        manager.cancel(roomId.hashCode())
        ChatNotificationHelper.clearRoom(roomId)
    }
}
