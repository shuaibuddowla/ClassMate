package com.shuaib.classmate.data.remote.supabase

import com.shuaib.classmate.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseClientProvider @Inject constructor() {
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
            install(Auth)
            install(Postgrest)
        }
    }
}
