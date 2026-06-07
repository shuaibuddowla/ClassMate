package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemPollBinding
import com.shuaib.classmate.databinding.ItemPollOptionBinding
import com.shuaib.classmate.models.Poll
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Date

class PollAdapter(
    private val polls: List<Poll>,
    private val currentUserId: String,
    private val isAdmin: Boolean,
    private val onVote: (pollId: String, option: String) -> Unit,
    private val onMultiVote: (poll: Poll, option: String) -> Unit = { _, _ -> },
    private val onDelete: (pollId: String) -> Unit
) : RecyclerView.Adapter<PollAdapter.PollViewHolder>() {

    inner class PollViewHolder(val binding: ItemPollBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PollViewHolder {
        val binding = ItemPollBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PollViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PollViewHolder, position: Int) {
        val poll = polls[position]
        val b = holder.binding
        val context = holder.itemView.context
        val hasVoted = poll.hasVoted(currentUserId)
        val selectedOptions = poll.selectedOptionsFor(currentUserId)
        val isExpired = poll.expiresAt?.toDate()?.before(Date()) ?: false

        b.tvQuestion.text = poll.question
        b.tvTotalVotes.text = when (poll.totalVoters) {
            1 -> "1 voter"
            else -> "${poll.totalVoters} voters"
        }
        b.tvPollMode.text = if (poll.allowMultipleAnswers) {
            if (hasVoted) "Tap options to update your choices" else "Select one or more options"
        } else {
            if (hasVoted) "Tap another option to change your vote" else "Select one option"
        }
        b.tvStatus.text = if (isExpired) "Expired" else "Active"
        b.tvStatus.setTextColor(
            if (isExpired) ThemeColors.error(context) else ThemeColors.success(context)
        )

        b.optionsContainer.removeAllViews()
        poll.options.forEach { option ->
            val optionBinding = ItemPollOptionBinding.inflate(
                LayoutInflater.from(context),
                b.optionsContainer,
                false
            )

            val percentage = poll.percentageFor(option)
            val voteCount = poll.votesFor(option)
            val isSelected = selectedOptions.contains(option)
            val showResults = hasVoted || isExpired

            optionBinding.tvOptionText.text = option
            optionBinding.tvOptionPercent.text = "$percentage%"
            optionBinding.tvOptionVotes.text = when (voteCount) {
                1 -> "1 vote"
                else -> "$voteCount votes"
            }
            optionBinding.optionProgress.isVisible = showResults
            optionBinding.tvOptionPercent.isVisible = showResults
            optionBinding.tvOptionVotes.isVisible = showResults
            optionBinding.ivCheck.isVisible = isSelected

            if (showResults) {
                optionBinding.optionProgress.progress = percentage
                optionBinding.optionRoot.isEnabled = !isExpired
                if (isSelected) {
                    optionBinding.tvOptionText.setTextColor(ThemeColors.primary(context))
                    optionBinding.optionRoot.setBackgroundResource(R.drawable.bg_card_primary)
                    optionBinding.optionRoot.alpha = 1.0f
                } else {
                    optionBinding.tvOptionText.setTextColor(ThemeColors.textPrimary(context))
                    optionBinding.optionRoot.setBackgroundResource(R.drawable.bg_settings_card)
                    optionBinding.optionRoot.alpha = 0.72f
                }
            } else {
                optionBinding.optionRoot.isEnabled = true
                optionBinding.optionRoot.setBackgroundResource(R.drawable.bg_settings_card)
                optionBinding.optionRoot.alpha = 1.0f
            }

            if (!isExpired) {
                optionBinding.optionRoot.applyClickAnimation {
                    if (poll.allowMultipleAnswers) {
                        onMultiVote(poll, option)
                    } else {
                        onVote(poll.id, option)
                    }
                }
            }

            b.optionsContainer.addView(optionBinding.root)
        }

        b.btnDelete.isVisible = isAdmin
        b.btnDelete.setOnClickListener {
            android.app.AlertDialog.Builder(context, R.style.Theme_ClassMate_Dialog)
                .setTitle("Delete Poll?")
                .setMessage("This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> onDelete(poll.id) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun getItemCount() = polls.size
}
