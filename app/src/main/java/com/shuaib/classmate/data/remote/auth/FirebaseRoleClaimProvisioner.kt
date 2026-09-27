package com.shuaib.classmate.data.remote.auth

import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class FirebaseRoleClaimProvisioner @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val httpClient: OkHttpClient
) {
    suspend fun ensureAuthenticatedClaim() = withContext(Dispatchers.IO) {
        val user = checkNotNull(firebaseAuth.currentUser) {
            "No active Firebase session."
        }
        val currentToken = user.getIdToken(false).await()
        if (currentToken.claims[ROLE_CLAIM] == AUTHENTICATED_ROLE) {
            return@withContext
        }

        val bridgeUrl = BuildConfig.FIREBASE_ROLE_BRIDGE_URL.trim()
        check(bridgeUrl.startsWith("https://")) {
            "Firebase role bridge is not configured."
        }
        val idToken = requireNotNull(currentToken.token) {
            "Firebase did not provide an ID token."
        }
        val request = Request.Builder()
            .url(bridgeUrl)
            .header("Authorization", "Bearer $idToken")
            .header("Accept", "application/json")
            .post(EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Firebase role provisioning failed with HTTP ${response.code}.")
            }
        }

        val refreshedToken = user.getIdToken(true).await()
        check(refreshedToken.claims[ROLE_CLAIM] == AUTHENTICATED_ROLE) {
            "Firebase role claim was not present after refresh."
        }
    }

    private companion object {
        const val ROLE_CLAIM = "role"
        const val AUTHENTICATED_ROLE = "authenticated"
        const val EMPTY_JSON = "{}"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
