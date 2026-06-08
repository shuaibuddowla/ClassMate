package com.shuaib.classmate.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.shuaib.classmate.R
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.local.TimetableEntity
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.SubjectVisuals
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class WidgetTimetableService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetTimetableFactory(applicationContext)
    }
}

class WidgetTimetableFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var periodsList: List<TimetableEntity> = emptyList()

    override fun onCreate() {
        // No-op
    }

    override fun onDataSetChanged() {
        val db = ClassMateDatabase.getInstance(context)
        val today = DateHelper.todayDayString()
        periodsList = db.timetableDao().getPeriodsSync(today)
    }

    override fun onDestroy() {
        periodsList = emptyList()
    }

    override fun getCount(): Int {
        return periodsList.size
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= periodsList.size) return null
        val entity = periodsList[position]
        
        val views = RemoteViews(context.packageName, R.layout.item_widget_period)
        
        // Bind subject
        views.setTextViewText(R.id.tvWidgetPeriodSubject, entity.subject)
        
        // Format times
        val startTimeFormatted = formatTo12Hour(entity.startTime)
        val endTimeFormatted = formatTo12Hour(entity.endTime)
        views.setTextViewText(R.id.tvWidgetPeriodTime, "$startTimeFormatted - $endTimeFormatted")
        
        // Determine if lab session
        val isLabSession = entity.subject.trim().endsWith("lab", ignoreCase = true) || 
                           entity.subject.trim().endsWith("labs", ignoreCase = true)
        
        // Build metadata text (Teacher & Room/Location)
        val todayStr = DateHelper.today()
        val isCancelled = entity.cancelDate == todayStr
        val isSubstitute = entity.substituteDate == todayStr
        
        val metaText = when {
            isCancelled -> "Class has been cancelled"
            isSubstitute -> "Substitute: ${entity.substituteTeacher}"
            else -> "Teacher: ${entity.teacher}"
        }
        views.setTextViewText(R.id.tvWidgetPeriodMeta, metaText)
        
        // Bind status indicator and badges
        when {
            isCancelled -> {
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundResource", R.drawable.bg_badge_pill)
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundTint", context.getColor(R.color.cm_error))
                
                views.setTextViewText(R.id.tvWidgetPeriodBadge, "CANCELLED")
                views.setInt(R.id.tvWidgetPeriodBadge, "setBackgroundResource", R.drawable.bg_badge_red)
                views.setTextColor(R.id.tvWidgetPeriodBadge, context.getColor(R.color.cm_error))
            }
            isSubstitute -> {
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundResource", R.drawable.bg_badge_pill)
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundTint", context.getColor(R.color.cm_warning))
                
                views.setTextViewText(R.id.tvWidgetPeriodBadge, "SUBSTITUTE")
                views.setInt(R.id.tvWidgetPeriodBadge, "setBackgroundResource", R.drawable.bg_badge_purple)
                views.setTextColor(R.id.tvWidgetPeriodBadge, context.getColor(R.color.cm_warning))
            }
            else -> {
                // Normal class or lab
                val accentColor = getSubjectAccentColor(entity.subject)
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundResource", R.drawable.bg_badge_pill)
                views.setInt(R.id.vWidgetPeriodIndicator, "setBackgroundTint", accentColor)
                
                if (isLabSession) {
                    views.setTextViewText(R.id.tvWidgetPeriodBadge, "LAB")
                    views.setInt(R.id.tvWidgetPeriodBadge, "setBackgroundResource", R.drawable.bg_badge_green)
                    views.setTextColor(R.id.tvWidgetPeriodBadge, context.getColor(R.color.cm_file_lab_text))
                } else {
                    views.setTextViewText(R.id.tvWidgetPeriodBadge, "CLASS")
                    views.setInt(R.id.tvWidgetPeriodBadge, "setBackgroundResource", R.drawable.bg_badge_blue)
                    views.setTextColor(R.id.tvWidgetPeriodBadge, context.getColor(R.color.cm_primary))
                }
            }
        }
        
        // Setup fill-in intent for clicking a period (opening MainActivity)
        val fillInIntent = Intent().apply {
            putExtra("periodId", entity.id)
        }
        views.setOnClickFillInIntent(R.id.layoutWidgetPeriodRoot, fillInIntent)
        
        return views
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }

    private fun formatTo12Hour(time24: String): String {
        return try {
            val time = LocalTime.parse(time24)
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
            time.format(formatter)
        } catch (e: Exception) {
            time24
        }
    }

    private fun getSubjectAccentColor(subject: String): Int {
        return try {
            SubjectVisuals.forSubject(subject).startColor
        } catch (e: Exception) {
            context.getColor(R.color.cm_primary)
        }
    }
}
