package com.shuaib.classmate.utils

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException

object AuthDebug {
    const val TAG = "AuthDebug"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }

    fun maskEmail(email: String): String {
        val trimmed = email.trim()
        val atIndex = trimmed.indexOf("@")
        if (atIndex <= 0) return "***"
        val local = trimmed.take(atIndex)
        val domain = trimmed.drop(atIndex + 1)
        val visibleLocal = local.take(2)
        val visibleDomain = domain.take(2)
        return "$visibleLocal***@$visibleDomain***"
    }

    fun clientIdSummary(clientId: String): String {
        val value = clientId.trim()
        if (value.isBlank()) return "blank"
        val prefix = value.take(8)
        val suffix = value.takeLast(10)
        return "$prefix...$suffix"
    }

    fun logRuntimeConfig(context: Context, auth: FirebaseAuth?, source: String, webClientId: String) {
        val appInitialized = FirebaseApp.getApps(context).isNotEmpty()
        val googleAppIdResource = context.resources.getIdentifier("google_app_id", "string", context.packageName)
        d("$source runtime FirebaseApp initialized=$appInitialized")
        d("$source runtime FirebaseAuth available=${auth != null}")
        d("$source runtime currentUser uid=${auth?.currentUser?.uid ?: "none"}")
        d("$source runtime package=${context.packageName}")
        d("$source runtime default_web_client_id exists=${webClientId.isNotBlank()} summary=${clientIdSummary(webClientId)}")
        d("$source runtime google_app_id generated resource exists=${googleAppIdResource != 0}")
    }

    fun logAuthFailure(operation: String, error: Exception) {
        val code = (error as? FirebaseAuthException)?.errorCode ?: "none"
        e("$operation failure class=${error.javaClass.name} code=$code message=${error.message}", error)
    }

    fun logFirestoreFailure(operation: String, error: Exception) {
        val code = (error as? FirebaseFirestoreException)?.code?.name ?: "none"
        e("$operation Firestore failure class=${error.javaClass.name} code=$code message=${error.message}", error)
    }
}
