package com.shuaib.classmate.activities

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.workers.LibraryUploadManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class CourseEntry(
    val offeringId: String,
    val code: String,
    val title: String
) {
    val displayLabel: String get() = if (title.isNotBlank()) "$code · $title" else code
    val second: String get() = displayLabel
    val first: String get() = offeringId
    override fun toString(): String = displayLabel
}

/** Redesigned notice & library file publish editor. */
class ClassMatePublishActivity : AppCompatActivity() {
    private val batch by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private val editingFile by lazy { intent.getStringExtra("resource")?.let { JSONObject(it) } }
    private val upload by lazy { intent.getBooleanExtra("upload", false) }
    private lateinit var form: ClassMateFormUi
    private lateinit var submit: MaterialButton
    private lateinit var status: TextView
    private lateinit var name: EditText
    private var uri: Uri? = null
    private var selectedFileLabel = ""
    private var selectedFileSize = 0L
    private var restoredFileName = ""
    private var busy = false
    private var courses = emptyList<CourseEntry>()
    private var uploadMode = "file"
    private var restoredLinkUrl = ""
    private var urlInput: EditText? = null
    private var currentUploadTaskId: String? = null

    // Callbacks and references for UI updates
    private var onPickedFileChanged: (() -> Unit)? = null
    private var onClipboardCheckRequested: (() -> Unit)? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected == null) return@registerForActivityResult
        uri = selected
        runCatching { contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val label = contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Selected file"
        selectedFileLabel = label

        val size = contentResolver.query(selected, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L
        selectedFileSize = size

        if (::name.isInitialized && name.text.isNullOrBlank()) {
            name.setText(label.substringBeforeLast('.'))
        }
        onPickedFileChanged?.invoke()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val editRawLink = editingFile?.optString("link_url")?.trim().orEmpty()
        val sanitizedEditLink = if (editRawLink.isBlank() || editRawLink.equals("null", ignoreCase = true)) "" else editRawLink
        val isEditingLink = editingFile?.optString("file_type") == "link" || sanitizedEditLink.isNotBlank()
        uploadMode = state?.getString("upload_mode") ?: (if (isEditingLink) "link" else "file")
        restoredLinkUrl = (state?.getString("link_url") ?: sanitizedEditLink).let {
            if (it.isBlank() || it.equals("null", ignoreCase = true)) "" else it
        }

        ClassMateAuthApi.attach(applicationContext)
        uri = state?.getString("file_uri")?.let(Uri::parse)
        selectedFileLabel = state?.getString("file_label").orEmpty()
        selectedFileSize = state?.getLong("file_size", 0L) ?: 0L
        restoredFileName = state?.getString("file_name").orEmpty()
        currentUploadTaskId = state?.getString("upload_task_id")

        if (batch.isBlank()) { finish(); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cm_background))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        var badgeSemester: TextView? = null
        if (upload) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            val btnBack = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(getColor(R.color.cm_text_primary))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                val outVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
                setBackgroundResource(outVal.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(4) }
                setOnClickListener {
                    if (currentUploadTaskId != null) {
                        Toast.makeText(this@ClassMatePublishActivity, "Upload continuing in background", Toast.LENGTH_SHORT).show()
                        finish()
                    } else if (!busy) {
                        finish()
                    }
                }
            }
            val headerTitles = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val tvHeaderTitle = TextView(this).apply {
                text = if (editingFile != null) {
                    if (isEditingLink) "Edit Drive link" else "Edit library file"
                } else {
                    "Add Library File"
                }
                textSize = 17.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_text_primary))
            }
            val tvHeaderSub = TextView(this).apply {
                text = intent.getStringExtra("batch_label").orEmpty()
                textSize = 11.5f
                setTextColor(getColor(R.color.cm_text_muted))
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            headerTitles.addView(tvHeaderTitle)
            headerTitles.addView(tvHeaderSub)

            badgeSemester = TextView(this).apply {
                text = "Semester"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_primary))
                setBackgroundResource(R.drawable.bg_badge_semester)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }

            header.addView(btnBack)
            header.addView(headerTitles)
            header.addView(badgeSemester)

            val headerDivider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1))
                setBackgroundColor(getColor(R.color.cm_divider))
            }
            root.addView(header)
            root.addView(headerDivider)

            form = ClassMateFormUi(this)
            root.addView(form.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val toolbar = MaterialToolbar(this).apply {
                title = "Post notice"
                setTitleTextColor(getColor(R.color.cm_text_primary))
                setNavigationIcon(R.drawable.ic_chevron_left)
                navigationIcon?.setTint(getColor(R.color.cm_text_primary))
                setNavigationOnClickListener {
                    if (currentUploadTaskId != null) {
                        Toast.makeText(this@ClassMatePublishActivity, "Upload continuing in background", Toast.LENGTH_SHORT).show()
                        finish()
                    } else if (!busy) {
                        finish()
                    }
                }
            }
            root.addView(toolbar)
            form = ClassMateFormUi(this)
            form.label(intent.getStringExtra("batch_label").orEmpty()).apply { textSize = 14f }
            root.addView(form.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        submit = MaterialButton(this).apply {
            val isLink = isEditingLink || uploadMode == "link"
            text = if (editingFile != null) "Save changes" else if (upload) (if (isLink) "Share Drive link" else "Upload file") else "Post notice"
            isAllCaps = false
            isEnabled = false
            cornerRadius = dp(16)
            setBackgroundColor(getColor(R.color.cm_primary))
        }
        root.addView(submit, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(dp(20), dp(10), dp(20), dp(14)) })
        setContentView(root)

        val loading = form.label("Loading editor…")

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentUploadTaskId != null) {
                    Toast.makeText(this@ClassMatePublishActivity, "Upload continuing in background", Toast.LENGTH_SHORT).show()
                    finish()
                } else if (!busy) {
                    finish()
                } else {
                    Toast.makeText(this@ClassMatePublishActivity, "Please wait for completion", Toast.LENGTH_SHORT).show()
                }
            }
        })

        lifecycleScope.launch {
            try {
                val semesterObj = ClassMateAuthApi.rows("semesters", "select=id,semester_number&batch_id=eq.$batch&status=eq.active&limit=1").optJSONObject(0)
                val semester = semesterObj?.optString("id")
                val semNum = semesterObj?.optInt("semester_number", 0) ?: 0
                val semTitle = if (semNum > 0) "Semester $semNum" else "Semester 3"
                badgeSemester?.text = semTitle
                val offerings = if (semester == null) JSONArray() else ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester")
                val catalog = ClassMateCourses.catalog(batch)
                val catalogMap = (0 until catalog.length()).map { catalog.getJSONObject(it) }.associateBy { it.getString("id") }

                val teacher = intent.getStringExtra("role") == "teacher"
                val assigned = if (teacher) ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.${intent.getStringExtra("profile_id")}&active=eq.true").let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it).getString("semester_course_id") }.toSet()
                } else null

                courses = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                    .filter { assigned == null || it.getString("id") in assigned }
                    .map { off ->
                        val offId = off.getString("id")
                        val cat = catalogMap[off.getString("course_id")]
                        val code = cat?.optString("course_code") ?: "Course"
                        val title = cat?.optString("course_title") ?: ""
                        CourseEntry(offId, code, title)
                    }

                editingFile?.optString("semester_course_id")?.takeIf { it.isNotBlank() && it != "null" && courses.none { c -> c.offeringId == it } }?.let { id ->
                    val offering = ClassMateAuthApi.rows("semester_courses", "select=course_id&id=eq.$id").optJSONObject(0)
                    val cat = offering?.optString("course_id")?.let { catalogMap[it] }
                    courses = courses + CourseEntry(id, cat?.optString("course_code") ?: "Course", cat?.optString("course_title") ?: "Current subject")
                }

                loading.visibility = View.GONE
                if (upload) buildUpload() else {
                    buildNotice()
                }
            } catch (e: Exception) {
                loading.text = "Could not load editor. ${e.message}"
                submit.text = "Retry"
                submit.isEnabled = true
                submit.setOnClickListener { recreate() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onClipboardCheckRequested?.invoke()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("upload_mode", uploadMode)
        outState.putString("link_url", urlInput?.text?.toString() ?: restoredLinkUrl)
        outState.putString("file_uri", uri?.toString())
        outState.putString("file_label", selectedFileLabel)
        outState.putLong("file_size", selectedFileSize)
        if (::name.isInitialized) outState.putString("file_name", name.text.toString())
        outState.putString("upload_task_id", currentUploadTaskId)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mb)
        else String.format(java.util.Locale.US, "%.0f KB", kb)
    }

    private fun createDropdown(label: String, options: List<String>): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        val field = MaterialAutoCompleteTextView(box.context).apply {
            inputType = android.text.InputType.TYPE_NULL
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, options))
            if (options.isNotEmpty()) setText(options.first(), false)
            setTextColor(getColor(R.color.cm_text_primary))
        }
        box.addView(field)
        return box to field
    }

    private fun dropdown(label: String, options: List<String>): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val (box, field) = createDropdown(label, options)
        form.panel.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return box to field
    }

    private fun buildNotice() {
        val teacher = intent.getStringExtra("role") == "teacher"
        form.label("Notice type")
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val general = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "General notice"
            setTextColor(getColor(R.color.cm_text_primary))
        }
        val cancellation = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Class cancellation"
            setTextColor(getColor(R.color.cm_text_primary))
        }
        choices.addView(general)
        choices.addView(cancellation)
        form.panel.addView(choices)

        val title = form.field("Notice title")
        val titleBox = title.parent.parent as View
        titleBox.visibility = View.GONE

        val message = form.field("Notice message · English, Banglish or Bengali", true).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
        }
        val messageBox = message.parent.parent as View

        val silentHint = form.label("Banglish auto-converts to Bengali. English remains English. Start with /silent to post without push alerts.")
        val (courseBox, course) = dropdown(if (teacher) "Assigned course" else "Cancelled course", courses.map { it.second })
        val (dateBox, date) = dropdown("When?", listOf("Today", "Tomorrow"))
        status = form.status()

        choices.setOnCheckedChangeListener { _, checked ->
            val cancelled = checked == cancellation.id
            if (cancelled) {
                titleBox.visibility = View.GONE
                messageBox.visibility = View.GONE
                silentHint.visibility = View.GONE
                courseBox.visibility = View.VISIBLE
                dateBox.visibility = View.VISIBLE
            } else {
                titleBox.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                messageBox.visibility = View.VISIBLE
                silentHint.visibility = View.VISIBLE
                courseBox.visibility = if (teacher) View.VISIBLE else View.GONE
                dateBox.visibility = View.GONE
            }
            submit.text = "Post notice"
        }
        choices.check(general.id)
        submit.text = "Post notice"
        submit.isEnabled = true

        submit.setOnClickListener {
            val cancelled = choices.checkedRadioButtonId == cancellation.id
            val selected = courses.firstOrNull { it.second == course.text.toString() }

            if ((cancelled || teacher) && selected == null) {
                courseBox.error = "No assigned course available"
                return@setOnClickListener
            }

            if (cancelled) {
                perform {
                    ClassMateAuthApi.rpc(
                        "post_cancellation_notice",
                        JSONObject().put("target_batch", batch)
                            .put("target_course", selected!!.first)
                            .put("change_date", LocalDate.now().plusDays(if (date.text.toString() == "Tomorrow") 1 else 0).toString())
                    )
                }
                return@setOnClickListener
            }

            // General Notice
            val rawBody = message.text.toString().trim()
            if (rawBody.isBlank()) {
                message.error = "Write your notice message first"
                return@setOnClickListener
            }

            val manualTitle = title.text?.toString()?.trim().orEmpty()
            if (titleBox.visibility == View.VISIBLE && manualTitle.isNotBlank()) {
                perform {
                    ClassMateAuthApi.rpc(
                        "post_notice",
                        JSONObject().put("target_batch", batch)
                            .put("target_course", if (teacher) selected!!.first else JSONObject.NULL)
                            .put("notice_title", manualTitle)
                            .put("notice_body", rawBody)
                    )
                }
                return@setOnClickListener
            }

            if (busy) return@setOnClickListener
            busy = true
            submit.isEnabled = false
            status.visibility = View.VISIBLE
            status.text = "Detecting title & formatting notice with ClassMate AI…"

            lifecycleScope.launch {
                try {
                    val reqId = java.util.UUID.randomUUID().toString()
                    val response = ClassMateAuthApi.ai(
                        JSONObject()
                            .put("mode", "auto_notice")
                            .put("batch_id", batch)
                            .put("request_id", reqId)
                            .put("text", rawBody)
                    )
                    val result = response.optJSONObject("result")
                    val detectedTitle = result?.optString("title")?.trim().orEmpty()
                    val formattedBody = result?.optString("body")?.trim().orEmpty()

                    if (detectedTitle.isBlank() || formattedBody.isBlank()) {
                        throw IllegalStateException("AI returned empty notice details")
                    }

                    status.text = "Publishing notice to batch…"
                    val postRes = ClassMateAuthApi.rpc(
                        "post_notice",
                        JSONObject().put("target_batch", batch)
                            .put("target_course", if (teacher) selected!!.first else JSONObject.NULL)
                            .put("notice_title", detectedTitle)
                            .put("notice_body", formattedBody)
                    )

                    Toast.makeText(this@ClassMatePublishActivity, "Notice published successfully!", Toast.LENGTH_SHORT).show()
                    val data = Intent().putExtra("created_notice", postRes.toString())
                    setResult(RESULT_OK, data)
                    finish()
                } catch (e: Exception) {
                    busy = false
                    submit.isEnabled = true
                    titleBox.visibility = View.VISIBLE
                    title.requestFocus()
                    status.text = "ClassMate AI title detection failed. Please enter a title manually and tap Post notice."
                    Toast.makeText(
                        this@ClassMatePublishActivity,
                        "Notice title detection failed. Please enter title manually.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildUpload() {
        if (courses.isEmpty()) { form.label("No active subject is available for uploading."); return }

        val isEditing = editingFile != null
        val editRawLink = editingFile?.optString("link_url")?.trim().orEmpty()
        val sanitizedEditLink = if (editRawLink.isBlank() || editRawLink.equals("null", ignoreCase = true)) "" else editRawLink
        val isEditingLink = isEditing && (editingFile!!.optString("file_type") == "link" || sanitizedEditLink.isNotBlank())
        if (isEditing) {
            uploadMode = if (isEditingLink) "link" else "file"
        }

        var updateModeVisibility: (() -> Unit)? = null

        // --- 1. Top Underline Tabs [ Upload Document | Google Drive Link ] ---
        var tvTabUpload: TextView? = null
        var indTabUpload: View? = null
        var tvTabLink: TextView? = null
        var indTabLink: View? = null

        fun updateTabsUi() {
            val isLink = uploadMode == "link"
            if (!isLink) {
                tvTabUpload?.setTypeface(null, Typeface.BOLD)
                tvTabUpload?.setTextColor(getColor(R.color.cm_primary))
                indTabUpload?.visibility = View.VISIBLE

                tvTabLink?.setTypeface(null, Typeface.NORMAL)
                tvTabLink?.setTextColor(getColor(R.color.cm_text_muted))
                indTabLink?.visibility = View.INVISIBLE
            } else {
                tvTabUpload?.setTypeface(null, Typeface.NORMAL)
                tvTabUpload?.setTextColor(getColor(R.color.cm_text_muted))
                indTabUpload?.visibility = View.INVISIBLE

                tvTabLink?.setTypeface(null, Typeface.BOLD)
                tvTabLink?.setTextColor(getColor(R.color.cm_primary))
                indTabLink?.visibility = View.VISIBLE
            }
            if (!isEditing) {
                submit.text = if (isLink) "Share Drive link" else "Upload file"
            }
        }

        if (!isEditing) {
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(2)
                }
            }
            val tabUpload = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                isClickable = true
                isFocusable = true
                setPadding(0, dp(4), 0, 0)
            }
            tvTabUpload = TextView(this).apply {
                text = "Upload Document"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_primary))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(6))
            }
            indTabUpload = View(this).apply {
                val indH = (2.5f * resources.displayMetrics.density).toInt().coerceAtLeast(dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(84), indH)
                setBackgroundColor(getColor(R.color.cm_primary))
            }
            tabUpload.addView(tvTabUpload)
            tabUpload.addView(indTabUpload)

            val tabLink = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                isClickable = true
                isFocusable = true
                setPadding(0, dp(4), 0, 0)
            }
            tvTabLink = TextView(this).apply {
                text = "Google Drive Link"
                textSize = 13f
                setTypeface(null, Typeface.NORMAL)
                setTextColor(getColor(R.color.cm_text_muted))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(6))
            }
            indTabLink = View(this).apply {
                val indH = (2.5f * resources.displayMetrics.density).toInt().coerceAtLeast(dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(84), indH)
                setBackgroundColor(getColor(R.color.cm_primary))
                visibility = View.INVISIBLE
            }
            tabLink.addView(tvTabLink)
            tabLink.addView(indTabLink)

            tabRow.addView(tabUpload)
            tabRow.addView(tabLink)

            val tabDivider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(14) }
                setBackgroundColor(getColor(R.color.cm_divider))
            }

            form.panel.addView(tabRow)
            form.panel.addView(tabDivider)

            tabUpload.setOnClickListener {
                if (uploadMode != "file") {
                    uploadMode = "file"
                    updateModeVisibility?.invoke()
                }
            }
            tabLink.setOnClickListener {
                if (uploadMode != "link") {
                    uploadMode = "link"
                    updateModeVisibility?.invoke()
                }
            }
        }

        // --- 2. Minimalist File Picker Card ---
        val filePickerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_picker_card_empty)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
            isClickable = true
            isFocusable = true
        }
        val fileIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundResource(R.drawable.bg_picker_pill)
        }
        val fileIconImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            setImageResource(R.drawable.ic_file_generic)
            setColorFilter(getColor(R.color.cm_primary))
        }
        fileIconFrame.addView(fileIconImg)

        val fileDetailsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        }
        val tvFileName = TextView(this).apply {
            text = "No file selected"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvFileMeta = TextView(this).apply {
            text = "PDF, DOCX up to 50MB"
            textSize = 11.5f
            setTextColor(getColor(R.color.cm_text_muted))
        }
        fileDetailsCol.addView(tvFileName)
        fileDetailsCol.addView(tvFileMeta)

        val btnActionChoose = TextView(this).apply {
            text = "Choose"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            setBackgroundResource(R.drawable.bg_picker_pill)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        val btnActionChange = TextView(this).apply {
            text = "Change"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            setBackgroundResource(R.drawable.bg_picker_pill)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
        }
        val btnActionRemove = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.cm_text_muted))
            setBackgroundResource(R.drawable.bg_picker_pill)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(6) }
            visibility = View.GONE
        }
        filePickerCard.addView(fileIconFrame)
        filePickerCard.addView(fileDetailsCol)
        filePickerCard.addView(btnActionChoose)
        filePickerCard.addView(btnActionChange)
        filePickerCard.addView(btnActionRemove)
        form.panel.addView(filePickerCard)

        // --- 3. Google Drive Link Section ---
        val linkSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        }
        val tvLinkLabel = TextView(this).apply {
            text = "Google Drive Link *"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        }
        val urlField = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_clean_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 13.5f
            setTextColor(getColor(R.color.cm_text_primary))
            setHintTextColor(getColor(R.color.cm_text_disabled))
            hint = "Paste Google Drive URL here"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            val initialUrl = restoredLinkUrl.ifBlank { sanitizedEditLink }
            setText(if (initialUrl.equals("null", ignoreCase = true)) "" else initialUrl)
        }
        urlInput = urlField

        val tvUrlError = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.cm_error))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        }

        // Inline dismissible clipboard banner: "📋 Link detected — tap to paste"
        var detectedClipboardUrl: String? = null
        val clipboardBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_toggle_container)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        }
        val bannerIcon = TextView(this).apply {
            text = "📋"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        val bannerText = TextView(this).apply {
            text = "Link detected — tap to paste"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val bannerDismiss = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.cm_text_secondary))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        clipboardBanner.addView(bannerIcon)
        clipboardBanner.addView(bannerText)
        clipboardBanner.addView(bannerDismiss)

        // Non-Drive link inline warning banner
        val driveWarning = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_warning_banner)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        val warnIcon = TextView(this).apply {
            text = "⚠️"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        val warnText = TextView(this).apply {
            text = "This doesn't look like a Drive link — make sure it's shared as 'Anyone with the link can view' or others won't be able to open it."
            textSize = 11.5f
            setTextColor(Color.parseColor("#B45309"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        driveWarning.addView(warnIcon)
        driveWarning.addView(warnText)

        linkSection.addView(tvLinkLabel)
        linkSection.addView(urlField)
        linkSection.addView(tvUrlError)
        linkSection.addView(clipboardBanner)
        linkSection.addView(driveWarning)
        form.panel.addView(linkSection)

        fun checkClipboard() {
            if (uploadMode != "link" || isEditing) {
                clipboardBanner.visibility = View.GONE
                return
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val raw = clip.getItemAt(0).coerceToText(this@ClassMatePublishActivity).toString().trim()
                val isUrl = raw.startsWith("http://", ignoreCase = true) ||
                            raw.startsWith("https://", ignoreCase = true) ||
                            raw.contains("drive.google.com", ignoreCase = true)
                val cur = urlField.text?.toString()?.trim().orEmpty()
                if (isUrl && raw != cur && cur.isBlank()) {
                    detectedClipboardUrl = raw
                    clipboardBanner.visibility = View.VISIBLE
                    return
                }
            }
            clipboardBanner.visibility = View.GONE
        }
        onClipboardCheckRequested = { checkClipboard() }

        val pasteBannerAction = {
            detectedClipboardUrl?.let { link ->
                urlField.setText(link)
                tvUrlError.visibility = View.GONE
                clipboardBanner.visibility = View.GONE
                Toast.makeText(this, "Link pasted from clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        clipboardBanner.setOnClickListener { pasteBannerAction() }
        bannerText.setOnClickListener { pasteBannerAction() }
        bannerDismiss.setOnClickListener { clipboardBanner.visibility = View.GONE }

        // --- 4. Display Title Section ---
        val tvNameLabel = TextView(this).apply {
            text = if (uploadMode == "link") "Resource title *" else "Display Title"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2)
                bottomMargin = dp(6)
            }
        }
        name = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_clean_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 13.5f
            setTextColor(getColor(R.color.cm_text_primary))
            setHintTextColor(getColor(R.color.cm_text_disabled))
            hint = "e.g. OOP Syllabus 2026"
            setSingleLine(true)
            setText(editingFile?.optString("title") ?: restoredFileName.ifBlank { selectedFileLabel.substringBeforeLast('.') })
        }
        val tvNameError = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.cm_error))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        }
        form.panel.addView(tvNameLabel)
        form.panel.addView(name)
        form.panel.addView(tvNameError)

        // --- 5. Subject Picker ---
        val colSubject = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        val tvSubLabel = TextView(this).apply {
            text = "Subject"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        }
        val boxSubject = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_clean_select)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
        }
        var selectedCourse: CourseEntry? = editingFile?.optString("semester_course_id")?.let { id ->
            courses.firstOrNull { it.offeringId == id }
        } ?: intent.getStringExtra("course_id")?.let { id ->
            courses.firstOrNull { it.offeringId == id }
        } ?: courses.firstOrNull()

        val tvSubjectValue = TextView(this).apply {
            text = selectedCourse?.displayLabel ?: "Select subject"
            textSize = 13f
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val arrowSubject = TextView(this).apply {
            text = "▾"
            textSize = 12f
            setTextColor(getColor(R.color.cm_text_disabled))
        }
        boxSubject.addView(tvSubjectValue)
        boxSubject.addView(arrowSubject)
        colSubject.addView(tvSubLabel)
        colSubject.addView(boxSubject)

        form.panel.addView(colSubject)

        boxSubject.setOnClickListener {
            if (courses.isEmpty()) return@setOnClickListener
            val labels = courses.map { it.displayLabel }.toTypedArray()
            val curIndex = courses.indexOfFirst { it.offeringId == selectedCourse?.offeringId }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("Select Subject")
                .setSingleChoiceItems(labels, curIndex) { dialog, which ->
                    selectedCourse = courses[which]
                    tvSubjectValue.text = selectedCourse?.displayLabel
                    dialog.dismiss()
                }
                .show()
        }

        editingFile?.let { file ->
            courses.firstOrNull { it.offeringId == file.optString("semester_course_id") }?.let {
                selectedCourse = it
                tvSubjectValue.text = it.displayLabel
            }
            filePickerCard.visibility = View.GONE
            val note = if (isEditingLink) "Update the title, link or subject." else "Update the title or subject. The uploaded file stays unchanged."
            form.label(note)
        }

        // --- 6. Determinate Progress Bar & Status ---
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(8))
        }
        val progressText = TextView(this).apply {
            text = "Uploading… 0%"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_primary))
        }
        val determinateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) }
        }
        progressContainer.addView(progressText)
        progressContainer.addView(determinateProgress)
        form.panel.addView(progressContainer)

        status = form.status()

        fun updatePickedFileUi() {
            if (uri != null && selectedFileLabel.isNotBlank()) {
                filePickerCard.setBackgroundResource(R.drawable.bg_picker_card_empty)
                tvFileName.text = selectedFileLabel
                tvFileMeta.text = "${formatFileSize(selectedFileSize)} · Ready"
                btnActionChoose.visibility = View.GONE
                btnActionChange.visibility = View.VISIBLE
                btnActionRemove.visibility = View.VISIBLE

                val tempPdf = PdfFile(
                    id = "picked",
                    title = selectedFileLabel,
                    fileType = selectedFileLabel.substringAfterLast('.', "pdf"),
                    materialType = "notes",
                    sizeBytes = selectedFileSize,
                    driveUrl = ""
                )
                val visuals = FileVisuals.getVisuals(tempPdf)
                FileVisuals.applyVisuals(fileIconImg, fileIconFrame, visuals)

                if (name.text.isNullOrBlank()) {
                    name.setText(selectedFileLabel.substringBeforeLast('.'))
                }
            } else {
                filePickerCard.setBackgroundResource(R.drawable.bg_picker_card_empty)
                tvFileName.text = "No file selected"
                tvFileMeta.text = "PDF, DOCX up to 50MB"
                btnActionChoose.visibility = View.VISIBLE
                btnActionChange.visibility = View.GONE
                btnActionRemove.visibility = View.GONE
                fileIconFrame.setBackgroundResource(R.drawable.bg_picker_pill)
                fileIconImg.setImageResource(R.drawable.ic_file_generic)
                fileIconImg.setColorFilter(getColor(R.color.cm_primary))
            }
        }

        val triggerPickFile = {
            if (!busy) pickFile.launch(arrayOf("*/*"))
        }
        filePickerCard.setOnClickListener { triggerPickFile() }
        btnActionChoose.setOnClickListener { triggerPickFile() }
        btnActionChange.setOnClickListener { triggerPickFile() }

        btnActionRemove.setOnClickListener {
            uri = null
            selectedFileLabel = ""
            selectedFileSize = 0L
            updatePickedFileUi()
            onPickedFileChanged?.invoke()
        }

        onPickedFileChanged = {
            updatePickedFileUi()
        }

        updateModeVisibility = {
            val isLink = uploadMode == "link"
            updateTabsUi()
            linkSection.visibility = if (isLink) View.VISIBLE else View.GONE
            filePickerCard.visibility = if (!isLink && !isEditing) View.VISIBLE else View.GONE
            tvNameLabel.text = if (isLink) "Resource title *" else "Display Title"
            if (isLink) checkClipboard()
        }

        name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                tvNameError.visibility = View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        urlField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                tvUrlError.visibility = View.GONE
                val u = s?.toString()?.trim().orEmpty()
                val isNonDrive = u.isNotBlank() && !FileVisuals.isGoogleDrive(u)
                driveWarning.visibility = if (isNonDrive && uploadMode == "link") View.VISIBLE else View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        updatePickedFileUi()
        updateModeVisibility.invoke()

        // Observe background upload progress
        lifecycleScope.launch {
            LibraryUploadManager.uploadState.collect { prog ->
                if (prog != null && prog.taskId == currentUploadTaskId) {
                    when (prog.status) {
                        LibraryUploadManager.Status.QUEUED -> {
                            progressContainer.visibility = View.VISIBLE
                            determinateProgress.isIndeterminate = true
                            progressText.text = "Queued for upload…"
                        }
                        LibraryUploadManager.Status.UPLOADING -> {
                            progressContainer.visibility = View.VISIBLE
                            determinateProgress.isIndeterminate = false
                            determinateProgress.progress = prog.percent
                            val upStr = formatFileSize(prog.uploadedBytes)
                            val totStr = formatFileSize(prog.totalBytes)
                            progressText.text = "Uploading… ${prog.percent}% ($upStr / $totStr)"
                        }
                        LibraryUploadManager.Status.COMPLETED -> {
                            progressContainer.visibility = View.GONE
                            Toast.makeText(this@ClassMatePublishActivity, "File uploaded successfully", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                        LibraryUploadManager.Status.WAITING_FOR_NETWORK -> {
                            progressContainer.visibility = View.VISIBLE
                            determinateProgress.isIndeterminate = true
                            progressText.text = "Connection lost. Upload queued — will finish when online."
                        }
                        LibraryUploadManager.Status.FAILED -> {
                            progressContainer.visibility = View.GONE
                            status.visibility = View.VISIBLE
                            status.text = "Upload failed: ${prog.error ?: "Unknown error"}"
                            submit.isEnabled = true
                            busy = false
                        }
                    }
                }
            }
        }

        submit.isEnabled = true
        submit.setOnClickListener {
            val isLink = uploadMode == "link"
            val title = name.text.toString().trim()
            if (title.isBlank()) {
                tvNameError.text = if (isLink) "Resource title is required" else "Display title is required"
                tvNameError.visibility = View.VISIBLE
                name.requestFocus()
                return@setOnClickListener
            }
            tvNameError.visibility = View.GONE

            val course = selectedCourse
            if (course == null) {
                Toast.makeText(this, "Please select a subject", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val kind = "notes"

            val rawUrl = urlField.text.toString().trim()
            val urlWithScheme = if (rawUrl.matches(Regex("(?i)^https?://.*"))) rawUrl else "https://$rawUrl"

            if (isLink) {
                if (rawUrl.isBlank()) {
                    tvUrlError.text = "Google Drive link is required"
                    tvUrlError.visibility = View.VISIBLE
                    urlField.requestFocus()
                    return@setOnClickListener
                }
                try {
                    val u = java.net.URI(urlWithScheme)
                    if (u.host.isNullOrBlank() || !u.host.contains('.')) {
                        tvUrlError.text = "Please enter a valid link URL (e.g. https://drive.google.com/...)"
                        tvUrlError.visibility = View.VISIBLE
                        urlField.requestFocus()
                        return@setOnClickListener
                    }
                } catch (e: Exception) {
                    tvUrlError.text = "Please enter a valid link URL"
                    tvUrlError.visibility = View.VISIBLE
                    urlField.requestFocus()
                    return@setOnClickListener
                }
                tvUrlError.visibility = View.GONE
            } else {
                if (uri == null && editingFile == null) {
                    status.visibility = View.VISIBLE
                    status.text = "Please choose a file to upload"
                    return@setOnClickListener
                }
            }

            // Perform submission with duplicate detection
            fun executeSubmission(targetResourceId: String?) {
                if (busy) return
                busy = true
                submit.isEnabled = false
                status.visibility = View.VISIBLE

                if (isLink) {
                    perform {
                        if (targetResourceId != null || editingFile != null) {
                            val resId = targetResourceId ?: editingFile!!.getString("id")
                            ClassMateAuthApi.rpc("edit_resource_metadata", JSONObject()
                                .put("target_resource", resId)
                                .put("target_title", title)
                                .put("target_category", kind)
                                .put("target_course", course.offeringId)
                                .put("target_link_url", urlWithScheme)
                            )
                            null
                        } else {
                            ClassMateAuthApi.rpc("create_library_link", JSONObject()
                                .put("target_batch", batch)
                                .put("target_course", course.offeringId)
                                .put("target_title", title)
                                .put("target_category", kind)
                                .put("target_url", urlWithScheme)
                            )
                            null
                        }
                    }
                } else {
                    if (editingFile != null || (targetResourceId != null && uri == null)) {
                        val resId = targetResourceId ?: editingFile!!.getString("id")
                        perform {
                            ClassMateAuthApi.rpc("edit_resource_metadata", JSONObject()
                                .put("target_resource", resId)
                                .put("target_title", title)
                                .put("target_category", kind)
                                .put("target_course", course.offeringId)
                                .put("target_link_url", null)
                            )
                            null
                        }
                    } else if (uri != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val stats = runCatching { ClassMateAuthApi.rpc("get_r2_storage_stats", JSONObject()) }.getOrNull()
                                val totalBytes = stats?.optLong("total_bytes", 0L) ?: 0L
                                val limitBytes = stats?.optLong("limit_bytes", 10_630_044_057L) ?: 10_630_044_057L
                                if (totalBytes >= limitBytes || totalBytes + selectedFileSize > limitBytes) {
                                    withContext(Dispatchers.Main) {
                                        busy = false
                                        submit.isEnabled = true
                                        status.text = "Storage limit reached (9.9 GB). File uploads are paused. Please share via Drive Link."
                                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
                                            .setTitle("Storage Quota Reached")
                                            .setMessage("Cloudflare R2 free tier storage has reached the 9.9 GB limit. Direct file uploads are paused.\n\nYou can still share this resource using Drive Link!")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                    return@launch
                                }
                                withContext(Dispatchers.Main) {
                                    val mime = contentResolver.getType(uri!!) ?: "application/pdf"
                                    val taskId = LibraryUploadManager.enqueueUpload(
                                        context = this@ClassMatePublishActivity,
                                        uri = uri!!,
                                        fileName = selectedFileLabel,
                                        batchId = batch,
                                        courseId = course.offeringId,
                                        courseName = course.code,
                                        title = title,
                                        mimeType = mime,
                                        sizeBytes = selectedFileSize,
                                        category = kind
                                    )
                                    currentUploadTaskId = taskId
                                    progressContainer.visibility = View.VISIBLE
                                    progressText.text = "Starting background upload…"
                                    determinateProgress.isIndeterminate = true
                                    determinateProgress.progress = 0
                                    status.text = "Upload running in background. You may leave this screen."
                                    Toast.makeText(this@ClassMatePublishActivity, "Upload started in background", Toast.LENGTH_SHORT).show()
                                    submit.isEnabled = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    submit.isEnabled = true
                                    status.text = "Failed to start upload: ${e.message}"
                                    progressContainer.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }

            // Check duplicate
            lifecycleScope.launch {
                var duplicateId: String? = null
                var duplicateTitle: String? = null
                if (!isEditing) {
                    try {
                        val checkRes = ClassMateAuthApi.rows(
                            "resources",
                            "select=id,title&semester_course_id=eq.${course.offeringId}&batch_id=eq.$batch"
                        )
                        val normalize = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
                        val inputNorm = normalize(title)
                        for (i in 0 until checkRes.length()) {
                            val r = checkRes.getJSONObject(i)
                            val rTitle = r.optString("title")
                            if (normalize(rTitle) == inputNorm) {
                                duplicateId = r.optString("id")
                                duplicateTitle = rTitle
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (duplicateId != null && duplicateTitle != null) {
                    MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
                        .setTitle("Similar file already exists")
                        .setMessage("A file titled \"$duplicateTitle\" already exists in ${course.code} under ${kind.replaceFirstChar { it.uppercase() }}.\n\nWould you like to add this as a new file, or update the existing file instead?")
                        .setPositiveButton("Update existing") { _, _ ->
                            executeSubmission(targetResourceId = duplicateId)
                        }
                        .setNegativeButton("Add as new") { _, _ ->
                            executeSubmission(targetResourceId = null)
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    executeSubmission(targetResourceId = null)
                }
            }
        }
    }

    private fun perform(action: suspend () -> JSONObject?) {
        if (busy) return
        busy = true
        submit.isEnabled = false
        status.visibility = View.VISIBLE
        status.text = if (editingFile != null) "Saving changes…" else if (upload) (if (uploadMode == "link") "Sharing link…" else "Uploading file…") else "Posting notice…"
        lifecycleScope.launch {
            try {
                val outcome = action()
                val data = Intent()
                if (outcome != null) data.putExtra("created_notice", outcome.toString())
                setResult(RESULT_OK, data)
                finish()
            } catch (e: Exception) {
                status.text = e.message ?: "Could not complete. Try again."
            } finally {
                busy = false
                submit.isEnabled = true
            }
        }
    }
}
