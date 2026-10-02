package com.shuaib.classmate.activities

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.format.DateTimeFormatter

/** The same day pills and period cards as the timetable; writes stay server-authorized. */
class ClassMatePeriodEditorActivity : ClassMateScheduleEditor() {
    private data class Offering(val id: String, val title: String, val name: String, val type: String)
    private val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val offerings = mutableListOf<Offering>()
    private val catalog = mutableMapOf<String,Offering>()
    private val slots = mutableListOf<JSONObject>()
    private val teachers = mutableMapOf<String,String>()
    private lateinit var dayRow: LinearLayout
    private lateinit var slotList: LinearLayout
    private lateinit var add: MaterialButton
    private lateinit var count: TextView
    private var day = LocalDate.now().dayOfWeek.value % 7
    private var batchId = ""
    private var role = ""
    private var profileId = ""
    private var loading = false
    private var editor: androidx.appcompat.app.AlertDialog? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); ClassMateAuthApi.attach(applicationContext)
        batchId=intent.getStringExtra("batch_id").orEmpty(); role=intent.getStringExtra("role").orEmpty(); profileId=intent.getStringExtra("profile_id").orEmpty()
        if(batchId.isBlank()) { finish(); return }
        day=(state?.getInt("day") ?: intent.getIntExtra("day",day)).coerceIn(0,6)
        page("Edit timetable"); save.visibility=View.GONE
        form.panel.setPadding(0,dp(8),0,dp(16))
        val root=form.scroll.parent as LinearLayout
        val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,dp(14),dp(12)) }
        val top=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text=intent.getStringExtra("batch_label").orEmpty(); textSize=13f; maxLines=2; setTextColor(getColor(R.color.cm_text_secondary)) },LinearLayout.LayoutParams(0,-2,1f))
        add=MaterialButton(this).apply { text="+ Add period"; isAllCaps=false; cornerRadius=dp(20); isEnabled=false; setOnClickListener { openPeriod(null) } }
        top.addView(add); header.addView(top)
        dayRow=LinearLayout(this); header.addView(dayRow,LinearLayout.LayoutParams(-1,dp(64)).apply { topMargin=dp(10) })
        root.addView(header,1)
        count=form.label("").apply { setPadding(dp(24),dp(10),dp(24),dp(10)) }
        status=form.status().apply { setPadding(dp(24),0,dp(24),dp(8)) }
        slotList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; form.panel.addView(slotList)
        readCache(); renderDays(); renderSlots(); load()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("day",day) }
    private fun time(value:String):LocalTime = runCatching { LocalTime.parse(value.take(5)) }.getOrDefault(LocalTime.of(9,0))
    private fun readCache() {
        val snapshot=ClassMateAcademicCache.read(this,profileId,batchId,"routine") ?: return
        val names=snapshot.optJSONObject("names") ?: JSONObject()
        names.keys().forEach { id -> catalog[id]=Offering(id,names.optString(id),names.optString(id),"class") }
        val entries=snapshot.optJSONArray("entries")
        for(i in 0 until (entries?.length() ?: 0)) entries?.optJSONObject(i)?.let { slots.add(it) }
        val details=snapshot.optJSONArray("details")
        for(i in 0 until (details?.length() ?: 0)) details?.optJSONObject(i)?.let { teachers[it.optString("semester_course_id")]=it.optString("teacher_name").takeUnless { name -> name.equals("null",true) }.orEmpty() }
    }
    private fun renderDays() {
        dayRow.removeAllViews()
        (0..6).forEach { offset ->
            val date=LocalDate.now().plusDays(offset.toLong())
            val index=date.dayOfWeek.value%7
            val name=days[index]
            val pill=layoutInflater.inflate(R.layout.item_day_card,dayRow,false)
            (pill.layoutParams as LinearLayout.LayoutParams).apply { width=0; weight=1f; height=dp(48); marginStart=dp(4); marginEnd=dp(4) }
            pill.findViewById<TextView>(R.id.tvDayShort).apply { text=name.take(3); setTextColor(getColor(if(index==day) R.color.cm_text_inverse else R.color.cm_text_secondary)) }
            pill.findViewById<TextView>(R.id.tvDayDate).apply { text=date.dayOfMonth.toString(); setTextColor(getColor(if(index==day) R.color.cm_text_inverse else R.color.cm_text_primary)) }
            pill.setBackgroundResource(if(index==day) R.drawable.bg_day_card_selected else R.drawable.bg_day_card_unselected)
            pill.findViewById<View>(R.id.vDayIndicator).visibility=if(index==day) View.VISIBLE else View.INVISIBLE
            pill.contentDescription=name; pill.setOnClickListener { day=index; renderDays(); renderSlots() }; dayRow.addView(pill)
        }
    }
    private fun renderSlots() {
        slotList.removeAllViews()
        val entries=slots.filter { it.optInt("day_of_week")==day }.sortedBy { it.optString("start_time") }
        count.text="${days[day].uppercase()}'S SCHEDULE · ${entries.size} classes"
        if(entries.isEmpty()) slotList.addView(TextView(this).apply { text="No periods on ${days[day]}."; gravity=android.view.Gravity.CENTER; setPadding(dp(24),dp(40),dp(24),dp(40)); setTextColor(getColor(R.color.cm_text_secondary)) })
        entries.forEach { slot ->
            val id=slot.optString("semester_course_id"); val offering=catalog[id]
            val card=layoutInflater.inflate(R.layout.item_period,slotList,false)
            fun text(viewId:Int,value:String) { card.findViewById<TextView>(viewId).text=value }
            val from=time(slot.optString("start_time")); val to=time(slot.optString("end_time")); val format=DateTimeFormatter.ofPattern("hh:mm a")
            text(R.id.tvSubject,offering?.name ?: "Class"); text(R.id.tvStartTime,from.format(format)); text(R.id.tvEndTime,to.format(format))
            val teacher=teachers[id].orEmpty(); text(R.id.tvTeacher,teacher); card.findViewById<View>(R.id.layoutTeacherInfo).visibility=if(teacher.isBlank()) View.GONE else View.VISIBLE
            val room=slot.optString("room").takeUnless { it == "null" }.orEmpty(); text(R.id.tvRoom,room); card.findViewById<View>(R.id.layoutRoomInfo).visibility=if(room.isBlank()) View.GONE else View.VISIBLE
            text(R.id.tvTypeBadge,slot.optString("type").takeUnless { it.isBlank() || it=="null" }?.uppercase() ?: if(offering?.type=="lab") "LAB" else "CLASS")
            text(R.id.tvDuration,"${Duration.between(from,to).toMinutes()} min")
            card.findViewById<ImageView>(R.id.ivSubjectIcon).setColorFilter(getColor(R.color.cm_primary_light))
            card.setOnClickListener { if(offerings.any { it.id==id }) openPeriod(slot) else Toast.makeText(this,if(loading) "Loading courses…" else if(!ClassMateAcademicCache.online(this)) "Connect to edit periods." else "You can edit only your assigned courses.",Toast.LENGTH_SHORT).show() }
            slotList.addView(card)
        }
    }
    private fun load() {
        if(loading) return
        loading=true
        lifecycleScope.launch {
            runCatching {
                val semesters=ClassMateAuthApi.rows("semesters","select=id,status&batch_id=eq.$batchId&status=in.(active,not_started)&order=semester_number.desc")
                val semester=(0 until semesters.length()).map { semesters.getJSONObject(it) }.firstOrNull { it.optString("status")=="active" } ?: semesters.optJSONObject(0) ?: error("No current semester in this batch")
                val rows=ClassMateAuthApi.rows("semester_courses","select=id,course_id&semester_id=eq.${semester.getString("id")}")
                val courses=ClassMateAuthApi.rows("courses","select=id,course_code,course_title,course_type")
                val names=(0 until courses.length()).associate { courses.getJSONObject(it).let { item -> item.getString("id") to item } }
                val allowed=if(role=="teacher") ClassMateAuthApi.rows("teacher_course_assignments","select=semester_course_id&teacher_id=eq.$profileId&active=eq.true").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("semester_course_id") }.toSet() } else null
                val nextCatalog=mutableMapOf<String,Offering>()
                for(i in 0 until rows.length()) {
                    val item=rows.getJSONObject(i); val c=names[item.optString("course_id")] ?: continue
                    val name=c.optString("course_title"); nextCatalog[item.getString("id")]=Offering(item.getString("id"),"${c.optString("course_code")} · $name",name,c.optString("course_type"))
                }
                val ids=nextCatalog.keys.joinToString(",")
                val routine=if(ids.isEmpty()) org.json.JSONArray() else ClassMateAuthApi.rows("routine_slots","select=id,semester_course_id,day_of_week,start_time,end_time,room,type&semester_course_id=in.($ids)&order=start_time")
                val details=if(ids.isEmpty()) org.json.JSONArray() else org.json.JSONArray(ClassMateAuthApi.rpcText("timetable_details",JSONObject().put("target_batch",batchId).put("target_date",LocalDate.now().toString()).put("target_course_ids",org.json.JSONArray(nextCatalog.keys.toList()))))
                catalog.clear(); catalog.putAll(nextCatalog); offerings.clear(); offerings.addAll(nextCatalog.values.filter { allowed==null || it.id in allowed })
                slots.clear(); for(i in 0 until routine.length()) slots.add(routine.getJSONObject(i))
                teachers.clear(); for(i in 0 until details.length()) details.getJSONObject(i).let { teachers[it.optString("semester_course_id")]=it.optString("teacher_name").takeUnless { name -> name.equals("null",true) }.orEmpty() }
            }.onSuccess {
                renderSlots(); add.isEnabled=offerings.isNotEmpty(); status.visibility=View.GONE
                intent.getStringExtra("edit_id")?.let { id -> slots.firstOrNull { it.optString("id")==id }?.let(::openPeriod); intent.removeExtra("edit_id") }
            }.onFailure { status.visibility=View.VISIBLE; status.text="${if(slots.isEmpty()) "Could not load timetable." else "Showing saved timetable."} Tap to retry."; status.setOnClickListener { load() } }
            loading=false
        }
    }
    private fun openPeriod(slot: JSONObject?) {
        if(busy || editor?.isShowing==true || offerings.isEmpty()) return
        val dialogForm=ClassMateFormUi(this)
        val box=TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply { hint="Course"; boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE; setBoxCornerRadii(dp(12).toFloat(),dp(12).toFloat(),dp(12).toFloat(),dp(12).toFloat()) }
        val course=MaterialAutoCompleteTextView(box.context).apply { inputType=android.text.InputType.TYPE_NULL; setTextColor(getColor(R.color.cm_text_primary)); setAdapter(ArrayAdapter(this@ClassMatePeriodEditorActivity,android.R.layout.simple_dropdown_item_1line,offerings.map { it.title })) }
        box.addView(course); dialogForm.panel.addView(box)
        val selected=offerings.firstOrNull { it.id==slot?.optString("semester_course_id") } ?: offerings.first()
        course.setText(selected.title,false)
        val room=dialogForm.field("Room or lab").apply { setText(slot?.optString("room")?.takeUnless { it=="null" }.orEmpty()) }
        var from=slot?.let { time(it.optString("start_time")) } ?: slots.filter { it.optInt("day_of_week")==day }.maxByOrNull { it.optString("end_time") }?.let { time(it.optString("end_time")) } ?: LocalTime.of(9,0)
        var to=slot?.let { time(it.optString("end_time")) } ?: from.plusMinutes(45)
        val times=LinearLayout(this)
        times.addView(timeButton("From",{from},{from=it}),LinearLayout.LayoutParams(0,dp(56),1f).apply { marginEnd=dp(4) })
        times.addView(timeButton("To",{to},{to=it}),LinearLayout.LayoutParams(0,dp(56),1f).apply { marginStart=dp(4) })
        dialogForm.panel.addView(times); val error=dialogForm.status()
        val builder=MaterialAlertDialogBuilder(this).setTitle(if(slot==null) "Add period · ${days[day]}" else "Edit period · ${days[day]}").setView(dialogForm.scroll).setNegativeButton("Cancel",null).setPositiveButton(if(slot==null) "Add period" else "Save",null)
        if(slot!=null) builder.setNeutralButton("Delete",null)
        val dialog=builder.create(); editor=dialog; dialog.show()
        fun saveAction(deleting:Boolean) {
            if(busy) return
            val offering=offerings.firstOrNull { it.title==course.text.toString() }
            if(!deleting && offering==null) { box.error="Select a course"; return }
            if(!deleting && from>=to) { error.visibility=View.VISIBLE; error.text="End time must be after start time."; return }
            if(!ClassMateAcademicCache.online(this)) { error.visibility=View.VISIBLE; error.text="Connect to save changes."; return }
            busy=true; dialog.setCancelable(false)
            listOf(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE,androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).forEach { dialog.getButton(it)?.isEnabled=false }
            error.visibility=View.VISIBLE; error.text=if(deleting) "Deleting…" else "Saving…"
            lifecycleScope.launch {
                try {
                    if(deleting) {
                        ClassMateAuthApi.rpcText("delete_routine_slot",JSONObject().put("target_id",slot!!.getString("id")))
                        slots.removeAll { it.optString("id")==slot.optString("id") }
                        ClassMateAcademicCache.updateSchedule(this@ClassMatePeriodEditorActivity,profileId,batchId,"routine",null,deletedId=slot.getString("id"))
                    } else {
                        val saved=ClassMateAuthApi.rpc("save_routine_slot",JSONObject().put("target_id",slot?.optString("id") ?: JSONObject.NULL).put("target_semester_course",offering!!.id).put("target_day",day).put("target_start",from.toString()).put("target_end",to.toString()).put("target_room",room.text.toString().trim()))
                        slots.removeAll { it.optString("id")==saved.optString("id") }; slots.add(saved)
                        ClassMateAcademicCache.updateSchedule(this@ClassMatePeriodEditorActivity,profileId,batchId,"routine",saved,courseName=offering.name)
                    }
                    setResult(RESULT_OK); renderSlots(); dialog.dismiss()
                } catch(e:Exception) { error.text=e.message ?: "Could not save. Try again." }
                finally { busy=false; dialog.setCancelable(true); listOf(-1,-2,-3).forEach { dialog.getButton(it)?.isEnabled=true } }
            }
        }
        dialog.getButton(-1).setOnClickListener { saveAction(false) }
        if(slot!=null) dialog.getButton(-3).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Delete period?").setMessage("Remove this period from ${days[day]}'s timetable?").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> saveAction(true) }.show()
        }
    }
}
