package com.shuaib.classmate.activities

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Shared university calendar. All event content comes from Supabase or its last sync. */
internal class ClassMateCalendarUi(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val data: ClassMateCalendarData,
    private val owner: () -> Boolean,
) {
    val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false }
    private val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(112)) }
    private var month = YearMonth.now()
    private var selected: LocalDate? = null
    private var request = 0
    private var error: String? = null
    init { scroll.addView(panel) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = activity.getColor(id)
    private fun label(value: String, size: Float = 14f, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color(R.color.cm_text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = value; isAllCaps = false; textSize = 13f; cornerRadius = dp(14); setOnClickListener { action() }
    }
    fun show(force: Boolean = false) {
        render()
        val target = month; val ticket = ++request
        scope.launch {
            val failure = runCatching { data.refresh(target.year, force) }.exceptionOrNull()?.let { "Could not refresh. Showing the last synced calendar." }
            if (ticket == request && target == month && scroll.isAttachedToWindow) { error = failure; render() }
        }
    }
    private fun move(offset: Long) { month = month.plusMonths(offset); selected = null; error = null; show() }
    private fun render() {
        panel.removeAllViews()
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { move(-1) }.apply { contentDescription = "Previous month" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 21f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("›") { move(1) }.apply { contentDescription = "Next month" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)
        val tools = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.addView(button("Today") { month = YearMonth.now(); selected = LocalDate.now(); show() })
        tools.addView(button("Refresh") { show(true) })
        if (owner()) tools.addView(button("Add event") { edit(null) })
        panel.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(tools) })
        val events = data.events(month.year)
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(12)) }
        val names = LinearLayout(activity)
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach {
            names.addView(label(it, 12f, true).apply { gravity = Gravity.CENTER; setTextColor(color(R.color.cm_text_secondary)) }, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
        grid.addView(names)
        val first = month.atDay(1).dayOfWeek.value % 7
        val cells = ((first + month.lengthOfMonth() + 6) / 7) * 7
        for (week in 0 until cells / 7) {
            val row = LinearLayout(activity)
            for (day in 0..6) {
                val number = week * 7 + day - first + 1
                val cell = label("", 16f, true).apply { gravity = Gravity.CENTER }
                if (number in 1..month.lengthOfMonth()) {
                    val date = month.atDay(number)
                    val matches = events.filter { ClassMateCalendarData.includes(it, date) }
                    val closed = data.kindFor(date) == "closed"
                    val classOnly = matches.any { it.optString("scope") == "classes" }
                    val hasEvent = matches.isNotEmpty()
                    cell.text = "$number" + if (hasEvent) "\n•" else ""
                    cell.setTextColor(when {
                        date == selected -> Color.WHITE
                        closed -> color(R.color.cm_error)
                        classOnly -> Color.rgb(26, 155, 107)
                        else -> color(R.color.cm_text_primary)
                    })
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (date == selected) color(R.color.cm_primary) else Color.TRANSPARENT)
                        if (date == LocalDate.now() && date != selected) setStroke(dp(1), color(R.color.cm_primary))
                    }
                    cell.contentDescription = "$date${if (closed) ", offices closed" else ""}${if (classOnly) ", classes closed" else ""}. ${matches.joinToString { it.optString("title") }}"
                    cell.setOnClickListener { selected = date; render() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp((52 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt()), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            grid.addView(row)
        }
        panel.addView(grid)
        panel.addView(label("Red · offices closed    Green · classes only    • event", 12f).apply { setTextColor(color(R.color.cm_text_secondary)) })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(selected?.format(DateTimeFormatter.ofPattern("d MMMM")) ?: "This month", 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected != null) heading.addView(button("All events") { selected = null; render() })
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val visible = events.filter { event -> selected?.let { ClassMateCalendarData.includes(event, it) } ?: (
            !LocalDate.parse(event.getString("end_date")).isBefore(month.atDay(1)) &&
                !LocalDate.parse(event.getString("start_date")).isAfter(month.atEndOfMonth())) }
        if (visible.isEmpty()) panel.addView(label(when {
            data.snapshot(month.year) == null -> when { error != null -> "Calendar unavailable. Tap Refresh to retry."; ClassMateAcademicCache.online(activity) -> "Loading calendar…"; else -> "Connect to sync this year's calendar." }
            selected != null && data.kindFor(selected!!) == "closed" -> "Weekly holiday · offices closed"
            events.isEmpty() && data.snapshot(month.year)?.optJSONObject("publication") == null -> "No academic calendar published for ${month.year}. Weekly holidays are shown."
            else -> "No calendar events for this ${if (selected == null) "month" else "day"}."
        }).apply { setPadding(0, dp(16), 0, dp(16)) })
        visible.groupBy { it.getString("title") }.forEach { (title, group) ->
            val card = MaterialCardView(activity).apply {
                radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
                setCardBackgroundColor(color(R.color.cm_surface)); strokeColor = color(R.color.cm_border_glass)
            }
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            content.addView(label(title, 16f, true))
            group.forEach { event ->
                val start = LocalDate.parse(event.getString("start_date")); val end = LocalDate.parse(event.getString("end_date"))
                val fmt = DateTimeFormatter.ofPattern("d MMM")
                val dates = if (start == end) start.format(fmt) else "${start.format(fmt)} – ${end.format(fmt)}"
                val category = when (event.getString("scope")) {
                    "classes" -> "Classes closed"
                    "university" -> if (group.any { it.optString("scope") == "classes" }) "Offices closed" else "Classes & offices closed"
                    "working_day" -> "Working day"
                    else -> "Observance"
                }
                content.addView(label("$dates · $category", 13f).apply { setTextColor(color(R.color.cm_text_secondary)); setPadding(0, dp(6), 0, 0) })
                if (owner()) content.addView(button("Edit") { edit(event) })
            }
            if (group.any { it.optBoolean("provisional") }) content.addView(label("Date depends on moon sighting", 12f).apply { setTextColor(color(R.color.cm_text_secondary)); setPadding(0, dp(6), 0, 0) })
            card.addView(content); panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val snapshot = data.snapshot(month.year)
        snapshot?.optJSONObject("publication")?.let { publication ->
            panel.addView(button("Calendar notes") {
                MaterialAlertDialogBuilder(activity).setTitle(publication.optString("title"))
                    .setMessage(publication.optString("notes")).setPositiveButton("Done", null).show()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        error?.let { panel.addView(label(it, 12f)) }
        snapshot?.optLong("synced_at", snapshot.optLong("saved_at"))?.let { time ->
            panel.addView(label("${if (ClassMateAcademicCache.online(activity)) "Last synced" else "Offline · last synced"} ${Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}", 12f).apply { setTextColor(color(R.color.cm_text_secondary)); setPadding(0, dp(12), 0, 0) })
        }
    }
    private fun edit(event: JSONObject?) {
        if (!owner()) return
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val title = EditText(activity).apply { hint = "Event name"; setText(event?.optString("title")); maxLines = 3 }
        form.addView(title)
        var start = event?.let { LocalDate.parse(it.getString("start_date")) } ?: month.atDay(1)
        var end = event?.let { LocalDate.parse(it.getString("end_date")) } ?: start
        fun choose(date: LocalDate, chosen: (LocalDate) -> Unit) {
            MaterialDatePicker.Builder.datePicker().setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).build().also { picker ->
                picker.addOnPositiveButtonClickListener { chosen(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                picker.show(activity.supportFragmentManager, "calendar_date")
            }
        }
        val from = button("From · $start") {}; val to = button("To · $end") {}
        from.setOnClickListener { choose(start) { start = it; from.text = "From · $start"; if (end.isBefore(start)) { end = start; to.text = "To · $end" } } }
        to.setOnClickListener { choose(end) { end = it; to.text = "To · $end" } }
        form.addView(from); form.addView(to)
        val scopes = listOf("university", "classes", "observance", "working_day")
        val type = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Classes & offices closed", "Classes only", "Observance · no closure", "Exceptional working day"))
            setSelection(scopes.indexOf(event?.optString("scope") ?: "university").coerceAtLeast(0))
        }
        form.addView(type)
        val status = label("", 12f); form.addView(status)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (event == null) "Add calendar event" else "Edit calendar event")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null)
            .apply { if (event != null) setNeutralButton("Delete", null) }.create()
        dialog.setOnShowListener {
            var busy = false
            fun submit(delete: Boolean) {
                if (busy) return
                if (!delete && (title.text.toString().trim().isEmpty() || end.isBefore(start))) { status.text = "Enter a name and a valid date range."; return }
                busy = true; dialog.setCancelable(false); dialog.getButton(-2).isEnabled = false; dialog.getButton(-1).isEnabled = false; dialog.getButton(-3)?.isEnabled = false
                scope.launch {
                    try {
                        val saved = if (delete) {
                            ClassMateAuthApi.rpcText("delete_calendar_event", JSONObject().put("target_id", event!!.getString("id")))
                            null
                        } else JSONObject(ClassMateAuthApi.rpcText("save_calendar_event", JSONObject().put("target_id", event?.optString("id") ?: JSONObject.NULL)
                            .put("target_title", title.text.toString().trim()).put("target_start", start.toString()).put("target_end", end.toString()).put("target_scope", scopes[type.selectedItemPosition])))
                        data.applyChange(saved, event)
                        // A successful write stays successful even if the follow-up sync is offline.
                        runCatching { data.refresh(month.year, true) }
                        dialog.dismiss(); show()
                    } catch (e: Exception) { status.text = e.message ?: "Could not save. Try again." }
                    finally { busy = false; dialog.setCancelable(true); dialog.getButton(-2).isEnabled = true; dialog.getButton(-1).isEnabled = true; dialog.getButton(-3)?.isEnabled = true }
                }
            }
            dialog.getButton(-1).setOnClickListener { submit(false) }
            dialog.getButton(-3)?.setOnClickListener {
                MaterialAlertDialogBuilder(activity).setTitle("Delete this event?").setMessage("This changes the calendar for every batch.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> submit(true) }.show()
            }
        }
        dialog.show()
    }
}
