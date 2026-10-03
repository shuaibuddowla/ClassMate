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
    private fun icon(drawable: Int, description: String, action: () -> Unit) = androidx.appcompat.widget.AppCompatImageButton(activity).apply {
        setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.cm_primary))
        contentDescription = description; setPadding(dp(12), dp(12), dp(12), dp(12))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.cm_primary_container)),
            GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color(R.color.cm_primary_soft)) }, null)
        setOnClickListener { action() }
    }
    private fun surface() = MaterialCardView(activity).apply {
        radius = dp(24).toFloat(); cardElevation = dp(1).toFloat(); strokeWidth = dp(1)
        setCardBackgroundColor(color(R.color.cm_surface)); strokeColor = color(R.color.cm_border_glass)
    }
    private fun quiet(value: String, action: () -> Unit) = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = value; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
        insetTop = 0; insetBottom = 0; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action() }
    }
    private fun render() {
        panel.removeAllViews()
        val events = data.events(month.year)
        val monthEvents = events.filter { !LocalDate.parse(it.getString("end_date")).isBefore(month.atDay(1)) &&
            !LocalDate.parse(it.getString("start_date")).isAfter(month.atEndOfMonth()) }
        val calendar = surface()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(12), dp(12)) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val monthTitle = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, 0) }
        monthTitle.addView(label(month.format(DateTimeFormatter.ofPattern("MMMM")), 22f, true))
        monthTitle.addView(label("${month.year} · ${monthEvents.map { it.getString("title") }.distinct().size} events", 12f).apply { setTextColor(color(R.color.cm_text_disabled)) })
        monthTitle.contentDescription = "Choose month or year"
        monthTitle.setOnClickListener {
            MaterialDatePicker.Builder.datePicker().setTitleText("Jump to a date").setSelection((selected ?: month.atDay(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).build().also { picker ->
                picker.addOnPositiveButtonClickListener { value -> selected = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate(); month = YearMonth.from(selected); show() }
                picker.show(activity.supportFragmentManager, "calendar_jump")
            }
        }
        header.addView(monthTitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(quiet("Today") { month = YearMonth.now(); selected = LocalDate.now(); scroll.scrollTo(0, 0); show() }, LinearLayout.LayoutParams(-2, dp(48)))
        header.addView(icon(R.drawable.ic_chevron_left, "Previous month") { move(-1); scroll.scrollTo(0, 0) }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(icon(R.drawable.ic_chevron_right, "Next month") { move(1); scroll.scrollTo(0, 0) }, LinearLayout.LayoutParams(dp(44), dp(48)).apply { leftMargin = dp(4) })
        content.addView(header)
        val names = LinearLayout(activity).apply { setPadding(0, dp(14), 0, dp(4)) }
        listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { index, name ->
            names.addView(label(name, 11f, true).apply { gravity = Gravity.CENTER; setTextColor(color(R.color.cm_text_disabled)); contentDescription = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")[index] }, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        content.addView(names)
        val first = month.atDay(1).dayOfWeek.value % 7
        val cells = ((first + month.lengthOfMonth() + 6) / 7) * 7
        for (week in 0 until cells / 7) {
            val row = LinearLayout(activity)
            for (day in 0..6) {
                val number = week * 7 + day - first + 1
                val cell = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                if (number in 1..month.lengthOfMonth()) {
                    val date = month.atDay(number)
                    val matches = events.filter { ClassMateCalendarData.includes(it, date) }
                    val closed = data.kindFor(date) == "closed"
                    val classOnly = matches.any { it.optString("scope") == "classes" }
                    val accent = when { closed -> color(R.color.cm_notice_cancel_text); classOnly -> color(R.color.cm_success); else -> color(R.color.cm_primary) }
                    cell.addView(label("$number", 15f, true).apply { gravity = Gravity.CENTER; setTextColor(if (date == selected) Color.WHITE else if (closed || classOnly) accent else color(R.color.cm_text_primary)) })
                    val dot = View(activity).apply { visibility = if (matches.isEmpty()) View.INVISIBLE else View.VISIBLE; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (date == selected) Color.WHITE else accent) } }
                    cell.addView(dot, LinearLayout.LayoutParams(dp(4), dp(4)).apply { topMargin = dp(3) })
                    cell.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.cm_primary_container)), GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat(); setColor(when { date == selected -> color(R.color.cm_primary); matches.any { it.optString("scope") == "university" } -> color(R.color.cm_period_cancel_bg); else -> Color.TRANSPARENT })
                        if (date == LocalDate.now() && date != selected) setStroke(dp(1), color(R.color.cm_primary))
                    }, null)
                    cell.contentDescription = "$date${if (closed) ", offices closed" else ""}${if (classOnly) ", classes closed" else ""}. ${matches.joinToString { it.optString("title") }}"
                    cell.setOnClickListener { selected = if (selected == date) null else date; render() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp((48 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt()), 1f).apply { setMargins(dp(1), dp(2), dp(1), dp(2)) })
            }
            content.addView(row)
        }
        val legend = LinearLayout(activity).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        listOf("Closed" to R.color.cm_notice_cancel_text, "Classes only" to R.color.cm_success, "Event" to R.color.cm_primary).forEach { (name, tint) ->
            val part = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
            part.addView(View(activity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(tint)) } }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) })
            part.addView(label(name, 10f).apply { setTextColor(color(R.color.cm_text_disabled)) }); legend.addView(part)
        }
        content.addView(legend); calendar.addView(content); panel.addView(calendar)
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(18), 0, dp(6)) }
        heading.addView(label(selected?.format(DateTimeFormatter.ofPattern("EEEE, d MMM")) ?: "Holidays & events", 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected != null) heading.addView(quiet("All") { selected = null; render() }, LinearLayout.LayoutParams(-2, dp(48)))
        if (owner()) heading.addView(icon(R.drawable.ic_add, "Add calendar event") { edit(null) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(heading)
        val visible = if (selected == null) monthEvents else events.filter { ClassMateCalendarData.includes(it, selected!!) }
        if (visible.isEmpty()) {
            val message = when {
                data.snapshot(month.year) == null -> if (ClassMateAcademicCache.online(activity)) "${if (error == null) "Syncing" else "Could not sync"} calendar" else "Connect to sync this calendar"
                selected != null && data.classClosure(selected!!) != null -> "Weekly holiday · no classes"
                events.isEmpty() && data.snapshot(month.year)?.optJSONObject("publication") == null -> "Calendar not published for ${month.year}"
                else -> if (selected == null) "No events this month" else "No events on this day"
            }
            panel.addView(surface().apply { addView(label(message, 14f).apply { setTextColor(color(R.color.cm_text_disabled)); setPadding(dp(18), dp(22), dp(18), dp(22)) }) }, LinearLayout.LayoutParams(-1, -2))
        }
        visible.groupBy { it.getString("title") }.forEach { (title, group) ->
            val card = surface(); val body = LinearLayout(activity).apply { gravity = Gravity.TOP; setPadding(dp(14), dp(14), dp(12), dp(14)) }
            val start = LocalDate.parse(group.first().getString("start_date"))
            val badgeAccent = color(when { group.any { it.getString("scope") == "university" } -> R.color.cm_notice_cancel_text; group.any { it.getString("scope") == "classes" } -> R.color.cm_success; else -> R.color.cm_primary })
            val badge = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(androidx.core.graphics.ColorUtils.blendARGB(color(R.color.cm_surface), badgeAccent, 0.1f)) } }
            badge.addView(label(start.format(DateTimeFormatter.ofPattern("MMM")).uppercase(), 10f, true).apply { setTextColor(badgeAccent); gravity = Gravity.CENTER })
            badge.addView(label("${start.dayOfMonth}", 23f, true).apply { setTextColor(badgeAccent); gravity = Gravity.CENTER })
            body.addView(badge, LinearLayout.LayoutParams(dp(48), dp(60)).apply { rightMargin = dp(12) })
            val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(title, 15f, true))
            group.forEach { event ->
                val from = LocalDate.parse(event.getString("start_date")); val to = LocalDate.parse(event.getString("end_date")); val fmt = DateTimeFormatter.ofPattern("d MMM")
                val dates = if (from == to) from.format(fmt) else "${from.format(fmt)} – ${to.format(fmt)}"
                val category = when (event.getString("scope")) { "classes" -> "Classes closed"; "university" -> if (group.any { it.optString("scope") == "classes" }) "Offices closed" else "Classes & offices closed"; "working_day" -> "Working day"; else -> "Observance" }
                details.addView(label("$dates · $category", 12f).apply { setTextColor(color(R.color.cm_text_disabled)); setPadding(0, dp(5), 0, 0) })
            }
            if (group.any { it.optBoolean("provisional") }) details.addView(label("Moon-sighting dependent", 10f).apply { setTextColor(color(R.color.cm_text_disabled)); setPadding(0, dp(5), 0, 0) })
            body.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (owner()) body.addView(icon(R.drawable.ic_more_vert, "Manage $title") {
                if (group.size == 1) edit(group.first()) else MaterialAlertDialogBuilder(activity).setTitle("Edit closure dates").setItems(group.map { if (it.getString("scope") == "classes") "Class holidays" else "Office holidays" }.toTypedArray()) { _, index -> edit(group[index]) }.show()
            }, LinearLayout.LayoutParams(dp(40), dp(48)).apply { leftMargin = dp(4) })
            card.addView(body); panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val snapshot = data.snapshot(month.year)
        val footer = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val synced = snapshot?.optLong("synced_at", snapshot.optLong("saved_at"))
        footer.addView(label(if (synced == null) "Not synced" else "${if (ClassMateAcademicCache.online(activity)) "Synced" else "Offline · synced"} ${Instant.ofEpochMilli(synced).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}", 10f).apply { setTextColor(color(R.color.cm_text_disabled)) }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(quiet("Refresh") { show(true) }, LinearLayout.LayoutParams(-2, dp(48)))
        snapshot?.optJSONObject("publication")?.let { publication -> footer.addView(quiet("Notes") {
            MaterialAlertDialogBuilder(activity).setTitle(publication.optString("title")).setMessage(publication.optString("notes")).setPositiveButton("Done", null).show()
        }, LinearLayout.LayoutParams(-2, dp(48))) }
        panel.addView(footer)
        error?.let { panel.addView(label(it, 12f)) }
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
