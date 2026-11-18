package com.shuaib.classmate.activities

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shuaib.classmate.BuildConfig
import org.json.JSONObject

/** App-private, account/batch separated snapshots. Never used to authorize writes. */
internal object ClassMateAcademicCache {
    private fun prefs(context: Context) = context.getSharedPreferences("classmate_academic_cache_${BuildConfig.CLASSMATE_ENV}", Context.MODE_PRIVATE)
    fun online(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    fun read(context: Context, user: String, batch: String, kind: String): JSONObject? = runCatching {
        prefs(context).getString("$user:$batch:$kind", null)?.let(::JSONObject)
    }.getOrNull()
    fun save(context: Context, user: String, batch: String, kind: String, data: JSONObject) {
        if (!com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi.hasSavedSession()) return
        prefs(context).edit().putString("$user:$batch:$kind", data.toString()).apply()
    }
    fun home(context: Context): JSONObject? = read(context, "identity", "home", "profile")
    fun institution(context: Context): JSONObject? = read(context, "identity", "home", "institution")
    fun renameCourse(context: Context, offeringIds: Set<String>, title: String, code: String) {
        val storage = prefs(context)
        val edit = storage.edit()
        storage.all.forEach { (key, value) ->
            if (!key.endsWith(":routine")) return@forEach
            val data = runCatching { JSONObject(value as? String ?: return@forEach) }.getOrNull() ?: return@forEach
            var changed = false
            data.optJSONObject("names")?.let { names ->
                offeringIds.forEach { id -> if (names.has(id)) { names.put(id, title); changed = true } }
            }
            data.optJSONArray("details")?.let { details ->
                for (i in 0 until details.length()) details.optJSONObject(i)?.let { row ->
                    if (row.optString("semester_course_id") in offeringIds) {
                        row.put("course_title", title).put("course_code", code); changed = true
                    }
                }
            }
            if (changed) edit.putString(key, data.put("saved_at", 0L).toString())
        }
        edit.apply()
    }
    fun saveHome(context: Context, profile: JSONObject, batch: String, label: String, institution: JSONObject? = null) {
        if (profile.optString("verification_status") == "active" && batch.isNotBlank()) {
            save(context, "identity", "home", "profile", JSONObject().put("profile", profile).put("batch", batch).put("label", label))
            val effectiveInst = institution ?: institution(context)
            if (effectiveInst != null) {
                save(context, "identity", "home", "institution", effectiveInst)
            }
        }
    }
    fun weekendDays(context: Context): Set<java.time.DayOfWeek> {
        val inst = institution(context)
        val arr = inst?.optJSONArray("weekend_days")
        if (arr != null && arr.length() > 0) {
            val days = mutableSetOf<java.time.DayOfWeek>()
            for (i in 0 until arr.length()) {
                val iso = arr.optInt(i)
                if (iso in 1..7) days.add(java.time.DayOfWeek.of(iso))
            }
            if (days.isNotEmpty()) return days
        }
        val email = home(context)?.optJSONObject("profile")?.optString("email").orEmpty().lowercase()
        return if (email.endsWith("@ru.ac.bd")) {
            setOf(java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY)
        } else {
            setOf(java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)
        }
    }
    fun hasBusTransit(context: Context): Boolean {
        val inst = institution(context)
        if (inst != null) return inst.optBoolean("has_bus_transit", false)
        val email = home(context)?.optJSONObject("profile")?.optString("email").orEmpty().lowercase()
        return !email.endsWith("@ru.ac.bd")
    }
    fun hasAcademicCalendar(context: Context): Boolean {
        val inst = institution(context)
        if (inst != null) return inst.optBoolean("has_academic_calendar", false)
        val email = home(context)?.optJSONObject("profile")?.optString("email").orEmpty().lowercase()
        return !email.endsWith("@ru.ac.bd")
    }
    fun supportContactUrl(context: Context): String {
        return institution(context)?.optString("support_contact_url").takeIf { !it.isNullOrBlank() }
            ?: "https://facebook.com/shuaibuddowla"
    }
    fun institutionShortName(context: Context): String {
        return institution(context)?.optString("short_name").takeIf { !it.isNullOrBlank() }
            ?: if (home(context)?.optJSONObject("profile")?.optString("email").orEmpty().lowercase().endsWith("@ru.ac.bd")) "RU" else "MBSTU"
    }
    fun institutionName(context: Context): String {
        return institution(context)?.optString("name").takeIf { !it.isNullOrBlank() }
            ?: if (home(context)?.optJSONObject("profile")?.optString("email").orEmpty().lowercase().endsWith("@ru.ac.bd")) "University of Rajshahi" else "Mawlana Bhashani Science and Technology University"
    }
    fun invalidateSchedules(context: Context, user: String, batch: String) {
        // Keep the last successful sync usable if the next refresh fails or goes offline.
        listOf("routine", "bus").forEach { kind ->
            read(context, user, batch, kind)?.let { save(context, user, batch, kind, it.put("saved_at", 0L)) }
        }
    }
    fun updateSchedule(context: Context, user: String, batch: String, kind: String, item: JSONObject?, deletedId: String? = null, courseName: String? = null) {
        val snapshot = read(context, user, batch, kind) ?: return
        val entries = snapshot.optJSONArray("entries") ?: org.json.JSONArray()
        val updated = org.json.JSONArray()
        for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { row ->
            if (row.optString("id") != (item?.optString("id") ?: deletedId)) updated.put(row)
        }
        item?.let { updated.put(it) }
        if (courseName != null && item != null) {
            val names = snapshot.optJSONObject("names") ?: JSONObject()
            names.put(item.optString("semester_course_id"), courseName); snapshot.put("names", names)
        }
        save(context, user, batch, kind, snapshot.put("entries", updated).put("saved_at", 0L))
    }
    fun removeResourceNotice(context: Context, user: String, batch: String, resource: String) {
        val snapshot = read(context, user, batch, "notices") ?: return
        val feed = snapshot.optJSONArray("feed") ?: return
        val updated = org.json.JSONArray()
        for (i in 0 until feed.length()) feed.optJSONObject(i)?.let { if (it.optString("resource_id") != resource) updated.put(it) }
        save(context, user, batch, "notices", snapshot.put("feed", updated))
    }
    fun removeCourse(context: Context, offeringIds: Set<String>) {
        val storage=prefs(context)
        val edit=storage.edit()
        storage.all.forEach { (key,value) ->
            if(!key.endsWith(":routine") && !key.endsWith(":notices")) return@forEach
            val data=runCatching { JSONObject(value as? String ?: return@forEach) }.getOrNull() ?: return@forEach
            listOf("entries","details","changes","feed").forEach { field ->
                data.optJSONArray(field)?.let { old ->
                    val kept=org.json.JSONArray()
                    for(i in 0 until old.length()) old.optJSONObject(i)?.let { row ->
                        if(row.optString("semester_course_id") !in offeringIds) kept.put(row)
                    }
                    data.put(field,kept)
                }
            }
            data.optJSONObject("names")?.let { names -> offeringIds.forEach { names.remove(it) } }
            edit.putString(key,data.put("saved_at",0L).toString())
        }
        edit.apply()
    }
    fun clear(context: Context) { prefs(context).edit().clear().apply() }
}
