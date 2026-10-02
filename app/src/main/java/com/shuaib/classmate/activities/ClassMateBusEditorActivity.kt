package com.shuaib.classmate.activities

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalTime

class ClassMateBusEditorActivity : ClassMateScheduleEditor() {
    private var draft: (() -> JSONObject)? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ClassMateAuthApi.attach(applicationContext)
        val existing = intent.getStringExtra("bus")?.let { JSONObject(it) }
        val initial = state?.getString("draft")?.let(::JSONObject) ?: existing
        page(if (existing == null) "Add bus schedule" else "Edit bus schedule")
        form.label("Set the route, departure time and operating days.")
        val route = form.field("Route name").apply { setText(initial?.optString("route_name")) }
        val origin = form.field("From").apply { setText(initial?.optString("origin")) }
        val destination = form.field("To").apply { setText(initial?.optString("destination")) }
        var time = runCatching { LocalTime.parse(initial?.optString("departure_time")) }.getOrNull() ?: LocalTime.of(8, 0)
        form.panel.addView(timeButton("Departure", { time }, { time = it }), LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
        form.label("Operating days")
        val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val old = initial?.optJSONArray("weekdays")
        val checks = days.mapIndexed { index, day -> MaterialCheckBox(this).apply {
            text = day; setTextColor(getColor(com.shuaib.classmate.R.color.cm_text_primary))
            isChecked = old == null || (0 until old.length()).any { old.optInt(it) == index }
            form.panel.addView(this)
        } }
        val notes = form.field("Notes (optional)", true).apply { setText(initial?.optString("notes")) }
        val active = MaterialSwitch(this).apply { text = "Active route"; isChecked = initial?.optBoolean("active") ?: true; form.panel.addView(this) }
        draft = {
            val weekdays = JSONArray(); checks.forEachIndexed { i, c -> if(c.isChecked) weekdays.put(i) }
            JSONObject().put("route_name", route.text.toString()).put("origin", origin.text.toString()).put("destination", destination.text.toString())
                .put("departure_time", time.toString()).put("weekdays", weekdays).put("notes", notes.text.toString()).put("active", active.isChecked)
        }
        status = form.status(); save.text = "Save bus schedule"
        save.setOnClickListener {
            when {
                route.text.isNullOrBlank() -> route.error = "Enter a route name"
                origin.text.isNullOrBlank() -> origin.error = "Enter the starting point"
                destination.text.isNullOrBlank() -> destination.error = "Enter the destination"
                checks.none { it.isChecked } -> { status.visibility = android.view.View.VISIBLE; status.text = "Select at least one operating day." }
                else -> perform({
                    val weekdays = JSONArray(); checks.forEachIndexed { i, c -> if(c.isChecked) weekdays.put(i) }
                    val saved = ClassMateAuthApi.rpc("save_bus_schedule", JSONObject().put("target_id", existing?.optString("id") ?: JSONObject.NULL)
                        .put("target_route", route.text.toString().trim()).put("target_departure", time.toString())
                        .put("target_origin", origin.text.toString().trim()).put("target_destination", destination.text.toString().trim())
                        .put("target_weekdays", weekdays).put("target_notes", notes.text.toString().trim()).put("target_active", active.isChecked))
                    ClassMateAcademicCache.updateSchedule(this, intent.getStringExtra("profile_id").orEmpty(), intent.getStringExtra("batch_id").orEmpty(), "bus", saved)
                }, { finish() })
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); draft?.let { out.putString("draft", it().toString()) }
    }
}
