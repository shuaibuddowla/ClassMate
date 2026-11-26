package com.shuaib.classmate.activities

import android.content.Context
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

internal class ClassMateCalendarData(
    private val context: Context,
    private val weekendDays: () -> Set<DayOfWeek> = { ClassMateAcademicCache.weekendDays(context) },
    private val isRu: () -> Boolean = { false },
    private val user: () -> String
) {
    internal data class ParsedEvent(
        val raw: JSONObject,
        val start: LocalDate,
        val end: LocalDate,
        val scope: String,
        val title: String,
        val provisional: Boolean
    ) {
        fun includes(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    }

    private data class CachedYear(
        val snapshot: JSONObject?,
        val events: List<ParsedEvent>
    )

    private val refreshMutex = Mutex()
    private val memoryCache = mutableMapOf<Int, CachedYear>()
    private var cachedUser: String? = null

    private fun ensureUserCache(): MutableMap<Int, CachedYear> {
        val currentUser = user()
        if (cachedUser != currentUser) {
            memoryCache.clear()
            cachedUser = currentUser
        }
        return memoryCache
    }

    private fun loadYear(year: Int): CachedYear {
        val cache = ensureUserCache()
        cache[year]?.let { return it }
        val snap = ClassMateAcademicCache.read(context, user(), "university", "calendar_$year")
        val array = snap?.optJSONArray("events") ?: JSONArray()
        val parsed = ArrayList<ParsedEvent>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val start = runCatching { LocalDate.parse(obj.getString("start_date")) }.getOrNull() ?: continue
            val end = runCatching { LocalDate.parse(obj.getString("end_date")) }.getOrNull() ?: start
            parsed.add(
                ParsedEvent(
                    raw = obj,
                    start = start,
                    end = end,
                    scope = obj.optString("scope"),
                    title = obj.optString("title"),
                    provisional = obj.optBoolean("provisional")
                )
            )
        }
        return CachedYear(snap, parsed).also { cache[year] = it }
    }

    fun snapshot(year: Int): JSONObject? = loadYear(year).snapshot
    fun events(year: Int): List<JSONObject> = loadYear(year).events.map { it.raw }
    fun parsedEvents(year: Int): List<ParsedEvent> = loadYear(year).events

    fun isFresh(year: Int): Boolean {
        val snap = snapshot(year) ?: return false
        return System.currentTimeMillis() - snap.optLong("saved_at") < 300_000
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
        ensureUserCache().remove(year)
    }
    fun kindFor(date: LocalDate, yearEvents: List<ParsedEvent> = parsedEvents(date.year), weekends: Set<DayOfWeek> = weekendDays()): String {
        var calHoliday = false
        var calWorking = false
        for (ev in yearEvents) {
            if (ev.includes(date)) {
                if (ev.scope == "university") calHoliday = true
                else if (ev.scope == "working_day") calWorking = true
            }
        }
        return ClassMateBusDays.kindFor(
            date,
            calendarHoliday = calHoliday,
            calendarWorkingDay = calWorking,
            weekendDays = weekends,
            isRu = isRu()
        )
    }
    fun classClosure(date: LocalDate, yearEvents: List<ParsedEvent> = parsedEvents(date.year), weekends: Set<DayOfWeek> = weekendDays()): String? {
        val matching = yearEvents.filter { it.includes(date) }
        if (!ClassMateCalendarRules.classesClosed(date, matching.map { it.scope }.toSet(), weekendDays = weekends, isRu = isRu())) return null
        return matching.filter { it.scope == "classes" || it.scope == "university" }
            .map { it.title }.distinct().joinToString(" · ").ifBlank { "Weekly holiday" }
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
                ensureUserCache().remove(year)
            }
        }
    }
    companion object {
        fun includes(event: JSONObject, date: LocalDate): Boolean =
            !date.isBefore(LocalDate.parse(event.getString("start_date"))) &&
                !date.isAfter(LocalDate.parse(event.getString("end_date")))
    }
}
