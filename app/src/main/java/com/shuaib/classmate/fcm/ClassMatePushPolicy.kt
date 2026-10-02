package com.shuaib.classmate.fcm

internal object ClassMatePushPolicy {
    fun addressedToCurrentUser(project: String?, recipient: String?, eventBatch: String?, release: Boolean,
                               expectedProject: String?, profileId: String, status: String, role: String, batch: String): Boolean =
        !expectedProject.isNullOrBlank() && project == expectedProject && profileId.isNotBlank() &&
            recipient == profileId && status == "active" && (release || role != "student" || (batch.isNotBlank() && eventBatch == batch))
}
