package com.shuaib.classmate.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.utils.AppConstants
import com.shuaib.classmate.utils.AppContextManager
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object BackendApiClient {
    fun url(path: String): String {
        val baseUrl = AppConstants.BACKEND_BASE_URL
        if (baseUrl.isBlank() || !baseUrl.startsWith("https://")) {
            throw IOException("Secure backend is not configured. Set BACKEND_BASE_URL in local.properties.")
        }
        return "$baseUrl/${path.trimStart('/')}"
    }

    fun authenticated(builder: Request.Builder): Request {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IOException("Please sign in to continue.")
        val token = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token
            ?: throw IOException("Unable to obtain a Firebase session.")
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
