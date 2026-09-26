package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

@Singleton
class SupabaseClientProvider @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {
    private val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    val isConfigured: Boolean
        get() = url.startsWith("https://") && publishableKey.isNotBlank()

    val client: SupabaseClient by lazy {
        check(isConfigured) {
            "Supabase is not configured. Add SUPABASE_URL and " +
                "SUPABASE_PUBLISHABLE_KEY to local.properties."
        }

        createSupabaseClient(url, publishableKey) {
            accessToken = {
                firebaseAuth.currentUser
                    ?.getIdToken(false)
                    ?.await()
                    ?.token
            }
            install(Postgrest)
        }
    }
}
