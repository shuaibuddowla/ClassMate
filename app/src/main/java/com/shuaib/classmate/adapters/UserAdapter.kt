/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/adapters/UserAdapter.kt
 */
package com.shuaib.classmate.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemUserBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation

class UserAdapter(
    private var users: List<User>,
    private val rootView: ViewGroup? = null,
    private val onUserClick: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    var onUserLongClick: ((User) -> Unit)? = null

    inner class UserViewHolder(val binding: ItemUserBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.binding.apply {
            val displayName = user.fullName.ifBlank { user.name }.ifBlank { "Unnamed user" }
            tvUserName.text = displayName
            tvUserEmail.text = user.email.ifBlank { "No email" }
            tvStudentId.text = user.studentId.ifBlank { "No student ID" }
            tvUserRole.text = user.role.ifBlank { "student" }.uppercase()
            applyRoleBadge(tvUserRole, user.role)
            tvAvatarLetter.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            
            loadProfilePicture(user.photoUrl, ivUserAvatar)
            
            root.applyClickAnimation { onUserClick(user) }
            root.setOnLongClickListener {
                onUserLongClick?.invoke(user)
                true
            }
        }
    }

    private fun loadProfilePicture(photoUrl: String?, imageView: ImageView) {
        if (photoUrl.isNullOrBlank()) {
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        Glide.with(imageView.context)
            .load(photoUrl)
            .circleCrop()
            .placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
            .into(imageView)
    }

    private fun applyRoleBadge(textView: TextView, role: String) {
        val fill = when (role.lowercase()) {
            "superadmin" -> "#FF4D6D"
            "admin" -> "#FFB347"
            else -> "#34D399"
        }
        textView.setTextColor(ThemeColors.textInverse(textView.context))
        textView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor(fill))
        }
    }

    override fun getItemCount(): Int = users.size

    fun updateList(newList: List<User>) {
        users = newList
        notifyDataSetChanged()
    }
}
