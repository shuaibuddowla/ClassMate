package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.domain.auth.AcademicScope
import com.shuaib.classmate.domain.auth.ProfileStatus
import com.shuaib.classmate.domain.auth.RoleGrant
import com.shuaib.classmate.domain.auth.SessionProfile
import com.shuaib.classmate.domain.auth.SessionRepository
import com.shuaib.classmate.domain.auth.UserRole
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.from
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SupabaseSessionRepository @Inject constructor(
    private val clientProvider: SupabaseClientProvider
) : SessionRepository {
    private val mutableSession = MutableStateFlow<SessionProfile?>(null)

    override val session: Flow<SessionProfile?> = mutableSession.asStateFlow()

    override val isConfigured: Boolean
        get() = clientProvider.isConfigured

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<SessionProfile> =
        runCatching {
            require(idToken.isNotBlank()) { "Google ID token cannot be blank." }
            val client = clientProvider.client
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
            }
            loadCurrentProfile()
        }.onFailure { mutableSession.value = null }

    override suspend fun refresh(): Result<SessionProfile> =
        runCatching { loadCurrentProfile() }
            .onFailure { mutableSession.value = null }

    override suspend fun signOut(): Result<Unit> =
        runCatching {
            if (isConfigured) {
                clientProvider.client.auth.signOut()
            }
            mutableSession.value = null
        }

    private suspend fun loadCurrentProfile(): SessionProfile {
        val client = clientProvider.client
        val userId = checkNotNull(client.auth.currentUserOrNull()?.id) {
            "No active Supabase session."
        }

        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<ProfileRow>()

        val student = client.from("student_profiles")
            .select { filter { eq("profile_id", userId) } }
            .decodeList<StudentProfileRow>()
            .singleOrNull()

        val now = Instant.now()
        val grants = client.from("role_grants")
            .select { filter { eq("profile_id", userId) } }
            .decodeList<RoleGrantRow>()
            .filter { row ->
                row.revokedAt == null &&
                    (row.expiresAt == null || Instant.parse(row.expiresAt).isAfter(now))
            }
            .map { it.toDomain(profile.universityId) }

        return SessionProfile(
            id = profile.id,
            universityId = requireNotNull(profile.universityId) {
                "Authenticated profile has no university assignment."
            },
            email = profile.email,
            displayName = profile.displayName.ifBlank { profile.email.substringBefore('@') },
            avatarUrl = profile.avatarUrl,
            status = profile.status.toProfileStatus(),
            studentScope = student?.let {
                AcademicScope(
                    universityId = requireNotNull(profile.universityId),
                    departmentId = it.departmentId,
                    batchId = it.assignedBatchId,
                    sectionId = it.sectionId
                )
            },
            roleGrants = grants
        ).also { mutableSession.value = it }
    }

    private fun RoleGrantRow.toDomain(universityId: String?): RoleGrant = RoleGrant(
        id = id,
        role = role.toUserRole(),
        scope = AcademicScope(
            universityId = requireNotNull(universityId),
            departmentId = departmentId,
            batchId = batchId,
            sectionId = sectionId,
            courseOfferingId = courseOfferingId
        ),
        startsAtEpochMillis = Instant.parse(startsAt).toEpochMilli(),
        expiresAtEpochMillis = expiresAt?.let { Instant.parse(it).toEpochMilli() }
    )

    private fun String.toUserRole(): UserRole = when (this) {
        "student" -> UserRole.STUDENT
        "cr" -> UserRole.CR
        "teacher" -> UserRole.TEACHER
        "admin" -> UserRole.ADMIN
        else -> error("Unsupported server role: $this")
    }

    private fun String.toProfileStatus(): ProfileStatus = when (this) {
        "active" -> ProfileStatus.ACTIVE
        "pending_setup" -> ProfileStatus.PENDING_SETUP
        "graduated" -> ProfileStatus.GRADUATED
        "suspended" -> ProfileStatus.SUSPENDED
        "blocked" -> ProfileStatus.BLOCKED
        else -> error("Unsupported profile status: $this")
    }
}
