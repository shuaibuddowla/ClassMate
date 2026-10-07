package com.shuaib.classmate.activities

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/** Real notice/upload editor using the existing RPC and protected upload pipeline. */
class ClassMatePublishActivity : AppCompatActivity() {
    private val batch by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private val editingFile by lazy { intent.getStringExtra("resource")?.let { JSONObject(it) } }
    private val upload by lazy { intent.getBooleanExtra("upload", false) }
    private lateinit var form: ClassMateFormUi
    private lateinit var submit: MaterialButton
    private lateinit var status: TextView
    private lateinit var picker: MaterialButton
    private lateinit var name: com.google.android.material.textfield.TextInputEditText
    private var uri: Uri? = null
    private var selectedFileLabel = ""
    private var restoredFileName = ""
    private var busy = false
    private var courses = emptyList<Pair<String, String>>()
    private var aiAllowed=false
    private var aiRequest:String?=null
    private var aiText=""
    private var aiHistory=org.json.JSONArray()
    private var restoredAiDraft=""
    private var aiDraft:com.google.android.material.textfield.TextInputEditText?=null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected == null) return@registerForActivityResult
        uri = selected
        runCatching { contentResolver.takePersistableUriPermission(selected, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val label = contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Selected file"
        selectedFileLabel = label
        if (::picker.isInitialized) picker.text = "Change file · $label"
        if (::name.isInitialized && name.text.isNullOrBlank()) name.setText(label.substringBeforeLast('.'))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        aiRequest=state?.getString("ai_request"); aiText=state?.getString("ai_request_text").orEmpty()
        restoredAiDraft=state?.getString("ai_draft").orEmpty()
        aiHistory=org.json.JSONArray(state?.getString("ai_history") ?: "[]")
        ClassMateAuthApi.attach(applicationContext)
        uri = state?.getString("file_uri")?.let(Uri::parse)
        selectedFileLabel = state?.getString("file_label").orEmpty()
        restoredFileName = state?.getString("file_name").orEmpty()
        if (batch.isBlank()) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.cm_background)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val toolbar = MaterialToolbar(this).apply {
            title = if (editingFile != null) "Edit library file" else if (upload) "Add library file" else "Post notice"
            setTitleTextColor(getColor(R.color.cm_text_primary)); setNavigationIcon(R.drawable.ic_chevron_left)
            navigationIcon?.setTint(getColor(R.color.cm_text_primary))
            setNavigationOnClickListener { if (!busy) finish() }
        }
        root.addView(toolbar)
        form = ClassMateFormUi(this)
        form.label(intent.getStringExtra("batch_label").orEmpty()).apply { textSize = 14f }
        root.addView(form.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        submit = MaterialButton(this).apply { text = if (editingFile != null) "Save changes" else if (upload) "Upload file" else "Post notice"; isAllCaps = false; isEnabled = false; cornerRadius = dp(16) }
        root.addView(submit, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(24), dp(12), dp(24), dp(16)) })
        setContentView(root)
        val loading = form.label("Loading editor…")
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!busy) finish() else Toast.makeText(this@ClassMatePublishActivity, "Please wait for completion", Toast.LENGTH_SHORT).show() }
        })
        lifecycleScope.launch {
            try {
                val semester = ClassMateAuthApi.rows("semesters", "select=id&batch_id=eq.$batch&status=eq.active&limit=1").optJSONObject(0)?.optString("id")
                val offerings = if (semester == null) org.json.JSONArray() else ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester")
                val catalog = ClassMateCourses.catalog(batch)
                val names = (0 until catalog.length()).associate { i -> catalog.getJSONObject(i).let { it.getString("id") to "${it.getString("course_code")} · ${it.getString("course_title")}" } }
                val teacher = intent.getStringExtra("role") == "teacher"
                val assigned = if (teacher) ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.${intent.getStringExtra("profile_id")}&active=eq.true").let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it).getString("semester_course_id") }.toSet()
                } else null
                courses = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                    .filter { assigned == null || it.getString("id") in assigned }
                    .map { it.getString("id") to (names[it.getString("course_id")] ?: "Course") }
                editingFile?.optString("semester_course_id")?.takeIf { it.isNotBlank() && it != "null" && courses.none { c -> c.first == it } }?.let { id ->
                    val offering = ClassMateAuthApi.rows("semester_courses", "select=course_id&id=eq.$id").optJSONObject(0)
                    offering?.optString("course_id")?.let { courseId -> courses = courses + (id to (names[courseId] ?: "Current subject")) }
                }
                loading.visibility = View.GONE
                if (upload) buildUpload() else {
                    aiAllowed=ClassMateAuthApi.rpcText("ai_can_write",JSONObject().put("target_batch",batch)).trim()=="true"
                    buildNotice()
                }
            } catch (e: Exception) {
                loading.text = "Could not load editor. ${e.message}"
                submit.text = "Retry"; submit.isEnabled = true
                submit.setOnClickListener { recreate() }
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("file_uri", uri?.toString()); out.putString("file_label", selectedFileLabel)
        if (::name.isInitialized) out.putString("file_name", name.text.toString())
        out.putString("ai_request",aiRequest); out.putString("ai_request_text",aiText); out.putString("ai_draft",aiDraft?.text?.toString() ?: restoredAiDraft); out.putString("ai_history",aiHistory.toString())
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun dropdown(label: String, options: List<String>): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        val field = MaterialAutoCompleteTextView(box.context).apply {
            inputType = android.text.InputType.TYPE_NULL
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, options))
            if (options.isNotEmpty()) setText(options.first(), false)
            setTextColor(getColor(R.color.cm_text_primary))
        }
        box.addView(field); form.panel.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return box to field
    }
    private fun buildNotice() {
        val teacher = intent.getStringExtra("role") == "teacher"
        form.label("Notice type")
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ai = com.google.android.material.radiobutton.MaterialRadioButton(this).apply { id=View.generateViewId(); text="AI compose"; setTextColor(getColor(R.color.cm_text_primary)) }
        val general = com.google.android.material.radiobutton.MaterialRadioButton(this).apply { id = View.generateViewId(); text = "General notice"; setTextColor(getColor(R.color.cm_text_primary)) }
        val cancellation = com.google.android.material.radiobutton.MaterialRadioButton(this).apply { id = View.generateViewId(); text = "Class cancellation"; setTextColor(getColor(R.color.cm_text_primary)) }
        if(aiAllowed) choices.addView(ai)
        choices.addView(general); choices.addView(cancellation); form.panel.addView(choices)
        val rough=form.field("Your rough notice · English or Bengali",true).apply { filters=arrayOf(android.text.InputFilter.LengthFilter(8000)); setText(restoredAiDraft) }; aiDraft=rough
        val aiHint=form.label("AI organizes and posts in English. For cancellations include the course and today/tomorrow. Start with /silent for no phone alerts.")
        val title = form.field("Notice title")
        val message = form.field("Message · links supported", true)
        val silentHint=form.label("Start with /silent to post without a push notification.")
        val (courseBox, course) = dropdown(if (teacher) "Assigned course" else "Cancelled course", courses.map { it.second })
        val (dateBox, date) = dropdown("When?", listOf("Today", "Tomorrow"))
        status = form.status()
        choices.setOnCheckedChangeListener { _, checked ->
            val cancelled = checked == cancellation.id
            val composing=checked==ai.id
            (rough.parent.parent as View).visibility=if(composing) View.VISIBLE else View.GONE
            aiHint.visibility=if(composing) View.VISIBLE else View.GONE
            (title.parent.parent as View).visibility = if (cancelled || composing) View.GONE else View.VISIBLE
            (message.parent.parent as View).visibility = if (cancelled || composing) View.GONE else View.VISIBLE
            silentHint.visibility=if(cancelled || composing) View.GONE else View.VISIBLE
            courseBox.visibility = if (cancelled || teacher) View.VISIBLE else View.GONE
            dateBox.visibility = if (cancelled) View.VISIBLE else View.GONE
            submit.text=if(composing) "Compose & post" else "Post notice"
        }
        choices.check(if(aiAllowed) ai.id else general.id)
        submit.isEnabled = true
        submit.setOnClickListener {
            if(choices.checkedRadioButtonId==ai.id) {
                val value=rough.text.toString().trim()
                if(value.isBlank()) { rough.error="Write your notice first"; return@setOnClickListener }
                if(busy) return@setOnClickListener
                if(aiText!=value) { aiRequest=java.util.UUID.randomUUID().toString(); aiText=value }
                aiRequest=aiRequest ?: java.util.UUID.randomUUID().toString()
                busy=true; submit.isEnabled=false; rough.isEnabled=false
                for(i in 0 until choices.childCount) choices.getChildAt(i).isEnabled=false
                status.visibility=View.VISIBLE; status.text="Composing…"
                lifecycleScope.launch {
                    try {
                        val response=ClassMateAuthApi.ai(JSONObject().put("mode","compose").put("batch_id",batch).put("request_id",aiRequest).put("text",value).put("history",aiHistory))
                        val actions=response.optJSONObject("result")?.optJSONArray("actions")
                        if((actions?.length() ?: 0)>0) {
                            val createdNotice=actions?.optJSONObject(0)?.optJSONObject("outcome")
                            val data=Intent()
                            if(createdNotice!=null) data.putExtra("created_notice",createdNotice.toString())
                            setResult(RESULT_OK,data)
                            finish()
                        }
                        else {
                            val reply=response.optJSONObject("plan")?.optString("message") ?: response.optJSONObject("result")?.optString("message") ?: "Add the missing course or date."
                            status.text=reply; aiHistory.put(JSONObject().put("role","user").put("text",value)); aiHistory.put(JSONObject().put("role","assistant").put("text",reply)); aiRequest=null
                            while(aiHistory.length()>8) aiHistory.remove(0)
                        }
                    } catch(e:Exception) { status.text="${e.message}\nTry again to retry the same request." }
                    finally { busy=false; submit.isEnabled=true; rough.isEnabled=true; for(i in 0 until choices.childCount) choices.getChildAt(i).isEnabled=true }
                }
                return@setOnClickListener
            }
            val cancelled = choices.checkedRadioButtonId == cancellation.id
            val selected = courses.firstOrNull { it.second == course.text.toString() }
            if ((cancelled || teacher) && selected == null) { courseBox.error = "No assigned course available"; return@setOnClickListener }
            if (!cancelled && title.text.isNullOrBlank()) { title.error = "Enter a title"; return@setOnClickListener }
            perform {
                if (cancelled) ClassMateAuthApi.rpc("post_cancellation_notice", JSONObject().put("target_batch", batch)
                    .put("target_course", selected!!.first).put("change_date", LocalDate.now().plusDays(if (date.text.toString() == "Tomorrow") 1 else 0).toString()))
                else ClassMateAuthApi.rpc("post_notice", JSONObject().put("target_batch", batch).put("target_course", if (teacher) selected!!.first else JSONObject.NULL)
                    .put("notice_title", title.text.toString().trim()).put("notice_body", message.text.toString().trim()))
            }
        }
    }
    private fun buildUpload() {
        if (courses.isEmpty()) { form.label("No active subject is available for uploading."); return }
        val (_, category) = dropdown("Category", listOf("Notes", "Slides", "Questions", "Syllabus", "Other"))
        val (_, subject) = dropdown("Subject", courses.map { it.second })
        name = form.field("File name").apply { setText(editingFile?.optString("title") ?: restoredFileName.ifBlank { selectedFileLabel.substringBeforeLast('.') }) }
        picker = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (uri == null) "Choose file" else "Change file · $selectedFileLabel"; isAllCaps = false; cornerRadius = dp(14)
            setOnClickListener { if (!busy) pickFile.launch(arrayOf("*/*")) }
        }
        editingFile?.let { file ->
            category.setText(file.optString("category").replaceFirstChar { it.uppercase() }, false)
            courses.firstOrNull { it.first == file.optString("semester_course_id") }?.let { subject.setText(it.second, false) }
            picker.visibility = View.GONE
            form.label("Update the name, category or subject. The uploaded file stays unchanged.")
        }
        form.panel.addView(picker, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(16) })
        status = form.status(); submit.isEnabled = true
        submit.setOnClickListener {
            val selected = uri
            if (name.text.isNullOrBlank()) { name.error = "Enter a file name"; return@setOnClickListener }
            if (selected == null && editingFile == null) { status.visibility = View.VISIBLE; status.text = "Choose a file to upload"; return@setOnClickListener }
            val course = courses.firstOrNull { it.second == subject.text.toString() } ?: return@setOnClickListener
            val title = name.text.toString().trim(); val kind = category.text.toString().lowercase()
            perform {
                if (editingFile != null) {
                    ClassMateAuthApi.rpc("edit_resource_metadata", JSONObject().put("target_resource", editingFile!!.getString("id"))
                        .put("target_title", title).put("target_category", kind).put("target_course", course.first))
                    null
                } else {
                    val size = contentResolver.query(selected!!, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } ?: error("Could not read file size")
                    ClassMateAuthApi.uploadResource(contentResolver, selected, batch, course.first, title,
                        contentResolver.getType(selected) ?: "application/octet-stream", size, kind)
                    null
                }
            }
        }
    }
    private fun perform(action: suspend () -> JSONObject?) {
        if (busy) return
        busy = true; submit.isEnabled = false; status.visibility = View.VISIBLE
        status.text = if (editingFile != null) "Saving changes…" else if (upload) "Uploading file…" else "Posting notice…"
        lifecycleScope.launch {
            try {
                val outcome = action()
                val data = Intent()
                if (outcome != null) data.putExtra("created_notice", outcome.toString())
                setResult(RESULT_OK, data)
                finish()
            }
            catch (e: Exception) { status.text = e.message ?: "Could not complete. Try again." }
            finally { busy = false; submit.isEnabled = true }
        }
    }
}
