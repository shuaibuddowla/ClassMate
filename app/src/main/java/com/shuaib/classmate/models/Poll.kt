package com.shuaib.classmate.models

data class Poll(
    val id: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: com.google.firebase.Timestamp? = null,
    val expiresAt: com.google.firebase.Timestamp? = null,
    val isActive: Boolean = true,
    val votes: Map<String, String> = emptyMap(),
    val allowMultipleAnswers: Boolean = false,
    val multiVotes: Map<String, List<String>> = emptyMap()
) {
    fun votesFor(option: String): Int {
        return if (allowMultipleAnswers) {
            allMultiVoteSelections().values.sumOf { selected -> selected.count { it == option } }
        } else {
            votes.values.count { it == option }
        }
    }

    val totalVotes: Int
        get() = if (allowMultipleAnswers) {
            allMultiVoteSelections().values.sumOf { it.size }
        } else {
            votes.size
        }

    val totalVoters: Int
        get() = if (allowMultipleAnswers) {
            allMultiVoteSelections().count { it.value.isNotEmpty() }
        } else {
            votes.size
        }

    fun percentageFor(option: String): Int {
        if (totalVotes == 0) return 0
        return (votesFor(option) * 100) / totalVotes
    }

    fun hasVoted(userId: String): Boolean =
        selectedOptionsFor(userId).isNotEmpty()

    fun userVote(userId: String): String? =
        selectedOptionsFor(userId).firstOrNull()

    fun selectedOptionsFor(userId: String): List<String> {
        return if (allowMultipleAnswers) {
            multiVotes[userId] ?: decodeMultiVote(votes[userId])
        } else {
            votes[userId]?.let { listOf(it) } ?: multiVotes[userId]?.take(1) ?: emptyList()
        }
    }

    private fun allMultiVoteSelections(): Map<String, List<String>> {
        val selections = votes.mapValues { (_, value) -> decodeMultiVote(value) }.toMutableMap()
        multiVotes.forEach { (userId, selected) ->
            if (selected.isNotEmpty()) selections[userId] = selected
        }
        return selections
    }

    private fun decodeMultiVote(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(MULTI_VOTE_SEPARATOR).filter { it.isNotBlank() }
    }

    companion object {
        const val MULTI_VOTE_SEPARATOR = "|||"
        fun encodeMultiVote(options: List<String>): String =
            options.filter { it.isNotBlank() }.joinToString(MULTI_VOTE_SEPARATOR)
    }

    fun getWinner(): String? {
        if (totalVotes == 0) return null
        var maxVotes = -1
        var winner = ""
        options.forEach { option ->
            val count = votesFor(option)
            if (count > maxVotes) {
                maxVotes = count
                winner = option
            }
        }
        return if (maxVotes > 0) winner else null
    }
}
