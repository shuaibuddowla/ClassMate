package com.shuaib.classmate.network

import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.utils.AppConstants
import com.shuaib.classmate.utils.AppContextManager
import okhttp3.Request
import java.io.IOException

object BackendApiClient {

    fun url(path: String): String {
        val baseUrl = AppConstants.BACKEND_BASE_URL
        if (baseUrl.isBlank() || !baseUrl.startsWith("https://")) {
            throw IOException("Secure backend is not configured. Set BACKEND_BASE_URL in local.properties.")
        }
        return "$baseUrl/${path.trimStart('/')}"
    }

    fun authenticated(builder: Request.Builder): Request {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw IOException("BackendApiClient.authenticated cannot be called from the main thread")
        }

        val token = ClassMateAuthApi.getValidAccessToken()
            ?: ClassMateAuthApi.accessToken
            ?: throw IOException("Please sign in to continue.")

        android.util.Log.d("BackendApiClient", "Authenticated with Supabase token prefix: ${token.take(15)}... (len=${token.length})")

        val batchId = AppContextManager.getManagedBatchId()
            .ifBlank { AppContextManager.getBatchId() }

        val semesterId = AppContextManager.getSemesterId()

        return builder

            .header("Authorization", "Bearer $token")

            .header("User-Agent", "ClassMate-Android")

            // Context headers are verified by the Worker; they are not trusted

            // authorization input on their own.

            .apply {

                if (batchId.isNotBlank()) header("X-Batch-Id", batchId)

                if (semesterId.isNotBlank()) header("X-Semester-Id", semesterId)

            }

            .build()

    }

}

