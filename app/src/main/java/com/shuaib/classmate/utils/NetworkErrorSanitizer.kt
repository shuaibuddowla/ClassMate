package com.shuaib.classmate.utils

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkErrorSanitizer {
    private const val DEFAULT_NO_INTERNET_MESSAGE = "No internet connection. Please check your network and try again."

    @JvmStatic
    fun sanitize(error: Throwable?): String {
        if (error == null) return "An unexpected error occurred. Please try again."
        return sanitize(error.message, error)
    }

    @JvmStatic
    fun sanitize(message: String?, error: Throwable? = null): String {
        val raw = message.orEmpty()
        val isNetwork = error is UnknownHostException ||
                error is ConnectException ||
                error is SocketTimeoutException ||
                error is IOException ||
                raw.contains("supabase.co", ignoreCase = true) ||
                raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("No address associated with hostname", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("Network is unreachable", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ||
                raw.contains("Connection refused", ignoreCase = true) ||
                raw.contains("Software caused connection abort", ignoreCase = true) ||
                raw.contains("SSLHandshakeException", ignoreCase = true) ||
                raw.contains("ENETUNREACH", ignoreCase = true)

        if (isNetwork) {
            return DEFAULT_NO_INTERNET_MESSAGE
        }

        // Mask raw internal URLs or Supabase keys
        if (raw.contains("http://", ignoreCase = true) ||
            raw.contains("https://", ignoreCase = true) ||
            raw.contains(".supabase.", ignoreCase = true) ||
            raw.contains("apikey", ignoreCase = true)
        ) {
            return "Unable to complete network request. Please check your connection and try again."
        }

        return raw.ifBlank { "Something went wrong. Please try again." }
    }
}
