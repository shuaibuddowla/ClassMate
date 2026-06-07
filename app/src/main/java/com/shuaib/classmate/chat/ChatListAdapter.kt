package com.shuaib.classmate.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import de.hdodenhof.circleimageview.CircleImageView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatListAdapter(
    private val onChatClick: (ChatListItem) -> Unit
) : ListAdapter<ChatListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatListItem.Header -> VIEW_TYPE_HEADER
            is ChatListItem.Room -> VIEW_TYPE_ROOM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_chat_header, parent, false))
        } else {
            ChatViewHolder(inflater.inflate(R.layout.item_chat_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is HeaderViewHolder && item is ChatListItem.Header) {
            holder.bind(item)
        } else if (holder is ChatViewHolder && item is ChatListItem.Room) {
            holder.bind(item)
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvHeaderTitle)
        fun bind(item: ChatListItem.Header) {
            title.text = item.title
        }
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: CircleImageView = itemView.findViewById(R.id.ivChatAvatar)
        private val avatarLetter: TextView = itemView.findViewById(R.id.tvChatAvatarLetter)
        private val groupIcon: ImageView = itemView.findViewById(R.id.ivGroupIcon)
        private val onlineDot: View = itemView.findViewById(R.id.viewOnlineDot)
        private val title: TextView = itemView.findViewById(R.id.tvChatName)
        private val subtitle: TextView = itemView.findViewById(R.id.tvLastMessage)
        private val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val unreadBadge: TextView = itemView.findViewById(R.id.tvUnreadBadge)
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

        fun bind(item: ChatListItem.Room) {
            title.text = item.title
            subtitle.text = item.subtitle
            timestamp.text = if (item.room.lastMessageTime > 0) {
                val format = if (isToday(item.room.lastMessageTime)) timeFormat else dateFormat
                format.format(Date(item.room.lastMessageTime))
            } else {
                ""
            }
            unreadBadge.isVisible = item.room.unreadCount > 0
            unreadBadge.text = item.room.unreadCount.coerceAtMost(99).toString()
            onlineDot.isVisible = !item.isGroup && item.isOnline

            if (item.isGroup) {
                avatarLetter.visibility = View.GONE
                avatar.setImageDrawable(null)
                avatar.background = AvatarUtils.circle(item.room.id)
                groupIcon.isVisible = true
            } else {
                groupIcon.isVisible = false
                AvatarUtils.bind(avatar, avatarLetter, item.otherUserId.ifBlank { item.title }, item.title, item.avatarUrl)
            }
            itemView.setOnClickListener { onChatClick(item) }
        }

        private fun isToday(value: Long): Boolean {
            val date = Calendar.getInstance().apply { timeInMillis = value }
            val today = Calendar.getInstance()
            return date.get(Calendar.YEAR) == today.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROOM = 1

        private val DiffCallback = object : DiffUtil.ItemCallback<ChatListItem>() {
            override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
                return when {
                    oldItem is ChatListItem.Header && newItem is ChatListItem.Header -> oldItem.title == newItem.title
                    oldItem is ChatListItem.Room && newItem is ChatListItem.Room -> oldItem.room.id == newItem.room.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean = oldItem == newItem
        }
    }
}
