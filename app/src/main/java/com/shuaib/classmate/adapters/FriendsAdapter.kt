// com/shuaib/classmate/adapters/FriendsAdapter.kt
package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemFriendBinding
import com.shuaib.classmate.models.User

class FriendsAdapter(
    friends: List<User>,
    private val onFriendClick: (User) -> Unit,
    private val onCallClick: (User) -> Unit,
    private val onWhatsappClick: (User) -> Unit
) : ListAdapter<User, FriendsAdapter.FriendViewHolder>(DiffCallback) {

    init {
        submitList(friends)
    }

    inner class FriendViewHolder(val binding: ItemFriendBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val binding = ItemFriendBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FriendViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friend = getItem(position)
        holder.binding.apply {
            tvFriendName.text = friend.name
            
            val details = listOfNotNull(
                friend.studentId.takeIf { it.isNotBlank() },
                friend.bloodGroup.takeIf { it.isNotBlank() },
                friend.homeDistrict.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            tvFriendId.text = details

            Glide.with(ivFriendProfile.context)
                .load(friend.photoUrl.ifEmpty { null })
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivFriendProfile)

            root.setOnClickListener { onFriendClick(friend) }
            btnCall.setOnClickListener { onCallClick(friend) }
            btnWhatsapp.setOnClickListener { onWhatsappClick(friend) }
        }
    }

    fun updateList(newList: List<User>) {
        submitList(newList.toList())
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<User>() {
            override fun areItemsTheSame(oldItem: User, newItem: User): Boolean =
                oldItem.uid == newItem.uid && oldItem.studentId == newItem.studentId

            override fun areContentsTheSame(oldItem: User, newItem: User): Boolean =
                oldItem == newItem
        }
    }
}
