package com.shuaib.classmate.utils

import com.shuaib.classmate.BuildConfig

object AppConstants {
    // Service credentials are read from ignored local.properties at build time.
    val ONESIGNAL_APP_ID: String = BuildConfig.ONESIGNAL_APP_ID
    val ONESIGNAL_REST_API_KEY: String = BuildConfig.ONESIGNAL_REST_API_KEY

    // Telegram
    val TELEGRAM_BOT_TOKEN: String = BuildConfig.TELEGRAM_BOT_TOKEN
    val TELEGRAM_CHANNEL_ID: String = BuildConfig.TELEGRAM_CHANNEL_ID

    // AI
    val GEMINI_API_KEY: String = BuildConfig.GEMINI_API_KEY
    val GROQ_API_KEY: String = BuildConfig.GROQ_API_KEY
    val GEMINI_MODEL: String = BuildConfig.GEMINI_MODEL
    val GROQ_MODEL: String = BuildConfig.GROQ_MODEL

    // Links
    const val WHATSAPP_GROUP_LINK = "https://chat.whatsapp.com/ENVSddeEDrwGrNntcAOzzr"
}
