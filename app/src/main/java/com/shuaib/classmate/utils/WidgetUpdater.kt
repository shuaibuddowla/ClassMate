// com/shuaib/classmate/utils/WidgetUpdater.kt
package com.shuaib.classmate.utils

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.shuaib.classmate.widget.ClassMateWidget

object WidgetUpdater {

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, ClassMateWidget::class.java)
        )
        widgetIds.forEach { id ->
            ClassMateWidget.updateWidget(context, manager, id)
        }
    }
}
