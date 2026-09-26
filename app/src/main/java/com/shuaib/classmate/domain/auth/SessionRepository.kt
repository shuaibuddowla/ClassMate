package com.shuaib.classmate.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * Bridges the Firebase session into the V2 Supabase data layer.
 * Firebase remains the only authentication provider.
 */
interface SessionRepository {
    val session: Flow<SessionProfile?>

    val isConfigured: Boolean

    suspend fun synchronizeFirebaseSession(): Result<SessionProfile>

    suspend fun refresh(): Result<SessionProfile>

    suspend fun signOut(): Result<Unit>
}
