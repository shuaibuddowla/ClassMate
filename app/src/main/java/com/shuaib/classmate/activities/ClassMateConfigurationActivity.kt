package com.shuaib.classmate.activities

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Supabase-only course setup, teacher directory and owner roster. */
class ClassMateConfigurationActivity : ClassMateScheduleEditor() {
    private val batch by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private val role by lazy { intent.getStringExtra("role").orEmpty() }
    private val mode by lazy { intent.getStringExtra("mode") ?: "courses" }
    private lateinit var rowsHost: LinearLayout
    private var courses=emptyList<JSONObject>()
    private var request: Job?=null
    private var generation=0
    private var editor: androidx.appcompat.app.AlertDialog?=null
    private var semesterAvailable=false
    private val pendingCRChanges=mutableSetOf<String>()
    private fun rows(a: JSONArray)=(0 until a.length()).map { a.getJSONObject(it) }
    private fun clean(item: JSONObject,key: String)=item.optString(key).takeUnless { it=="null" }.orEmpty()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); ClassMateAuthApi.attach(applicationContext)
        page(when(mode) { "people" -> "People & approvals"; "teachers" -> "Teachers"; else -> "Courses" })
        save.visibility=View.GONE
        form.label(if(mode=="courses") intent.getStringExtra("batch_label").orEmpty() else if(mode=="people") "All ClassMate accounts" else "Teacher profiles and course assignments")
        status=form.status()
        rowsHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(mode=="people") { setupPeople(); return }
        if(mode=="teachers") form.panel.addView(MaterialButton(this).apply {
            text="＋ Add teacher"; isAllCaps=false; setOnClickListener { addTeacher() }
        },LinearLayout.LayoutParams(-1,dp(56)))
        if(mode=="courses") {
            form.panel.addView(MaterialButton(this).apply { text="＋ Add course"; isAllCaps=false; cornerRadius=dp(16); setOnClickListener {
                if(semesterAvailable) courseForm(null) else MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity)
                    .setTitle("Active semester needed").setMessage("Ask the global admin to publish a semester in Manage → Batches & semesters before adding courses.").setPositiveButton("Got it",null).show()
            } },LinearLayout.LayoutParams(-1,dp(56)))
            form.panel.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="Choose an existing course"; isAllCaps=false; setOnClickListener { catalogPicker() }
            })
        }
        form.panel.addView(rowsHost); load()
    }
    private fun card(title: String,detail: String,action: ()->Unit) {
        val c=MaterialCardView(this).apply { radius=dp(18).toFloat(); cardElevation=0f; strokeWidth=dp(1); strokeColor=getColor(R.color.cm_border); setCardBackgroundColor(getColor(R.color.cm_surface)) }
        val labels=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        labels.addView(TextView(this).apply { text=title; textSize=16f; setTypeface(null,1); setTextColor(getColor(R.color.cm_text_primary)) })
        labels.addView(TextView(this).apply { text=detail; textSize=13f; setPadding(0,dp(6),0,0); setTextColor(getColor(R.color.cm_text_secondary)) })
        c.addView(labels); c.isClickable=true; c.isFocusable=true; c.setOnClickListener { action() }
        rowsHost.addView(c,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
    }
    private fun load() {
        request?.cancel(); val version=++generation; status.visibility=View.VISIBLE; status.text="Loading…"
        request=lifecycleScope.launch {
            try {
                if(mode=="teachers") {
                    // Owner-only RPC also includes signed-in teachers without draft records.
                    val records=rows(JSONArray(ClassMateAuthApi.rpcText("owner_teachers",JSONObject())))
                    if(version!=generation) return@launch
                    rowsHost.removeAllViews(); records.forEach { t -> card(clean(t,"full_name"),
                        listOf(clean(t,"email").ifBlank { "Email not added" },"${t.optInt("course_count")} courses").joinToString(" · ")) { teacherForm(t) } }
                    status.text=if(records.isEmpty()) "Add a teacher while configuring a course." else "${records.size} teachers"
                } else {
                    val semesters=ClassMateAuthApi.rows("semesters","select=id&batch_id=eq.$batch&status=eq.active&limit=1")
                    courses=rows(ClassMateCourses.catalog(batch)); semesterAvailable=semesters.length()>0
                    if(version!=generation) return@launch
                    rowsHost.removeAllViews(); courses.forEach { c -> card("${c.optString("course_code")} · ${c.optString("course_title")}",
                        listOf(c.optString("course_type").replaceFirstChar { it.uppercase() },clean(c,"teacher_name").ifBlank { "Teacher not added" }).joinToString(" · ")) {
                        MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle(c.optString("course_title"))
                            .setItems(if(role=="teacher") arrayOf("Delete from this batch") else arrayOf("Edit course","Delete from this batch")) { _,i -> if(role!="teacher" && i==0) courseForm(c) else deleteCourse(c) }.show()
                    } }
                    status.text=if(!semesterAvailable) "No active semester. Ask the global admin to publish one." else if(courses.isEmpty()) "No courses yet. Add your first course." else "${courses.size} courses · Changes stay within this batch"
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { status.text="Could not load. Tap to retry."; status.setOnClickListener { load() } }
        }
    }
    private fun catalogPicker() {
        if(!semesterAvailable || busy) return
        lifecycleScope.launch {
            try {
                val dept=ClassMateAuthApi.rows("batches","select=department_id&id=eq.$batch").getJSONObject(0).getString("department_id")
                val catalog=rows(ClassMateAuthApi.rows("courses","select=id,course_code,course_title,course_type,credit&department_id=eq.$dept&order=course_code"))
                val f=ClassMateFormUi(this@ClassMateConfigurationActivity); val query=f.field("Search courses")
                val list=LinearLayout(this@ClassMateConfigurationActivity).apply { orientation=LinearLayout.VERTICAL }; f.panel.addView(list)
                val dialog=MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Shared course catalog").setView(f.scroll).setNegativeButton("Close",null).create()
                fun render() {
                    list.removeAllViews()
                    catalog.filter { (it.optString("course_code")+" "+it.optString("course_title")).contains(query.text.toString(),true) }.forEach { c ->
                        val row=LinearLayout(this@ClassMateConfigurationActivity).apply { gravity=Gravity.CENTER_VERTICAL }
                        row.addView(MaterialButton(this@ClassMateConfigurationActivity,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
                            text="${c.optString("course_code")} · ${c.optString("course_title")}"; isAllCaps=false; setOnClickListener { dialog.dismiss(); courseForm(null,c) }
                        },LinearLayout.LayoutParams(0,-2,1f))
                        if(role=="admin") row.addView(MaterialButton(this@ClassMateConfigurationActivity,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
                            text="⋮"; contentDescription="Manage shared course"; setOnClickListener { dialog.dismiss(); globalCourse(c) }
                        },LinearLayout.LayoutParams(dp(48),dp(48)))
                        list.addView(row)
                    }
                }
                query.doAfterTextChanged { render() }; render(); dialog.show()
            } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
        }
    }
    private fun courseForm(current: JSONObject?,existing: JSONObject?=null) {
        if(editor?.isShowing==true || busy) return
        val initial=current ?: existing; val f=ClassMateFormUi(this)
        f.label("Names ending in Lab are detected as lab courses.")
        val code=f.field("Course code").apply { setText(initial?.optString("course_code")); filters=arrayOf(android.text.InputFilter.LengthFilter(30)) }
        val name=f.field("Course name").apply { setText(initial?.optString("course_title")); filters=arrayOf(android.text.InputFilter.LengthFilter(200)) }
        val teacher=f.field("Teacher name (optional)").apply { setText(current?.let { clean(it,"teacher_name") }); filters=arrayOf(android.text.InputFilter.LengthFilter(200)) }
        var selectedTeacher=current?.let { clean(it,"teacher_record_id").ifBlank { null } }
        teacher.doAfterTextChanged { if(teacher.text.toString()!=current?.let { clean(it,"teacher_name") }) selectedTeacher=null }
        val available=courses.filter { clean(it,"teacher_record_id").isNotBlank() }.distinctBy { it.optString("teacher_record_id") }
        if(available.isNotEmpty() || role=="admin") f.panel.addView(MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text="Choose a configured teacher"; isAllCaps=false; setOnClickListener {
                lifecycleScope.launch {
                    try {
                        val options=if(role=="admin") {
                            val dept=ClassMateAuthApi.rows("batches","select=department_id&id=eq.$batch").getJSONObject(0).getString("department_id")
                            rows(ClassMateAuthApi.rows("teacher_directory","select=id,full_name,email&department_id=eq.$dept&order=full_name"))
                                .map { JSONObject(it.toString()).put("teacher_record_id",it.getString("id")).put("teacher_name",it.getString("full_name")) }
                        } else available
                        if(options.isEmpty()) { Toast.makeText(this@ClassMateConfigurationActivity,"No teachers configured yet. Enter a name to create one.",Toast.LENGTH_LONG).show(); return@launch }
                        MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Choose teacher").setItems(options.map { clean(it,"teacher_name")+clean(it,"email").takeIf { email -> email.isNotBlank() }?.let { email -> " · $email" }.orEmpty() }.toTypedArray()) { _,i ->
                            teacher.setText(clean(options[i],"teacher_name")); selectedTeacher=options[i].getString("teacher_record_id")
                        }.show()
                    } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
                }
            }
        })
        val credit=f.field("Credit (optional)").apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(initial?.optString("credit")?.takeUnless { it=="null" }) }
        val error=f.status()
        val dialog=MaterialAlertDialogBuilder(this).setTitle(if(current==null) "Add course" else "Edit course").setView(f.scroll)
            .setNegativeButton("Cancel",null).setPositiveButton(if(current==null) "Add course" else "Save",null).create()
        editor=dialog; dialog.show()
        dialog.getButton(-1).setOnClickListener {
            val c=code.text.toString().trim(); val n=name.text.toString().trim(); val t=teacher.text.toString().trim(); val creditText=credit.text.toString().trim()
            if(c.length !in 2..30 || n.length !in 2..200 || (t.isNotEmpty() && t.length<2) || (creditText.isNotEmpty() && creditText.toDoubleOrNull()?.let { it>0 && it<=30 }!=true)) {
                error.visibility=View.VISIBLE; error.text="Check the course code, name, teacher and credit."; return@setOnClickListener
            }
            write(dialog,error,{
                ClassMateAuthApi.rpc("save_batch_course",JSONObject().put("target_batch",batch)
                    .put("target_offering",current?.optString("offering_id") ?: JSONObject.NULL).put("target_code",c).put("target_title",n)
                    .put("target_teacher_name",t).put("target_teacher_record",selectedTeacher ?: JSONObject.NULL)
                    .put("target_credit",creditText.toDoubleOrNull() ?: JSONObject.NULL).put("target_catalog_course",existing?.optString("id") ?: JSONObject.NULL))
                current?.let { ClassMateAcademicCache.renameCourse(this,setOf(it.getString("offering_id")),n,c) }
            })
        }
    }
    private fun write(dialog: androidx.appcompat.app.AlertDialog,error: TextView,operation: suspend ()->Unit) {
        if(busy) return
        if(!ClassMateAcademicCache.online(this)) { error.visibility=View.VISIBLE; error.text="Connect to save changes."; return }
        busy=true; dialog.setCancelable(false); dialog.getButton(-1).isEnabled=false; error.visibility=View.VISIBLE; error.text="Saving…"
        lifecycleScope.launch {
            try { operation(); setResult(RESULT_OK); dialog.dismiss(); load() }
            catch(e: Exception) { error.text=e.message ?: "Could not save. Try again." }
            finally { busy=false; dialog.setCancelable(true); dialog.getButton(-1).isEnabled=true }
        }
    }
    private fun deleteCourse(c: JSONObject) {
        val f=ClassMateFormUi(this); f.label("Removes this batch's periods, notices and teacher assignments. Other batches are preserved. Delete linked library files first."); val error=f.status()
        val d=MaterialAlertDialogBuilder(this).setTitle("Delete ${c.optString("course_code")}? ").setView(f.scroll).setNegativeButton("Cancel",null).setPositiveButton("Delete",null).create(); d.show()
        d.getButton(-1).setOnClickListener { write(d,error,{
            ClassMateAuthApi.rpcText("remove_batch_course",JSONObject().put("target_offering",c.getString("offering_id")))
            ClassMateAcademicCache.removeCourse(this,setOf(c.getString("offering_id")))
        }) }
    }
    private fun teacherForm(t: JSONObject) {
        if(role!="admin" || editor?.isShowing==true) return
        val f=ClassMateFormUi(this); val name=f.field("Teacher name").apply { setText(clean(t,"full_name")) }
        val email=f.field("University email (optional)").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setText(clean(t,"email")) }
        f.label("${t.optInt("course_count")} courses · ${clean(t,"course_names")}")
        f.label("Adding an email allows this teacher to sign in and access assigned courses.")
        f.panel.addView(MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text="Assign a course"; isAllCaps=false; setOnClickListener { assignTeacher(t) }
        })
        if(clean(t,"email").isNotBlank()) f.panel.addView(MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text="Disable teacher access"; isAllCaps=false; setOnClickListener {
                MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Disable teacher access?")
                    .setMessage("Existing teacher assignments will be deactivated.").setNegativeButton("Cancel",null).setPositiveButton("Disable") { _,_ ->
                        lifecycleScope.launch {
                            try { ClassMateAuthApi.rpc("set_teacher_allowlist",JSONObject().put("target_email",t.getString("email")).put("target_department",t.getString("department_id")).put("target_active",false)); setResult(RESULT_OK); Toast.makeText(this@ClassMateConfigurationActivity,"Teacher access disabled",Toast.LENGTH_SHORT).show() }
                            catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
                        }
                    }.show()
            }
        })
        val error=f.status(); val d=MaterialAlertDialogBuilder(this).setTitle("Teacher profile").setView(f.scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create(); editor=d; d.show()
        d.getButton(-1).setOnClickListener { write(d,error,{
            ClassMateAuthApi.rpc("owner_save_teacher",JSONObject().put("target_record",t.getString("id")).put("target_name",name.text.toString().trim()).put("target_email",email.text.toString().trim()))
        }) }
    }
    private fun addTeacher() = lifecycleScope.launch {
        try {
            val departments=rows(ClassMateAuthApi.rows("departments","select=id,name&order=name"))
            val f=ClassMateFormUi(this@ClassMateConfigurationActivity)
            val department=f.choice("Department",departments.map { it.optString("name") })
            val name=f.field("Teacher name"); val email=f.field("University email (optional)")
            val error=f.status(); val d=MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Add teacher").setView(f.scroll).setNegativeButton("Cancel",null).setPositiveButton("Add teacher",null).create(); d.show()
            d.getButton(-1).setOnClickListener { write(d,error,{
                val dept=departments.getOrNull(department.selectedItemPosition) ?: throw IllegalArgumentException("Select a department")
                ClassMateAuthApi.rpc("owner_create_teacher",JSONObject().put("target_department",dept.getString("id")).put("target_name",name.text.toString().trim()).put("target_email",email.text.toString().trim()))
            }) }
        } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
    }
    private fun assignTeacher(t: JSONObject) = lifecycleScope.launch {
        try {
            val choices=rows(ClassMateCourses.catalog(batch))
            if(choices.isEmpty()) { Toast.makeText(this@ClassMateConfigurationActivity,"Configure courses in the selected batch first",Toast.LENGTH_LONG).show(); return@launch }
            MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Assign course · current batch").setItems(choices.map { "${it.optString("course_code")} · ${it.optString("course_title")}" }.toTypedArray()) { _,i ->
                lifecycleScope.launch {
                    try { ClassMateAuthApi.rpcText("owner_assign_teacher_record",JSONObject().put("target_record",t.getString("id")).put("target_offering",choices[i].getString("offering_id"))); setResult(RESULT_OK); load() }
                    catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
                }
            }.show()
        } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
    }
    private fun globalCourse(c: JSONObject) {
        MaterialAlertDialogBuilder(this).setTitle("Shared catalog course").setItems(arrayOf("Edit across batches","Delete across all batches")) { _,which ->
            if(which==0) {
                val f=ClassMateFormUi(this); f.label("Updates the shared catalog. Batch-specific edits remain preserved.")
                val code=f.field("Course code").apply { setText(c.optString("course_code")) }; val name=f.field("Course name").apply { setText(c.optString("course_title")) }; val e=f.status()
                val d=MaterialAlertDialogBuilder(this).setTitle("Edit shared course").setView(f.scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create(); d.show()
                d.getButton(-1).setOnClickListener { write(d,e,{ ClassMateAuthApi.rpc("edit_course",JSONObject().put("target_course",c.getString("id")).put("target_code",code.text.toString().trim()).put("target_title",name.text.toString().trim())) }) }
            } else lifecycleScope.launch {
                try {
                    val impact=ClassMateAuthApi.rpc("course_deletion_preview",JSONObject().put("target_course",c.getString("id")))
                    val f=ClassMateFormUi(this@ClassMateConfigurationActivity); f.label("Permanently deletes from ${impact.optInt("batches")} batches, ${impact.optInt("periods")} periods, ${impact.optInt("files")} files and ${impact.optInt("notices")} notices."); val e=f.status()
                    val d=MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Delete shared course?").setView(f.scroll).setNegativeButton("Cancel",null).setPositiveButton("Delete everywhere",null).create(); d.show()
                    d.getButton(-1).setOnClickListener { write(d,e,{
                        val ids=rows(ClassMateAuthApi.rows("semester_courses","select=id&course_id=eq.${c.getString("id")}")).map { it.getString("id") }.toSet()
                        ClassMateAuthApi.rpc("delete_global_course",JSONObject().put("target_course",c.getString("id")))
                        ClassMateAcademicCache.removeCourse(this@ClassMateConfigurationActivity,ids)
                    }) }
                } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
            }
        }.show()
    }
    private fun setupPeople() {
        val search=form.field("Search name, student ID or batch")
        search.filters=arrayOf(android.text.InputFilter.LengthFilter(100))
        val parent=form.scroll.parent as LinearLayout
        // Keep search fixed and use RecyclerView for the paginated roster.
        parent.removeView(form.scroll); parent.addView(form.panel.apply { (this.parent as? android.view.ViewGroup)?.removeView(this) },LinearLayout.LayoutParams(-1,-2))
        val list=RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@ClassMateConfigurationActivity); itemAnimator=null; setPadding(dp(16),dp(8),dp(16),dp(16)); clipToPadding=false }
        parent.addView(list,LinearLayout.LayoutParams(-1,0,1f)); val people=mutableListOf<JSONObject>(); var offset=0; var more=true; var loading=false; var searchJob: Job?=null; var query=""; var epoch=0
        val adapter=object: RecyclerView.Adapter<PersonHolder>() {
            override fun getItemCount()=people.size
            override fun onCreateViewHolder(p: android.view.ViewGroup,type: Int): PersonHolder {
                val row=LinearLayout(this@ClassMateConfigurationActivity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(12),dp(8),dp(12)); setBackgroundColor(getColor(R.color.cm_surface)) }
                val avatar=ImageView(this@ClassMateConfigurationActivity); row.addView(avatar,LinearLayout.LayoutParams(dp(44),dp(44)))
                val labels=LinearLayout(this@ClassMateConfigurationActivity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(6),0) }
                val name=TextView(this@ClassMateConfigurationActivity).apply { textSize=16f; setTypeface(null,1); setTextColor(getColor(R.color.cm_text_primary)) }; val detail=TextView(this@ClassMateConfigurationActivity).apply { textSize=12f; setTextColor(getColor(R.color.cm_text_secondary)) }
                labels.addView(name); labels.addView(detail); row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                val toggle=SwitchMaterial(this@ClassMateConfigurationActivity).apply { text="CR"; setTextColor(getColor(R.color.cm_text_primary)) }; row.addView(toggle)
                val card=MaterialCardView(this@ClassMateConfigurationActivity).apply { radius=dp(16).toFloat(); cardElevation=0f; setCardBackgroundColor(getColor(R.color.cm_surface)); clipToOutline=true; addView(row); layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(8) } }
                return PersonHolder(card,avatar,name,detail,toggle)
            }
            override fun onBindViewHolder(h: PersonHolder,pos: Int) {
                val p=people[pos]; h.name.text=clean(p,"full_name").ifBlank { "ClassMate member" }
                h.detail.text=listOf(clean(p,"student_id"),clean(p,"batch_name"),clean(p,"role"),clean(p,"verification_status"),if(p.optBoolean("is_cr")) clean(p,"cr_valid_until").takeIf { it.isNotBlank() }?.let { "CR until ${it.take(10)}" }.orEmpty() else "").filter { it.isNotBlank() }.joinToString(" · ")
                Glide.with(this@ClassMateConfigurationActivity).load(clean(p,"avatar_url").ifBlank { null }).circleCrop().placeholder(R.drawable.ic_default_avatar).into(h.avatar)
                h.toggle.setOnCheckedChangeListener(null); h.toggle.isChecked=p.optBoolean("is_cr"); h.toggle.visibility=if(clean(p,"role")=="student" && clean(p,"verification_status")=="active" && clean(p,"batch_id").isNotBlank()) View.VISIBLE else View.GONE
                h.toggle.contentDescription="CR access for ${h.name.text}"
                h.toggle.isEnabled=p.getString("profile_id") !in pendingCRChanges
                h.toggle.setOnCheckedChangeListener { _,checked ->
                    h.toggle.setOnCheckedChangeListener(null); h.toggle.isChecked=p.optBoolean("is_cr"); if(h.bindingAdapterPosition!=RecyclerView.NO_POSITION) notifyItemChanged(h.bindingAdapterPosition)
                    MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle(if(checked) "Assign class representative?" else "Revoke CR access?")
                        .setMessage("${h.name.text} · ${clean(p,"batch_name")}").setNegativeButton("Cancel",null).setPositiveButton("Confirm") { _,_ ->
                            val id=p.getString("profile_id")
                            if(!pendingCRChanges.add(id)) return@setPositiveButton
                            notifyDataSetChanged()
                            lifecycleScope.launch {
                                try {
                                    val updated=ClassMateAuthApi.rpc(if(checked) "assign_cr" else "revoke_cr",JSONObject().put("target_profile",p.getString("profile_id")).apply { if(checked) put("target_batch",p.getString("batch_id")) })
                                    p.put("is_cr",updated.optBoolean("is_cr")); setResult(RESULT_OK)
                                } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
                                finally { pendingCRChanges.remove(id); notifyDataSetChanged() }
                            }
                        }.show()
                }
                h.itemView.setOnClickListener { personDetails(p) }
            }
        }
        list.adapter=adapter
        fun fetch(reset: Boolean) {
            if(!reset && (loading || !more)) return
            if(reset) { epoch++; searchJob?.cancel(); people.clear(); offset=0; more=true; query=search.text.toString().trim(); adapter.notifyDataSetChanged() }
            val stamp=epoch; loading=true; status.visibility=View.VISIBLE; status.text="Loading people…"
            searchJob=lifecycleScope.launch {
                try {
                    if(reset) delay(250)
                    val result=rows(JSONArray(ClassMateAuthApi.rpcText("owner_people",JSONObject().put("query_text",query).put("result_offset",offset))))
                    if(stamp!=epoch) return@launch
                    people.addAll(result); offset+=result.size; more=result.size==50; adapter.notifyDataSetChanged(); status.text=if(people.isEmpty()) "No matching people" else "${people.size}${if(more) "+" else ""} people"
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch(e: Exception) { status.text="Could not load people. Tap to retry." }
                finally { if(stamp==epoch) loading=false }
            }
        }
        search.doAfterTextChanged { fetch(true) }; status.setOnClickListener { fetch(people.isEmpty()) }
        list.addOnScrollListener(object: RecyclerView.OnScrollListener() { override fun onScrolled(r: RecyclerView,dx: Int,dy: Int) { if(dy>0 && (r.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=people.size-8) fetch(false) } })
        fetch(true)
    }
    private class PersonHolder(v: View,val avatar: ImageView,val name: TextView,val detail: TextView,val toggle: SwitchMaterial): RecyclerView.ViewHolder(v)
    private fun personDetails(p: JSONObject) {
        val f=ClassMateFormUi(this); f.label(listOf(clean(p,"student_id"),clean(p,"batch_name"),clean(p,"verification_status"),if(p.optBoolean("is_cr")) clean(p,"cr_valid_until").takeIf { it.isNotBlank() }?.let { "CR until ${it.take(10)}" }.orEmpty() else "").filter { it.isNotBlank() }.joinToString(" · "))
        val b=MaterialAlertDialogBuilder(this).setTitle(clean(p,"full_name")).setView(f.scroll).setNegativeButton("Close",null)
        if(clean(p,"role")=="student" && clean(p,"verification_status") in setOf("pending","rejected") && clean(p,"profile_source")=="manual") {
            val id=f.field("Corrected student ID").apply { setText(clean(p,"student_id")) }; val error=f.status()
            b.setPositiveButton("Approve",null)
            if(clean(p,"verification_status")=="pending") b.setNeutralButton("Reject",null)
            lifecycleScope.launch {
                try {
                    val dept=ClassMateAuthApi.rows("profiles","select=department_id&id=eq.${p.getString("profile_id")}").optJSONObject(0)?.let { clean(it,"department_id") }.orEmpty()
                    val batches=rows(ClassMateAuthApi.rows("batches","select=id,batch_number,academic_session&is_active=eq.true${if(dept.isNotBlank()) "&department_id=eq.$dept" else ""}&order=batch_number"))
                    val choice=f.choice("Batch",batches.map { "Batch ${it.optInt("batch_number")} · Session ${ClassMateAcademicSession.format(it.optInt("academic_session"))}" })
                    val d=b.create(); d.show()
                    d.getButton(-1).setOnClickListener {
                        val batch=batches.getOrNull(choice.selectedItemPosition) ?: return@setOnClickListener
                        if(busy) return@setOnClickListener
                        busy=true; d.getButton(-1).isEnabled=false
                        lifecycleScope.launch {
                            try { ClassMateAuthApi.rpc("approve_student_profile",JSONObject().put("target_profile",p.getString("profile_id")).put("corrected_student_id",id.text.toString().trim()).put("target_batch",batch.getString("id")).put("corrected_session",batch.optInt("academic_session"))); setResult(RESULT_OK); d.dismiss(); recreate() }
                            catch(e: Exception) { error.visibility=View.VISIBLE; error.text=e.message }
                            finally { busy=false; d.getButton(-1).isEnabled=true }
                        }
                    }
                    d.getButton(-3)?.setOnClickListener {
                        val reason=ClassMateFormUi(this@ClassMateConfigurationActivity); val input=reason.field("Reason"); val e=reason.status()
                        val reject=MaterialAlertDialogBuilder(this@ClassMateConfigurationActivity).setTitle("Reject profile").setView(reason.scroll).setNegativeButton("Cancel",null).setPositiveButton("Reject",null).create(); reject.show()
                        reject.getButton(-1).setOnClickListener { if(!busy) { busy=true; reject.getButton(-1).isEnabled=false; lifecycleScope.launch {
                            try { ClassMateAuthApi.rpc("reject_student_profile",JSONObject().put("target_profile",p.getString("profile_id")).put("reason",input.text.toString().trim())); setResult(RESULT_OK); reject.dismiss(); d.dismiss(); recreate() }
                            catch(ex: Exception) { e.visibility=View.VISIBLE; e.text=ex.message }
                            finally { busy=false; reject.getButton(-1).isEnabled=true }
                        } } }
                    }
                } catch(e: Exception) { Toast.makeText(this@ClassMateConfigurationActivity,e.message,Toast.LENGTH_LONG).show() }
            }
        } else b.show()
    }
}
