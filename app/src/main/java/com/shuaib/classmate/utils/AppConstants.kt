package com.shuaib.classmate.utils

import com.shuaib.classmate.BuildConfig

object AppConstants {
    // Public client configuration. Service credentials live in Cloudflare Worker secrets.
    val BACKEND_BASE_URL: String = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
    val ONESIGNAL_APP_ID: String = BuildConfig.ONESIGNAL_APP_ID

    // Public Telegram destination identifier used to construct the upload form.
    val TELEGRAM_CHANNEL_ID: String = BuildConfig.TELEGRAM_CHANNEL_ID

    val GEMINI_MODEL: String = BuildConfig.GEMINI_MODEL
    val GROQ_MODEL: String = BuildConfig.GROQ_MODEL

    // Links
    const val WHATSAPP_GROUP_LINK = "https://chat.whatsapp.com/ENVSddeEDrwGrNntcAOzzr"
}
