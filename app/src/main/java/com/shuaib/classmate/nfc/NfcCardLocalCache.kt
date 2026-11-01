package com.shuaib.classmate.nfc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * High-speed offline cache for ClassMate RFID/NFC cards.
 * Ensures instant (<5ms) offline identification of students even without physical NDEF EEPROM data.
 */
object NfcCardLocalCache {

    private const val PREFS_NAME = "classmate_nfc_card_cache"

    fun saveCard(context: Context, payload: NfcCardManager.StudentCardPayload, avatarUrl: String = "", attendancePct: Double? = null) {
        val cleanUid = payload.uid.trim().lowercase(Locale.US)
        if (cleanUid.isEmpty()) return

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("uid", cleanUid)
                put("id", payload.studentId)
                put("name", payload.name)
                put("roll", payload.roll)
                put("batch", payload.batch)
                put("dept", payload.dept)
                put("phone", payload.phone)
                put("blood", payload.blood)
                put("avatar_url", avatarUrl)
                if (attendancePct != null) put("attendance_pct", attendancePct)
            }
            prefs.edit().putString(cleanUid, json.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getCard(context: Context, uid: String): NfcCardManager.StudentCardPayload? {
        val cleanUid = uid.trim().lowercase(Locale.US)
        if (cleanUid.isEmpty()) return null

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(cleanUid, null) ?: return null
            val json = JSONObject(raw)

            return NfcCardManager.StudentCardPayload(
                uid = json.optString("uid", cleanUid).uppercase(Locale.US),
                studentId = json.optString("id"),
                name = json.optString("name"),
                roll = json.optString("roll"),
                batch = json.optString("batch"),
                dept = json.optString("dept"),
                phone = json.optString("phone"),
                blood = json.optString("blood")
            )
        } catch (_: Exception) {
            return null
        }
    }

    fun getCachedAvatarUrl(context: Context, uid: String): String {
        val cleanUid = uid.trim().lowercase(Locale.US)
        if (cleanUid.isEmpty()) return ""
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(cleanUid, null) ?: return ""
            JSONObject(raw).optString("avatar_url", "")
        } catch (_: Exception) { "" }
    }

    fun getCachedAttendancePct(context: Context, uid: String): Double? {
        val cleanUid = uid.trim().lowercase(Locale.US)
        if (cleanUid.isEmpty()) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(cleanUid, null) ?: return null
            val obj = JSONObject(raw)
            if (obj.has("attendance_pct") && !obj.isNull("attendance_pct")) obj.getDouble("attendance_pct") else null
        } catch (_: Exception) { null }
    }

    /**
     * Batch caches all student cards loaded in ClassMateAdminAttendanceActivity or batch directory.
     */
    fun cacheRoster(context: Context, students: List<JSONObject>, defaultBatch: String = "") {
        if (students.isEmpty()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (s in students) {
                val uid = s.optString("rfid_card_uid").trim().lowercase(Locale.US)
                if (uid.isNotEmpty() && uid != "null") {
                    val json = JSONObject().apply {
                        put("uid", uid)
                        put("id", s.optString("student_id"))
                        put("name", s.optString("full_name"))
                        put("roll", s.optString("roll"))
                        put("batch", defaultBatch)
                        put("dept", "MBSTU")
                        put("phone", s.optString("phone", s.optString("mobile_number", "")))
                        put("blood", s.optString("blood_group", ""))
                        put("avatar_url", s.optString("avatar_url", ""))
                    }
                    editor.putString(uid, json.toString())
                }
            }
            editor.apply()
        } catch (_: Exception) {}
    }
}
