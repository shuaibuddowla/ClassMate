package com.shuaib.classmate.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.chat.model.ChatMessage

class ChatSearchAdapter(
    private val onClick: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, ChatSearchAdapter.ResultViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(android.R.id.text1)
        private val text: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(message: ChatMessage) {
            title.text = message.senderName
            title.setTextColor(0xFFF0F4FF.toInt())
            text.text = message.text
            text.setTextColor(0xFF8899BB.toInt())
            itemView.setBackgroundColor(0xFF0D1526.toInt())
            itemView.setOnClickListener { onClick(message) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem == newItem
        }
    }
}
