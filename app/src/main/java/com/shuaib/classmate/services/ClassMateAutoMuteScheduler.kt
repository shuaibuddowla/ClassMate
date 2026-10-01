package com.shuaib.classmate.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Schedules the existing mute receiver from Supabase routine data only. */
object ClassMateAutoMuteScheduler {
    private const val PREFS = "classmate_auto_mute_alarms"

    suspend fun schedule(context: Context, batchId: String) {
        cancel(context)
        if (batchId.isBlank()) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val semesters = ClassMateAuthApi.rows("semesters",
            "select=id&batch_id=eq.$batchId&status=eq.active&limit=1")
        val semester = semesters.optJSONObject(0)?.optString("id") ?: return
        val offerings = ClassMateAuthApi.rows("semester_courses",
            "select=id&semester_id=eq.$semester")
        val ids = (0 until offerings.length()).map { offerings.getJSONObject(it).getString("id") }
        if (ids.isEmpty()) return
        val slots = ClassMateAuthApi.rows("routine_slots",
            "select=id,day_of_week,start_time,end_time&semester_course_id=in.(${ids.joinToString(",")})")
        val pendingCodes = mutableSetOf<String>()
        val today = LocalDate.now()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            val day = date.dayOfWeek.value % 7
            for (i in 0 until slots.length()) {
                val slot = slots.getJSONObject(i)
                if (slot.optInt("day_of_week") != day) continue
                scheduleOne(context, manager, slot, date, true)?.let { pendingCodes += it.toString() }
                scheduleOne(context, manager, slot, date, false)?.let { pendingCodes += it.toString() }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("codes", pendingCodes).apply()
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet("codes", emptySet()).orEmpty().forEach { raw ->
            val code = raw.toIntOrNull() ?: return@forEach
            for (action in listOf(AutoMuteReceiver.ACTION_MUTE, AutoMuteReceiver.ACTION_UNMUTE)) {
                val pending = PendingIntent.getBroadcast(context, code,
                    Intent(context, AutoMuteReceiver::class.java).apply { this.action = action },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) { manager.cancel(pending); pending.cancel() }
            }
        }
        prefs.edit().remove("codes").apply()
    }

    private fun scheduleOne(context: Context, manager: AlarmManager, slot: JSONObject,
                            date: LocalDate, start: Boolean): Int? {
        val time = runCatching { LocalTime.parse(slot.getString(if (start) "start_time" else "end_time")) }
            .getOrNull() ?: return null
        val whenMs = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (whenMs <= System.currentTimeMillis()) return null
        val action = if (start) AutoMuteReceiver.ACTION_MUTE else AutoMuteReceiver.ACTION_UNMUTE
        val code = "${slot.getString("id")}:${date}:$action".hashCode()
        val intent = Intent(context, AutoMuteReceiver::class.java).apply {
            this.action = action
            putExtra("period_id", slot.getString("id"))
        }
        val pending = PendingIntent.getBroadcast(context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(whenMs, pending), pending)
        return code
    }
}
