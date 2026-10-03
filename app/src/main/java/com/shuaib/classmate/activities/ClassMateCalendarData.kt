package com.shuaib.classmate.activities

import android.content.Context
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal class ClassMateCalendarData(private val context: Context, private val user: () -> String) {
    private val refreshMutex = Mutex()
    fun snapshot(year: Int) = ClassMateAcademicCache.read(context, user(), "university", "calendar_$year")
    fun events(year: Int): List<JSONObject> {
        val array = snapshot(year)?.optJSONArray("events") ?: JSONArray()
        return (0 until array.length()).map(array::getJSONObject)
    }
    suspend fun refresh(year: Int, force: Boolean = false) = refreshMutex.withLock {
        val old = snapshot(year)
        if (!ClassMateAcademicCache.online(context)) return@withLock
        if (!force && old != null && System.currentTimeMillis() - old.optLong("saved_at") < 300_000) return@withLock
        val data = ClassMateAuthApi.rows("academic_calendar_events",
            "select=id,title,start_date,end_date,scope,provisional,source_note&start_date=lte.$year-12-31&end_date=gte.$year-01-01&order=start_date,title&limit=500")
        val publication = ClassMateAuthApi.rows("academic_calendar_publications", "select=title,notes,source_note&year=eq.$year&limit=1").optJSONObject(0)
        ClassMateAcademicCache.save(context, user(), "university", "calendar_$year",
            JSONObject().put("events", data).put("publication", publication).put("saved_at", System.currentTimeMillis()).put("synced_at", System.currentTimeMillis()))
    }
    fun kindFor(date: LocalDate): String {
        val today = events(date.year).filter { includes(it, date) }
        return ClassMateBusDays.kindFor(date, calendarHoliday = today.any { it.optString("scope") == "university" },
            calendarWorkingDay = today.any { it.optString("scope") == "working_day" })
    }
    fun classClosure(date: LocalDate): String? {
        val matching = events(date.year).filter { includes(it, date) }
        if (!ClassMateCalendarRules.classesClosed(date, matching.map { it.getString("scope") }.toSet())) return null
        return matching.filter { it.getString("scope") in setOf("classes", "university") }
            .map { it.getString("title") }.distinct().joinToString(" · ").ifBlank { "Weekly holiday" }
    }
    fun applyChange(saved: JSONObject?, previous: JSONObject?) {
        val bounds = listOfNotNull(saved, previous)
        if (bounds.isEmpty()) return
        val years = bounds.flatMap { LocalDate.parse(it.getString("start_date")).year..LocalDate.parse(it.getString("end_date")).year }.toSet()
        val id = saved?.getString("id") ?: previous!!.getString("id")
        years.forEach { year ->
            snapshot(year)?.let { old ->
                val kept = JSONArray()
                events(year).filter { it.getString("id") != id }.forEach { kept.put(it) }
                if (saved != null && !LocalDate.parse(saved.getString("end_date")).isBefore(LocalDate.of(year, 1, 1)) &&
                    !LocalDate.parse(saved.getString("start_date")).isAfter(LocalDate.of(year, 12, 31))) kept.put(saved)
                ClassMateAcademicCache.save(context, user(), "university", "calendar_$year", old.put("synced_at", old.optLong("synced_at", old.optLong("saved_at"))).put("events", kept).put("saved_at", 0L))
            }
        }
    }
    companion object {
        fun includes(event: JSONObject, date: LocalDate): Boolean =
            !date.isBefore(LocalDate.parse(event.getString("start_date"))) &&
                !date.isAfter(LocalDate.parse(event.getString("end_date")))
    }
}
