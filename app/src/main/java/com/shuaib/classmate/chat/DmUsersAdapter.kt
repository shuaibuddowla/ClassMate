package com.shuaib.classmate.chat

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.model.ChatUser
import com.shuaib.classmate.utils.ThemeColors
import de.hdodenhof.circleimageview.CircleImageView

class DmUsersAdapter(
    private val onlineIdsProvider: () -> Set<String>,
    private val onUserClick: (ChatUser) -> Unit
) : ListAdapter<ChatUser, DmUsersAdapter.UserViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        return UserViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_dm_user, parent, false)
        )
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: CircleImageView = itemView.findViewById(R.id.ivAvatar)
        private val avatarLetter: TextView = itemView.findViewById(R.id.tvAvatarLetter)
        private val userName: TextView = itemView.findViewById(R.id.tvUserName)
        private val userStatus: TextView = itemView.findViewById(R.id.tvUserStatus)
        private val onlineDot: View = itemView.findViewById(R.id.viewOnlineDot)

        fun bind(user: ChatUser) {
            userName.text = user.name
            AvatarUtils.bind(avatar, avatarLetter, user.id, user.name, user.avatarUrl)
            val online = user.id in onlineIdsProvider() || user.isOnline
            val statusColor = if (online) ThemeColors.success(itemView.context) else ThemeColors.textMuted(itemView.context)
            onlineDot.visibility = View.VISIBLE
            onlineDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusColor)
            }
            userStatus.text = if (online) "Online" else "last seen recently"
            userStatus.setTextColor(statusColor)
            itemView.setOnClickListener { onUserClick(user) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ChatUser>() {
            override fun areItemsTheSame(oldItem: ChatUser, newItem: ChatUser): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ChatUser, newItem: ChatUser): Boolean {
                return oldItem == newItem
            }
        }
    }
}
