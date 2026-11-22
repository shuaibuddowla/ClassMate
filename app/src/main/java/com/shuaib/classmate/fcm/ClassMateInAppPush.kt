package com.shuaib.classmate.fcm

import kotlinx.coroutines.flow.MutableSharedFlow

data class InAppNotificationEvent(
    val title: String,
    val body: String,
    val kind: String,
    val recordId: String?,
    val openTab: String
)

object ClassMateInAppPush {
    val events = MutableSharedFlow<InAppNotificationEvent>(extraBufferCapacity = 8)
    fun notify(event: InAppNotificationEvent) {
        events.tryEmit(event)
    }
}
