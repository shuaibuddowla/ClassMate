package com.shuaib.classmate.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * Authentication boundary used during the Firebase-to-Supabase migration.
 * Implementations own provider-specific session and token behavior.
 */
interface SessionRepository {
    val session: Flow<SessionProfile?>

    suspend fun refresh(): Result<SessionProfile>

    suspend fun signOut(): Result<Unit>
}
