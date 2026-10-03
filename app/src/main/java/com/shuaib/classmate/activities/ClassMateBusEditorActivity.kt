package com.shuaib.classmate.activities

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONObject
import java.time.LocalTime

/** Pairs campus/city departures without vehicle descriptions or trip numbers. */
class ClassMateBusEditorActivity : ClassMateScheduleEditor() {
    private var draft: (() -> JSONObject)? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); ClassMateAuthApi.attach(applicationContext)
        val existing=intent.getStringExtra("bus")?.let(::JSONObject)
        val initial=state?.getString("draft")?.let(::JSONObject) ?: existing
        page(if(existing==null) "Add bus schedule" else "Edit bus schedule")
        form.label("Student bus departures")
        val group=MaterialButtonToggleGroup(this).apply { isSingleSelection=true; isSelectionRequired=true }
        val openId=android.view.View.generateViewId(); val closedId=android.view.View.generateViewId()
        fun option(label: String,id: Int)=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.id=id; text=label; isAllCaps=false; textSize=13f; maxLines=2; minimumHeight=dp(56)
        }
        group.addView(option("Office open",openId),LinearLayout.LayoutParams(0,-2,1f))
        group.addView(option("Closed / holidays",closedId),LinearLayout.LayoutParams(0,-2,1f))
        form.panel.addView(group)
        val oldDays=initial?.optJSONArray("weekdays")
        val closed=(initial?.optString("schedule_kind") ?: intent.getStringExtra("day_kind"))=="closed" ||
            (initial?.optString("schedule_kind").orEmpty() in setOf("","legacy") && oldDays!=null && oldDays.length()>0 &&
                (0 until oldDays.length()).all { oldDays.optInt(it) in 5..6 })
        group.check(if(closed) closedId else openId)
        fun parse(key: String,fallback: LocalTime)=runCatching { LocalTime.parse(initial?.optString(key)?.take(5)) }.getOrNull() ?: fallback
        var campus=parse("departure_time",LocalTime.of(8,0))
        var city=parse("city_departure_time",campus.plusMinutes(30))
        form.label("Campus → City")
        form.panel.addView(timeButton("Campus departure",{campus},{campus=it}),LinearLayout.LayoutParams(-1,dp(60)).apply { bottomMargin=dp(12) })
        form.label("City → Campus")
        form.panel.addView(timeButton("City departure",{city},{city=it}),LinearLayout.LayoutParams(-1,dp(60)))
        val active=MaterialSwitch(this).apply {
            text="Schedule enabled"; setTextColor(getColor(R.color.cm_text_primary)); isChecked=initial?.optBoolean("active") ?: true
            visibility=if(existing==null) android.view.View.GONE else android.view.View.VISIBLE
        }
        form.panel.addView(active,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
        draft={ JSONObject().put("departure_time",campus.toString()).put("city_departure_time",city.toString())
            .put("schedule_kind",if(group.checkedButtonId==closedId) "closed" else "office_open").put("active",active.isChecked) }
        status=form.status(); save.text="Save schedule"
        save.setOnClickListener {
            perform({
                val saved=ClassMateAuthApi.rpc("save_student_bus_schedule",JSONObject()
                    .put("target_id",existing?.optString("id") ?: JSONObject.NULL)
                    .put("target_kind",if(group.checkedButtonId==closedId) "closed" else "office_open")
                    .put("target_campus_departure",campus.toString()).put("target_city_departure",city.toString()).put("target_active",active.isChecked))
                ClassMateAcademicCache.updateSchedule(this,intent.getStringExtra("profile_id").orEmpty(),intent.getStringExtra("batch_id").orEmpty(),"bus",if(active.isChecked) saved else null,deletedId=if(active.isChecked) null else saved.getString("id"))
            },{ finish() })
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); draft?.let { out.putString("draft",it().toString()) }
    }
}
