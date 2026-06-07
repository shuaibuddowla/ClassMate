// com/shuaib/classmate/widget/ClassMateWidget.kt
package com.shuaib.classmate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.R
import com.shuaib.classmate.utils.DateHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ClassMateWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(
                context.packageName,
                R.layout.widget_classmate
            )

            // Set day + date
            val cal = Calendar.getInstance()
            val dayFormat = SimpleDateFormat(
                "EEEE, MMM dd", Locale.getDefault()
            )
            views.setTextViewText(
                R.id.tvWidgetDay,
                dayFormat.format(cal.time)
            )

            // Open app on button click
            val openPending = createMainPendingIntent(context, widgetId)
            views.setOnClickPendingIntent(
                R.id.btnWidgetOpen, openPending
            )
            views.setOnClickPendingIntent(
                R.id.widgetNoticeRow,
                createNoticesPendingIntent(context, widgetId)
            )

            // Default values
            views.setTextViewText(
                R.id.tvWidgetNextSubject, "No classes today"
            )
            views.setTextViewText(
                R.id.tvWidgetNextTime, ""
            )
            views.setTextViewText(
                R.id.tvWidgetAssignment, "No deadlines"
            )
            views.setTextViewText(
                R.id.tvWidgetDaysLeft, ""
            )
            views.setTextViewText(
                R.id.tvWidgetNoticeTitle, "No notices yet"
            )
            views.setTextViewText(
                R.id.tvWidgetNoticeTime, ""
            )

            // Push initial update
            appWidgetManager.updateAppWidget(widgetId, views)

            // Load data from Firestore
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) return

            val db = FirebaseFirestore.getInstance()
            val todayDay = DateHelper.todayDayString()

            // Load today's next period
            db.collection("timetable")
                .document(todayDay)
                .collection("periods")
                .orderBy("startTime")
                .get()
                .addOnSuccessListener { snapshot ->
                    val now = Calendar.getInstance()
                    val currentTime = String.format(
                        "%02d:%02d",
                        now.get(Calendar.HOUR_OF_DAY),
                        now.get(Calendar.MINUTE)
                    )

                    // Find next upcoming period
                    val nextPeriod = snapshot.documents
                        .map { doc ->
                            Pair(
                                doc.getString("subject") ?: "",
                                doc.getString("startTime") ?: ""
                            )
                        }
                        .filter { it.second > currentTime }
                        .firstOrNull()

                    if (nextPeriod != null) {
                        views.setTextViewText(
                            R.id.tvWidgetNextSubject,
                            nextPeriod.first
                        )
                        views.setTextViewText(
                            R.id.tvWidgetNextTime,
                            nextPeriod.second
                        )
                    } else {
                        views.setTextViewText(
                            R.id.tvWidgetNextSubject,
                            "No more classes today"
                        )
                        views.setTextViewText(
                            R.id.tvWidgetNextTime, ""
                        )
                    }

                    appWidgetManager.updateAppWidget(
                        widgetId, views
                    )
                }

            // Load upcoming assignments
            // from user's countdown subcollection
            val uid = auth.currentUser?.uid ?: return
            db.collection("users")
                .document(uid)
                .collection("countdowns")
                .orderBy("submissionDate")
                .limit(1)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.isEmpty) return@addOnSuccessListener

                    val doc = snapshot.documents.first()
                    val subject = doc.getString("subject") ?: ""
                    val topic = doc.getString("topic") ?: ""
                    val dueDate = doc.getString(
                        "submissionDate") ?: ""

                    // Calculate days left
                    val sdf = SimpleDateFormat(
                        "yyyy-MM-dd", Locale.getDefault()
                    )
                    val dueMillis = try {
                        sdf.parse(dueDate)?.time ?: 0L
                    } catch (e: Exception) { 0L }

                    val daysLeft = ((dueMillis -
                        System.currentTimeMillis()) /
                        (1000 * 60 * 60 * 24)).toInt()

                    val daysText = when {
                        daysLeft < 0 -> "Overdue"
                        daysLeft == 0 -> "Due Today"
                        daysLeft == 1 -> "1 day left"
                        else -> "$daysLeft days left"
                    }

                    views.setTextViewText(
                        R.id.tvWidgetAssignment,
                        "$subject: $topic"
                    )
                    views.setTextViewText(
                        R.id.tvWidgetDaysLeft,
                        daysText
                    )

                    appWidgetManager.updateAppWidget(
                        widgetId, views
                    )
                }

            // Load latest notice
            db.collection("notices")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .addOnSuccessListener { snapshot ->
                    val latestNotice = snapshot.documents
                        .firstOrNull { it.getBoolean("isDeleted") != true }
                        ?: return@addOnSuccessListener

                    val title = latestNotice.getString("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Latest notice"
                    val time = relativeTime(
                        latestNotice.getTimestamp("timestamp")?.toDate()?.time
                            ?: latestNotice.getTimestamp("createdAt")?.toDate()?.time
                    )

                    views.setTextViewText(
                        R.id.tvWidgetNoticeTitle,
                        title
                    )
                    views.setTextViewText(
                        R.id.tvWidgetNoticeTime,
                        time
                    )
                    views.setOnClickPendingIntent(
                        R.id.widgetNoticeRow,
                        createNoticePendingIntent(context, widgetId, latestNotice.id)
                    )

                    appWidgetManager.updateAppWidget(
                        widgetId, views
                    )
                }
        }

        private fun createMainPendingIntent(context: Context, widgetId: Int): PendingIntent {
            return PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createNoticesPendingIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("OPEN_TAB", "notices")
            }
            return PendingIntent.getActivity(
                context,
                widgetId + NOTICE_REQUEST_OFFSET,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createNoticePendingIntent(
            context: Context,
            widgetId: Int,
            noticeId: String
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("noticeId", noticeId)
                putExtra("OPEN_TAB", "notices")
            }
            return PendingIntent.getActivity(
                context,
                widgetId + NOTICE_REQUEST_OFFSET,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun relativeTime(millis: Long?): String {
            val timestamp = millis ?: return "Recently"
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
                diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
                else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
            }
        }

        private const val NOTICE_REQUEST_OFFSET = 10_000
    }
}
