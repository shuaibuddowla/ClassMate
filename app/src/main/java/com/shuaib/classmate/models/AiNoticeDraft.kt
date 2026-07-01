package com.shuaib.classmate.models

import com.google.gson.annotations.SerializedName

data class AiNoticeDraft(
    @SerializedName("type") val type: String? = "GENERAL",
    @SerializedName("title") val title: String? = "",
    @SerializedName("body") val body: String? = "",
    @SerializedName("subject") val subject: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("deadline") val deadline: String? = null,
    @SerializedName("startDate") val startDate: String? = null,
    @SerializedName("endDate") val endDate: String? = null,
    @SerializedName("priority") val priority: String? = "NORMAL",
    @SerializedName("isUrgent") val isUrgent: Boolean? = false,
    @SerializedName("shouldPushNotification") val shouldPushNotification: Boolean? = true,
    @SerializedName("notificationTitle") val notificationTitle: String? = "",
    @SerializedName("notificationBody") val notificationBody: String? = "",
    @SerializedName("missingFields") val missingFields: List<String>? = emptyList(),
    @SerializedName("confidence") val confidence: Double? = 0.0
)
