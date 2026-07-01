/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/ClassMateApp.kt
 */
package com.shuaib.classmate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.onesignal.OneSignal
import com.onesignal.notifications.INotificationClickListener
import com.onesignal.notifications.INotificationClickEvent
import com.onesignal.user.subscriptions.IPushSubscriptionObserver
import com.onesignal.user.subscriptions.PushSubscriptionChangedState
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.chat.ChatNotificationHelper
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.data.FirestoreManager
import com.shuaib.classmate.utils.AppConstants
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.utils.NotificationRouter
import com.shuaib.classmate.utils.Obfuscator
import com.shuaib.classmate.workers.DailySchedulerWorker
import com.shuaib.classmate.workers.MorningBriefWorker
import com.shuaib.classmate.workers.OfflineSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class ClassMateApp : Application() {
    private var lastSavedOneSignalPlayerId: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        NoticeTextFormatter.init(this)
        FirestoreManager.enableOfflinePersistence()

        val prefs = AppPreferences(this)

        applyThemePreference(prefs)
        createNotificationChannels()
        initializeServices(prefs)
    }

    private fun initializeServices(prefs: AppPreferences) {
        initializeOneSignal(prefs)
        scheduleBackgroundWorks()
        setupChatAuthListener()
    }

    private fun setupChatAuthListener() {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                val uid = user.uid
                val name = user.displayName?.takeIf { it.isNotBlank() }
                    ?: user.email?.substringBefore("@")
                    ?: "User"
                val avatar = user.photoUrl?.toString() ?: ""

                // Only initialize chat if online or already initialized
                ChatRepository.init(this@ClassMateApp, uid, name, avatar)

                // Save OneSignal ID
                try {
                    val playerId = OneSignal.User.pushSubscription.id
                    if (!playerId.isNullOrBlank()) {
                        saveOneSignalPlayerId(playerId)
                    }
                } catch (_: Exception) {}
            } else {
                // User is logged out: disconnect chat and opt out from OneSignal
                ChatRepository.close()
                try {
                    OneSignal.User.pushSubscription.optOut()
                    OneSignal.logout()
                } catch (_: Exception) {}
            }
        }
    }

    private fun initializeOneSignal(prefs: AppPreferences) {
        val appId = AppConstants.ONESIGNAL_APP_ID
        if (appId.isBlank()) {
            Log.e("ClassMateApp", "OneSignal App ID is missing. Push notifications disabled.")
            return
        }

        try {
            OneSignal.initWithContext(this, appId)
            setupOneSignalPlayerIdSync()
            syncNotificationPreference(prefs)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { OneSignal.Notifications.requestPermission(false) }
            }
        } catch (e: Exception) {
            Log.e("ClassMateApp", "OneSignal initialization failed: ${e.message}")
        }

        OneSignal.Notifications.addClickListener(object : INotificationClickListener {
            override fun onClick(event: INotificationClickEvent) {
                val data = event.notification.additionalData
                val type = data?.optString("type") ?: "notice"
                val subject = data?.optString("subject")
                val day = data?.optString("day")
                val roomId = data?.optString("roomId")
                val roomType = data?.optString("roomType") ?: "group"
                val senderId = data?.optString("senderId")
                val senderName = data?.optString("senderName") ?: ""
                val noticeId = data?.optString("noticeId")

                NotificationRouter.pendingSubject = subject
                NotificationRouter.pendingDay = day
                NotificationRouter.pendingChatRoomId = roomId?.takeIf { it.isNotBlank() }
                NotificationRouter.pendingChatSenderId = senderId?.takeIf { it.isNotBlank() }
                NotificationRouter.pendingChatRoomType = roomType
                NotificationRouter.pendingChatSenderName = senderName
                NotificationRouter.pendingNoticeId = noticeId
                NotificationRouter.pendingTab = when (type) {
                    "chat_message" -> "chat"
                    "resource" -> "pdf_library"
                    "poll" -> "notices"
                    else -> "notices"   // cancellation, substitute, assignment, deadline, exam → notices
                }

                if (type == "chat_message" && !roomId.isNullOrBlank()) {
                    val intent = Intent(this@ClassMateApp, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("roomId", roomId)
                        putExtra("roomType", roomType)
                        putExtra("senderName", senderName)
                        putExtra(ChatNotificationHelper.KEY_ROOM_ID, roomId)
                        putExtra(ChatNotificationHelper.KEY_ROOM_TYPE, roomType)
                        putExtra(ChatNotificationHelper.KEY_SENDER_NAME, senderName)
                    }
                    startActivity(intent)
                }
            }
        })
    }

    private fun setupOneSignalPlayerIdSync() {
        saveOneSignalPlayerId(OneSignal.User.pushSubscription.id)
        OneSignal.User.pushSubscription.addObserver(object : IPushSubscriptionObserver {
            override fun onPushSubscriptionChange(state: PushSubscriptionChangedState) {
                saveOneSignalPlayerId(state.current.id)
            }
        })
    }

    private fun saveOneSignalPlayerId(playerId: String?) {
        if (playerId.isNullOrBlank()) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val syncKey = "$uid:$playerId"
        val syncPrefs = getSharedPreferences(ONESIGNAL_SYNC_PREFS, MODE_PRIVATE)
        if (lastSavedOneSignalPlayerId == syncKey ||
            syncPrefs.getString(KEY_LAST_ONESIGNAL_PLAYER_ID, null) == syncKey
        ) {
            lastSavedOneSignalPlayerId = syncKey
            return
        }
        lastSavedOneSignalPlayerId = syncKey
        val payload = mapOf("oneSignalPlayerId" to playerId)
        FirebaseFirestore.getInstance()
            .document("users/$uid")
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                syncPrefs.edit().putString(KEY_LAST_ONESIGNAL_PLAYER_ID, syncKey).apply()
                Log.d("FirestoreDebug", "OneSignal player id synced for users/$uid")
            }
            .addOnFailureListener { e ->
                lastSavedOneSignalPlayerId = null
                Log.e("FirestoreDebug", "FULL ERROR: ${e.message}")
                Log.e("FirestoreDebug", "ERROR CLASS: ${e.javaClass.name}")
                Log.e("FirestoreDebug", "CAUSE: ${e.cause?.message}")
            }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Chat Channel
            val chatChannel = NotificationChannel(
                ChatNotificationHelper.CHANNEL_ID,
                ChatNotificationHelper.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "ClassMate chat message notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            // General Notifications Channel
            val generalChannel = NotificationChannel(
                "classmate_notifications",
                "ClassMate Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for class schedules, notices, and polls"
            }

            // Notices Channel
            val noticesChannel = NotificationChannel(
                "classmate_notices",
                "ClassMate Notices",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for ClassMate updates"
            }

            // Cancellations Channel
            val cancellationsChannel = NotificationChannel(
                "classmate_cancellations",
                "ClassMate Cancellations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for cancelled classes"
            }

            notificationManager.createNotificationChannels(
                listOf(chatChannel, generalChannel, noticesChannel, cancellationsChannel)
            )
        }
    }

    private fun applyThemePreference(prefs: AppPreferences) {
        prefs.setDarkMode(false)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun syncNotificationPreference(prefs: AppPreferences) {
        val isNotificationsEnabled = prefs.isNotificationsEnabled()
        val isLoggedIn = FirebaseAuth.getInstance().currentUser != null

        // Ensure OneSignal state matches user preference on app start
        if (isNotificationsEnabled && isLoggedIn) {
            OneSignal.User.pushSubscription.optIn()
        } else {
            OneSignal.User.pushSubscription.optOut()
        }
    }

    private fun scheduleBackgroundWorks() {
        CoroutineScope(Dispatchers.Default).launch {
            val workManager = WorkManager.getInstance(this@ClassMateApp)

            // Daily Timetable Sync
            val syncWorkRequest = PeriodicWorkRequestBuilder<DailySchedulerWorker>(
                24, TimeUnit.HOURS
            ).build()

            workManager.enqueueUniquePeriodicWork(
                "DailyTimetableSync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
            )

            // Morning Briefing at 8:00 AM
            val morningBriefRequest = PeriodicWorkRequestBuilder<MorningBriefWorker>(
                24, TimeUnit.HOURS
            ).setInitialDelay(calculateDelayUntil8AM(), TimeUnit.MILLISECONDS)
            .build()

            workManager.enqueueUniquePeriodicWork(
                "MorningBriefing",
                ExistingPeriodicWorkPolicy.KEEP,
                morningBriefRequest
            )

            val offlineSyncRequest = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                OfflineSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                offlineSyncRequest
            )
        }
    }

    private fun calculateDelayUntil8AM(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    companion object {
        private const val APP_STARTUP_DEFER_MS = 2500L
        private const val ONESIGNAL_SYNC_PREFS = "onesignal_sync"
        private const val KEY_LAST_ONESIGNAL_PLAYER_ID = "last_player_id"

        lateinit var instance: ClassMateApp
            private set
    }
}
