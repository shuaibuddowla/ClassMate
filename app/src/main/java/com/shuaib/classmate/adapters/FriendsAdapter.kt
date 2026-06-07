// com/shuaib/classmate/adapters/FriendsAdapter.kt
package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemFriendBinding
import com.shuaib.classmate.models.User

class FriendsAdapter(
    private var friends: List<User>,
    private val onFriendClick: (User) -> Unit,
    private val onCallClick: (User) -> Unit,
    private val onWhatsappClick: (User) -> Unit
) : RecyclerView.Adapter<FriendsAdapter.FriendViewHolder>() {

    inner class FriendViewHolder(val binding: ItemFriendBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val binding = ItemFriendBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FriendViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friend = friends[position]
        holder.binding.apply {
            tvFriendName.text = friend.name
            tvFriendId.text = friend.studentId

            Glide.with(ivFriendProfile.context)
                .load(friend.photoUrl.ifEmpty { null })
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivFriendProfile)

            root.setOnClickListener { onFriendClick(friend) }
            btnCall.setOnClickListener { onCallClick(friend) }
            btnWhatsapp.setOnClickListener { onWhatsappClick(friend) }
        }
    }

    override fun getItemCount(): Int = friends.size

    fun updateList(newList: List<User>) {
        friends = newList
        notifyDataSetChanged()
    }
}
