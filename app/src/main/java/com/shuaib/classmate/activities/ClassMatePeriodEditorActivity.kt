package com.shuaib.classmate.activities

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A batch-scoped routine editor. Supabase RPCs enforce owner, CR, and teacher authority. */
class ClassMatePeriodEditorActivity : AppCompatActivity() {
    private data class Offering(val id: String, val title: String)

    private val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val offerings = mutableListOf<Offering>()
    private val slots = mutableListOf<JSONObject>()
    private lateinit var dayRow: LinearLayout
    private lateinit var slotList: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var endButton: TextView
    private lateinit var message: TextView
    private var day = LocalDate.now().dayOfWeek.value % 7
    private var start = LocalTime.of(9, 0)
    private var end = LocalTime.of(9, 45)
    private var batchId = ""
    private var role = ""
    private var profileId = ""
    private var changed = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClassMateAuthApi.attach(applicationContext)
        batchId = intent.getStringExtra("batch_id").orEmpty()
        role = intent.getStringExtra("role").orEmpty()
        profileId = intent.getStringExtra("profile_id").orEmpty()
        if (batchId.isBlank()) { finish(); return }
        day = intent.getIntExtra("day", day).coerceIn(0, 6)
        start = parseTime(intent.getStringExtra("start")) ?: start
        end = parseTime(intent.getStringExtra("end")) ?: end

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(getColor(R.color.cm_background))
        }
        setContentView(page)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(top)
        val back = label("‹", 30f).apply {
            setPadding(dp(4), 0, dp(14), 0)
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(label("Edit timetable", 23f).apply { setTypeface(null, 1) })
        page.addView(label(intent.getStringExtra("batch_label") ?: "Running batch", 13f).apply {
            setTextColor(getColor(R.color.cm_text_disabled))
            setPadding(dp(4), 0, 0, dp(14))
        })

        dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(dayRow)
        page.addView(label("Time for a new period", 15f).apply {
            setTypeface(null, 1)
            setPadding(dp(4), dp(22), 0, dp(7))
        })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = action("From 09:00").also { times.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        endButton = action("To 09:45").also { times.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        page.addView(times)
        startButton.setOnClickListener { chooseTime(start) { start = it; updateTimeLabels() } }
        endButton.setOnClickListener { chooseTime(end) { end = it; updateTimeLabels() } }
        updateTimeLabels()
        val add = action("＋  Add period").apply {
            setTextColor(getColor(R.color.cm_primary))
            setTypeface(null, 1)
            setOnClickListener { showCourseRoomDialog(null) }
        }
        page.addView(add, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        message = label("Loading courses and periods…", 14f).apply {
            setPadding(dp(4), dp(16), 0, dp(8))
        }
        page.addView(message)
        val scroll = ScrollView(this)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        slotList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(slotList)
        renderDays()
        load()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.cm_text_primary))
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(value: String) = label(value, 14f).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_day_card_unselected)
        isClickable = true
        isFocusable = true
    }

    private fun renderDays() {
        dayRow.removeAllViews()
        days.forEachIndexed { index, name ->
            val card = layoutInflater.inflate(R.layout.item_day_card, dayRow, false)
            card.findViewById<TextView>(R.id.tvDayShort).text = name.take(3)
            card.findViewById<TextView>(R.id.tvDayDate).text = LocalDate.now().minusDays(
                (LocalDate.now().dayOfWeek.value % 7 - index).toLong()).dayOfMonth.toString()
            if (index == day) {
                card.setBackgroundResource(R.drawable.bg_day_card_selected)
                card.findViewById<TextView>(R.id.tvDayShort).setTextColor(getColor(android.R.color.white))
                card.findViewById<TextView>(R.id.tvDayDate).setTextColor(getColor(android.R.color.white))
                card.findViewById<View>(R.id.vDayIndicator).visibility = View.VISIBLE
            }
            card.setOnClickListener { day = index; renderDays(); renderSlots() }
            dayRow.addView(card)
        }
    }

    private fun updateTimeLabels() {
        startButton.text = "From ${start.format(DateTimeFormatter.ofPattern("hh:mm a"))}"
        endButton.text = "To ${end.format(DateTimeFormatter.ofPattern("hh:mm a"))}"
    }

    private fun chooseTime(current: LocalTime, onChosen: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> onChosen(LocalTime.of(hour, minute)) },
            current.hour, current.minute, false).show()
    }

    private fun parseTime(value: String?): LocalTime? = runCatching {
        value?.take(5)?.let(LocalTime::parse)
    }.getOrNull()

    private fun load() = lifecycleScope.launch {
        runCatching {
            val semesters = ClassMateAuthApi.rows("semesters",
                "select=id,status&batch_id=eq.$batchId&status=in.(active,not_started)&order=semester_number.desc")
            val semester = (0 until semesters.length()).map { semesters.getJSONObject(it) }
                .firstOrNull { it.optString("status") == "active" }
                ?: semesters.optJSONObject(0) ?: error("No current semester in this batch")
            val semesterId = semester.getString("id")
            val rows = ClassMateAuthApi.rows("semester_courses",
                "select=id,course_id&semester_id=eq.$semesterId")
            val courses = ClassMateAuthApi.rows("courses", "select=id,course_code,course_title")
            val names = (0 until courses.length()).associate { index ->
                courses.getJSONObject(index).let {
                    it.getString("id") to "${it.optString("course_code")} · ${it.optString("course_title")}" }
            }
            val allowed = if (role == "teacher") {
                val assignment = ClassMateAuthApi.rows("teacher_course_assignments",
                    "select=semester_course_id&teacher_id=eq.$profileId&active=eq.true")
                (0 until assignment.length()).map {
                    assignment.getJSONObject(it).getString("semester_course_id") }.toSet()
            } else null
            offerings.clear()
            (0 until rows.length()).forEach { index ->
                val item = rows.getJSONObject(index)
                val id = item.getString("id")
                if (allowed == null || id in allowed) offerings += Offering(id,
                    names[item.optString("course_id")] ?: "Course")
            }
            slots.clear()
            if (rows.length() > 0) {
                val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
                val routine = ClassMateAuthApi.rows("routine_slots",
                    "select=id,semester_course_id,day_of_week,start_time,end_time,room" +
                        "&semester_course_id=in.(${ids.joinToString(",")})&order=start_time")
                (0 until routine.length()).forEach { slots += routine.getJSONObject(it) }
            }
        }.onSuccess {
            message.text = if (offerings.isEmpty()) "No courses you can edit in this semester."
                else "${days[day]}'s periods · tap a period to edit"
            renderSlots()
            intent.getStringExtra("edit_id")?.let { id ->
                slots.firstOrNull { it.optString("id") == id }?.let(::editSlot)
                intent.removeExtra("edit_id")
            }
        }.onFailure { message.text = it.message ?: "Could not load timetable" }
    }

    private fun renderSlots() {
        if (!::slotList.isInitialized) return
        slotList.removeAllViews()
        val selected = slots.filter { it.optInt("day_of_week") == day }
        selected.forEach { slot ->
            val title = offerings.firstOrNull { it.id == slot.optString("semester_course_id") }?.title
                ?: "Course"
            val line = action("${slot.optString("start_time").take(5)}–${slot.optString("end_time").take(5)}  $title\n${slot.optString("room")}").apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { editSlot(slot) }
            }
            slotList.addView(line, LinearLayout.LayoutParams(-1, dp(74)).apply {
                bottomMargin = dp(8)
            })
        }
        if (selected.isEmpty()) slotList.addView(label("No periods on ${days[day]}.", 14f))
    }

    private fun editSlot(slot: JSONObject) {
        day = slot.optInt("day_of_week")
        start = parseTime(slot.optString("start_time")) ?: start
        end = parseTime(slot.optString("end_time")) ?: end
        renderDays()
        updateTimeLabels()
        val options = arrayOf("Edit period", "Delete period")
        AlertDialog.Builder(this).setTitle("Period options").setItems(options) { _, which ->
            if (which == 0) showCourseRoomDialog(slot) else confirmDelete(slot)
        }.show()
    }

    private fun showCourseRoomDialog(existing: JSONObject?) {
        if (offerings.isEmpty()) { Toast.makeText(this, "No available courses", Toast.LENGTH_SHORT).show(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        panel.addView(label("Course", 13f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            offerings.map { it.title })
        spinner.setSelection(offerings.indexOfFirst {
            it.id == existing?.optString("semester_course_id") }.coerceAtLeast(0))
        panel.addView(spinner)
        panel.addView(label("Room", 13f).apply { setPadding(0, dp(14), 0, 0) })
        val room = EditText(this).apply {
            hint = "Room or lab"
            setSingleLine(true)
            setText(existing?.optString("room").orEmpty())
        }
        panel.addView(room)
        AlertDialog.Builder(this).setTitle(if (existing == null) "Add ${days[day]} period" else "Edit period")
            .setView(panel).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (start >= end) {
                            Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        lifecycleScope.launch {
                            runCatching {
                                ClassMateAuthApi.rpc("save_routine_slot", JSONObject()
                                    .put("target_id", existing?.optString("id") ?: JSONObject.NULL)
                                    .put("target_semester_course", offerings[spinner.selectedItemPosition].id)
                                    .put("target_day", day).put("target_start", start.toString())
                                    .put("target_end", end.toString()).put("target_room", room.text.toString().trim()))
                            }.onSuccess { changed = true; setResult(RESULT_OK); load() }
                                .onFailure { Toast.makeText(this@ClassMatePeriodEditorActivity,
                                    it.message ?: "Could not save period", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun confirmDelete(slot: JSONObject) {
        AlertDialog.Builder(this).setTitle("Delete this period?")
            .setMessage("This removes the period from the batch timetable.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runCatching { ClassMateAuthApi.rpcText("delete_routine_slot",
                        JSONObject().put("target_id", slot.getString("id"))) }
                        .onSuccess { changed = true; setResult(RESULT_OK); load() }
                        .onFailure { Toast.makeText(this@ClassMatePeriodEditorActivity,
                            it.message ?: "Could not delete period", Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
