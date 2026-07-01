package com.shuaib.classmate.ai

import com.shuaib.classmate.models.AiNoticeDraft

interface AiProvider {
    suspend fun summarizeNotice(input: NoticeSummaryInput): Result<String>
    suspend fun generateNoticeDraft(input: NoticeDraftInput): Result<AiNoticeDraft>
    suspend fun chatWithAi(input: AiChatInput): Result<String>
}
