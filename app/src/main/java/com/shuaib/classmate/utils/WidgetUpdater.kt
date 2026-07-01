// com/shuaib/classmate/utils/WidgetUpdater.kt
package com.shuaib.classmate.utils

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.firebase.firestore.Source
import com.shuaib.classmate.R
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.widget.ClassMateWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context, syncTodayTimetable: Boolean = true) {
        val appContext = context.applicationContext
        refreshFromCache(appContext)

        if (!syncTodayTimetable) return

        scope.launch {
            runCatching {
                TimetableRepository.getInstance(appContext)
                    .syncDayFromFirestore(DateHelper.todayDayString(), Source.DEFAULT)
            }.onFailure {
                Log.e("WidgetUpdater", "Timetable widget sync failed: ${it.message}")
            }
            refreshFromCache(appContext)
        }
    }

    private fun refreshFromCache(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, ClassMateWidget::class.java)
        )
        widgetIds.forEach { id ->
            ClassMateWidget.updateWidget(context, manager, id)
        }
        if (widgetIds.isNotEmpty()) {
            manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.lvWidgetPeriods)
        }
    }
}
