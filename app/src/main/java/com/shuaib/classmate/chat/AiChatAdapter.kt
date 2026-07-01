package com.shuaib.classmate.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.chat.model.AiMessage
import com.shuaib.classmate.databinding.ItemAiMessageAssistantBinding
import com.shuaib.classmate.databinding.ItemAiMessageUserBinding
import com.shuaib.classmate.notices.NoticeTextFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatAdapter(
    private val onCopy: (AiMessage) -> Unit,
    private val onShare: (AiMessage) -> Unit,
    private val onSpeak: (AiMessage) -> Unit
) : ListAdapter<AiMessage, RecyclerView.ViewHolder>(DiffCallback) {

    var speakingMessageId: String? = null
        private set

    fun setSpeakingMessageId(id: String?) {
        val oldId = speakingMessageId
        speakingMessageId = id
        // Notify items changed to update icons
        currentList.forEachIndexed { index, message ->
            if (message.id == oldId || message.id == id) {
                notifyItemChanged(index)
            }
        }
    }


    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).sender == "user") TYPE_USER else TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val binding = ItemAiMessageUserBinding.inflate(inflater, parent, false)
            UserViewHolder(binding)
        } else {
            val binding = ItemAiMessageAssistantBinding.inflate(inflater, parent, false)
            AssistantViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (holder is UserViewHolder) {
            holder.bind(message)
        } else if (holder is AssistantViewHolder) {
            holder.bind(message)
        }
    }

    inner class UserViewHolder(private val binding: ItemAiMessageUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: AiMessage) {
            val spannable = android.text.SpannableStringBuilder(message.text)
            android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
            binding.tvMessageText.text = spannable
            binding.tvMessageText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            binding.tvTimestamp.text = formatTime(message.timestamp)
        }
    }

    inner class AssistantViewHolder(private val binding: ItemAiMessageAssistantBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: AiMessage) {
            val context = binding.root.context
            val blocks = parseBlocks(message.text)

            cleanDynamicViews(binding.llContentContainer, binding.tvMessageText)

            val hasTable = blocks.any { it is ChatBlock.Table }
            if (!hasTable) {
                binding.tvMessageText.visibility = View.VISIBLE
                val formatted = NoticeTextFormatter.format(context, message.text)
                binding.tvMessageText.text = formatted
                binding.tvMessageText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            } else {
                binding.tvMessageText.visibility = View.GONE
                for (block in blocks) {
                    when (block) {
                        is ChatBlock.Text -> {
                            if (block.content.isBlank()) continue
                            val textView = createTextViewFromTemplate(binding.tvMessageText, isTable = false)
                            val formatted = NoticeTextFormatter.format(context, block.content)
                            textView.text = formatted
                            binding.llContentContainer.addView(textView)
                        }
                        is ChatBlock.Table -> {
                            if (block.content.isBlank()) continue
                            
                            val firstTableLine = block.content.split("\n").firstOrNull { it.trim().startsWith("|") }
                            val pipeCount = firstTableLine?.count { it == '|' } ?: 0
                            val colsCount = if (pipeCount >= 2) pipeCount - 1 else 3
                            
                            val density = context.resources.displayMetrics.density
                            val screenWidth = context.resources.displayMetrics.widthPixels
                            val minWidthPx = (colsCount * 200 * density).toInt()
                            val finalWidth = if (minWidthPx > screenWidth) minWidthPx else ViewGroup.LayoutParams.MATCH_PARENT

                            val scrollView = HorizontalScrollView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                isHorizontalScrollBarEnabled = true
                                isFillViewport = true
                                setPadding(0, 8, 0, 8)
                                clipToPadding = false
                            }
                            val textView = createTextViewFromTemplate(binding.tvMessageText, isTable = true, customWidth = finalWidth)
                            val formatted = NoticeTextFormatter.format(context, block.content)
                            textView.text = formatted
                            scrollView.addView(textView)
                            binding.llContentContainer.addView(scrollView)
                        }
                    }
                }
            }

            binding.tvTimestamp.text = formatTime(message.timestamp)

            // Update speaking button visual state dynamically
            if (message.id == speakingMessageId) {
                binding.btnSpeak.setImageResource(com.shuaib.classmate.R.drawable.ic_close)
                binding.btnSpeak.contentDescription = "Stop Speaking"
                binding.btnSpeak.imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, com.shuaib.classmate.R.color.cm_primary)
                )
            } else {
                binding.btnSpeak.setImageResource(com.shuaib.classmate.R.drawable.ic_volume)
                binding.btnSpeak.contentDescription = "Speak Text"
                binding.btnSpeak.imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, com.shuaib.classmate.R.color.cm_text_secondary)
                )
            }

            binding.btnCopy.setOnClickListener { onCopy(message) }
            binding.btnShare.setOnClickListener { onShare(message) }
            binding.btnSpeak.setOnClickListener { onSpeak(message) }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1

        private val DiffCallback = object : DiffUtil.ItemCallback<AiMessage>() {
            override fun areItemsTheSame(oldItem: AiMessage, newItem: AiMessage): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: AiMessage, newItem: AiMessage): Boolean {
                return oldItem == newItem
            }
        }
    }
}

private sealed class ChatBlock {
    data class Text(val content: String) : ChatBlock()
    data class Table(val content: String) : ChatBlock()
}

private fun parseBlocks(text: String): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    val lines = text.split("\n")
    val currentTableLines = mutableListOf<String>()
    val currentTextLines = mutableListOf<String>()

    fun flushText() {
        if (currentTextLines.isNotEmpty()) {
            blocks.add(ChatBlock.Text(currentTextLines.joinToString("\n")))
            currentTextLines.clear()
        }
    }

    fun flushTable() {
        if (currentTableLines.isNotEmpty()) {
            blocks.add(ChatBlock.Table(currentTableLines.joinToString("\n")))
            currentTableLines.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        val isTableLine = trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
        if (isTableLine) {
            flushText()
            currentTableLines.add(line)
        } else {
            flushTable()
            currentTextLines.add(line)
        }
    }
    flushText()
    flushTable()

    return blocks
}

private fun cleanDynamicViews(container: ViewGroup, template: View) {
    val childCount = container.childCount
    for (i in childCount - 1 downTo 0) {
        val child = container.getChildAt(i)
        if (child != template) {
            container.removeViewAt(i)
        }
    }
}

private fun createTextViewFromTemplate(template: TextView, isTable: Boolean, customWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT): TextView {
    val context = template.context
    val textView = TextView(context)
    textView.layoutParams = ViewGroup.LayoutParams(
        if (isTable) customWidth else ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    textView.setTextColor(template.textColors)
    if (isTable) {
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.textSize * 0.93f)
        textView.setLineSpacing(0f, 1f)
    } else {
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.textSize)
        textView.setLineSpacing(template.lineSpacingExtra, template.lineSpacingMultiplier)
    }
    textView.setTextIsSelectable(template.isTextSelectable)
    textView.movementMethod = template.movementMethod
    return textView
}
