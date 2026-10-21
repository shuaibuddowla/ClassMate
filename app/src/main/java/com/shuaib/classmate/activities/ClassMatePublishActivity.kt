package com.shuaib.classmate.activities

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
    private lateinit var name: TextInputEditText
    private var uri: Uri? = null
    private var selectedFileLabel = ""
    private var selectedFileSize = 0L
    private var restoredFileName = ""
    private var busy = false
    private var courses = emptyList<CourseEntry>()
    private var uploadMode = "file"
    private var restoredLinkUrl = ""
    private var urlInput: TextInputEditText? = null
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

        val toolbar = MaterialToolbar(this).apply {
            title = if (editingFile != null) {
                if (isEditingLink) "Edit Drive link" else "Edit library file"
            } else if (upload) {
                "Add library file"
            } else {
                "Post notice"
            }
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

        submit = MaterialButton(this).apply {
            val isLink = isEditingLink || uploadMode == "link"
            text = if (editingFile != null) "Save changes" else if (upload) (if (isLink) "Share Drive link" else "Upload file") else "Post notice"
            isAllCaps = false
            isEnabled = false
            cornerRadius = dp(16)
        }
        root.addView(submit, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(24), dp(12), dp(24), dp(16)) })
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
                val semester = ClassMateAuthApi.rows("semesters", "select=id&batch_id=eq.$batch&status=eq.active&limit=1").optJSONObject(0)?.optString("id")
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

    private fun dropdown(label: String, options: List<String>): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
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

        var btnUploadFile: TextView? = null
        var btnDriveLink: TextView? = null

        fun updateToggleUi() {
            val isLink = uploadMode == "link"
            btnUploadFile?.apply {
                if (!isLink) {
                    setBackgroundResource(R.drawable.bg_toggle_item_selected)
                    setTextColor(Color.WHITE)
                    compoundDrawablesRelative[0]?.setTint(Color.WHITE)
                } else {
                    background = null
                    val sec = getColor(R.color.cm_text_secondary)
                    setTextColor(sec)
                    compoundDrawablesRelative[0]?.setTint(sec)
                }
            }
            btnDriveLink?.apply {
                if (isLink) {
                    setBackgroundResource(R.drawable.bg_toggle_item_selected)
                    setTextColor(Color.WHITE)
                } else {
                    background = null
                    setTextColor(getColor(R.color.cm_text_secondary))
                }
                compoundDrawablesRelative[0]?.setTintList(null)
            }
            if (!isEditing) {
                // Primary button casing: sentence case
                submit.text = if (isLink) "Share Drive link" else "Upload file"
            }
        }

        // Top toggle bar: [ Upload File | Drive Link ]
        if (!isEditing) {
            val toggle = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_toggle_container)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(12)
                }
            }
            btnUploadFile = TextView(this).apply {
                text = " Upload File"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                val uploadIcon = ContextCompat.getDrawable(this@ClassMatePublishActivity, R.drawable.ic_file_generic)?.mutate()?.apply {
                    setBounds(0, 0, dp(16), dp(16))
                }
                setCompoundDrawablesRelative(uploadIcon, null, null, null)
                compoundDrawablePadding = dp(6)
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            }
            btnDriveLink = TextView(this).apply {
                text = " Drive Link"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                val driveIcon = ContextCompat.getDrawable(this@ClassMatePublishActivity, R.drawable.ic_google_drive)?.mutate()?.apply {
                    setBounds(0, 0, dp(16), dp(16))
                }
                setCompoundDrawablesRelative(driveIcon, null, null, null)
                compoundDrawablePadding = dp(6)
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            }
            toggle.addView(btnUploadFile)
            toggle.addView(btnDriveLink)
            form.panel.addView(toggle)
        }

        // --- Resource title / File name ---
        name = form.field(if (uploadMode == "link") "Resource title *" else "File name *").apply {
            setText(editingFile?.optString("title") ?: restoredFileName.ifBlank { selectedFileLabel.substringBeforeLast('.') })
        }
        val nameBox = name.parent.parent as? TextInputLayout

        // --- Link section containers ---
        val linkSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
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
                topMargin = dp(8)
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

        val urlBox = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Google Drive link *"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        val urlField = TextInputEditText(urlBox.context).apply {
            setTextColor(getColor(R.color.cm_text_primary))
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            val initialUrl = restoredLinkUrl.ifBlank { sanitizedEditLink }
            setText(if (initialUrl.equals("null", ignoreCase = true)) "" else initialUrl)
        }
        urlInput = urlField
        urlBox.addView(urlField)

        // Non-Drive link inline warning banner
        val driveWarning = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_warning_banner)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
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

        linkSection.addView(clipboardBanner)
        linkSection.addView(urlBox)
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
                urlBox.error = null
                clipboardBanner.visibility = View.GONE
                Toast.makeText(this, "Link pasted from clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        clipboardBanner.setOnClickListener { pasteBannerAction() }
        bannerText.setOnClickListener { pasteBannerAction() }
        bannerDismiss.setOnClickListener { clipboardBanner.visibility = View.GONE }

        // --- File section (Formats hint, filled button, picked file card) ---
        val fileSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }

        val formatHint = TextView(this).apply {
            text = "PDF, DOCX, PPTX, XLSX, Images · up to 50MB"
            textSize = 12f
            setTextColor(getColor(R.color.cm_text_secondary))
            setPadding(0, 0, 0, dp(6))
        }

        val btnChooseFile = MaterialButton(this).apply {
            text = "Choose file"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.cm_primary))
            setTextColor(Color.WHITE)
            icon = ContextCompat.getDrawable(this@ClassMatePublishActivity, R.drawable.ic_file_generic)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            elevation = dp(3).toFloat()
            setOnClickListener { if (!busy) pickFile.launch(arrayOf("*/*")) }
        }

        // Picked file card with icon, name, size and remove button
        val pickedFileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_picked_file_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val pickedIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundResource(R.drawable.bg_library_file_icon_blue)
        }
        val pickedIconImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            setImageResource(R.drawable.ic_file_generic)
        }
        pickedIconFrame.addView(pickedIconImg)

        val pickedDetailsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        }
        val tvPickedName = TextView(this).apply {
            text = "Selected file"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvPickedSize = TextView(this).apply {
            text = "0 MB"
            textSize = 11.5f
            setTextColor(getColor(R.color.cm_text_secondary))
        }
        pickedDetailsCol.addView(tvPickedName)
        pickedDetailsCol.addView(tvPickedSize)

        val btnRemovePicked = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.cm_text_secondary))
            setBackgroundResource(R.drawable.bg_toggle_container)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) }
        }

        pickedFileCard.addView(pickedIconFrame)
        pickedFileCard.addView(pickedDetailsCol)
        pickedFileCard.addView(btnRemovePicked)

        fileSection.addView(formatHint)
        fileSection.addView(btnChooseFile, LinearLayout.LayoutParams(-1, dp(54)))
        fileSection.addView(pickedFileCard)
        form.panel.addView(fileSection)

        fun updatePickedFileUi() {
            if (uri != null && selectedFileLabel.isNotBlank()) {
                pickedFileCard.visibility = View.VISIBLE
                btnChooseFile.visibility = View.GONE
                formatHint.visibility = View.GONE
                tvPickedName.text = selectedFileLabel
                tvPickedSize.text = "${formatFileSize(selectedFileSize)} · Ready to upload"

                val tempPdf = PdfFile(
                    id = "picked",
                    title = selectedFileLabel,
                    fileType = selectedFileLabel.substringAfterLast('.', "pdf"),
                    materialType = "notes",
                    sizeBytes = selectedFileSize,
                    driveUrl = ""
                )
                val visuals = FileVisuals.getVisuals(tempPdf)
                FileVisuals.applyVisuals(pickedIconImg, pickedIconFrame, visuals)
            } else {
                pickedFileCard.visibility = View.GONE
                if (!isEditing) {
                    btnChooseFile.visibility = View.VISIBLE
                    formatHint.visibility = View.VISIBLE
                }
            }
        }

        btnRemovePicked.setOnClickListener {
            uri = null
            selectedFileLabel = ""
            selectedFileSize = 0L
            updatePickedFileUi()
            onPickedFileChanged?.invoke()
        }
        pickedFileCard.setOnClickListener {
            if (!busy) pickFile.launch(arrayOf("*/*"))
        }

        // --- Category & Subject Dropdowns ---
        val (categoryBox, category) = dropdown("Category *", listOf("Notes", "Slides", "Questions", "Syllabus", "Other"))

        // Subject dropdown with two-line layout: bold course code on top, smaller title below
        val subjectBox = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            hint = "Subject *"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        val subject = MaterialAutoCompleteTextView(subjectBox.context).apply {
            inputType = android.text.InputType.TYPE_NULL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            val adapter = object : ArrayAdapter<CourseEntry>(this@ClassMatePublishActivity, R.layout.item_course_two_line_dropdown, courses) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_course_two_line_dropdown, parent, false)
                    val item = getItem(position)
                    view.findViewById<TextView>(R.id.tvCourseCode).text = item?.code.orEmpty()
                    view.findViewById<TextView>(R.id.tvCourseTitle).text = item?.title.orEmpty()
                    return view
                }
            }
            setAdapter(adapter)
            if (courses.isNotEmpty()) {
                val defaultCourseId = intent.getStringExtra("course_id")
                val matched = if (!defaultCourseId.isNullOrBlank()) courses.firstOrNull { it.offeringId == defaultCourseId } else courses.first()
                matched?.let { setText(it.displayLabel, false) }
            }
            setTextColor(getColor(R.color.cm_text_primary))
        }
        subjectBox.addView(subject)
        form.panel.addView(subjectBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        editingFile?.let { file ->
            category.setText(file.optString("category").replaceFirstChar { it.uppercase() }, false)
            courses.firstOrNull { it.offeringId == file.optString("semester_course_id") }?.let { subject.setText(it.displayLabel, false) }
            fileSection.visibility = View.GONE
            val note = if (isEditingLink) "Update the title, link, category or subject." else "Update the name, category or subject. The uploaded file stays unchanged."
            form.label(note)
        }

        // --- Determinate Progress Bar & Status ---
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

        // --- Live Preview Card ---
        val previewHeader = TextView(this).apply {
            text = "PREVIEW IN LIBRARY"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            letterSpacing = 0.05f
            setPadding(0, dp(20), 0, dp(6))
        }
        form.panel.addView(previewHeader)

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_preview_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        val previewIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setBackgroundResource(R.drawable.bg_library_file_icon_blue)
        }
        val previewIconImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            setImageResource(R.drawable.ic_file_generic)
        }
        previewIconFrame.addView(previewIconImg)

        val previewTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        }
        val previewTitle = TextView(this).apply {
            text = "Resource title"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val previewSubject = TextView(this).apply {
            text = "Subject"
            textSize = 12.5f
            setTextColor(getColor(R.color.cm_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val previewMeta = TextView(this).apply {
            text = "Notes · File"
            textSize = 11.5f
            setTextColor(getColor(R.color.cm_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        previewTextCol.addView(previewTitle)
        previewTextCol.addView(previewSubject)
        previewTextCol.addView(previewMeta)

        val previewTag = TextView(this).apply {
            text = "PREVIEW"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            setBackgroundResource(R.drawable.bg_toggle_container)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }

        previewCard.addView(previewIconFrame)
        previewCard.addView(previewTextCol)
        previewCard.addView(previewTag)
        form.panel.addView(previewCard)

        fun updateLivePreview() {
            val isLink = uploadMode == "link"
            val titleText = name.text?.toString()?.trim().orEmpty()
            previewTitle.text = if (titleText.isNotBlank()) titleText else (if (isLink) "Resource title" else "File name")

            val selSubject = subject.text?.toString()?.trim().orEmpty()
            previewSubject.text = if (selSubject.isNotBlank()) selSubject else "Subject"

            val selCategory = category.text?.toString()?.trim()?.replaceFirstChar { it.uppercase() } ?: "Notes"
            val rawUrl = urlField.text?.toString()?.trim().orEmpty()
            val isDrive = FileVisuals.isGoogleDrive(rawUrl)

            val metaText = when {
                isLink && isDrive -> "$selCategory · Google Drive"
                isLink -> "$selCategory · External Link"
                selectedFileSize > 0 -> "$selCategory · ${formatFileSize(selectedFileSize)}"
                else -> "$selCategory · File"
            }
            previewMeta.text = metaText

            val tempPdf = PdfFile(
                id = "preview",
                title = if (titleText.isNotBlank()) titleText else (if (isLink) "Link" else selectedFileLabel.ifBlank { "file" }),
                fileType = if (isLink) "link" else selectedFileLabel.substringAfterLast('.', "pdf"),
                materialType = selCategory.lowercase(),
                sizeBytes = selectedFileSize,
                driveUrl = rawUrl
            )
            val visuals = FileVisuals.getVisuals(tempPdf)
            FileVisuals.applyVisuals(previewIconImg, previewIconFrame, visuals)
        }

        onPickedFileChanged = {
            updatePickedFileUi()
            updateLivePreview()
        }

        fun updateModeVisibility() {
            val isLink = uploadMode == "link"
            updateToggleUi()
            linkSection.visibility = if (isLink) View.VISIBLE else View.GONE
            fileSection.visibility = if (!isLink && !isEditing) View.VISIBLE else View.GONE
            nameBox?.hint = if (isLink) "Resource title *" else "File name *"
            if (isLink) checkClipboard()
            updateLivePreview()
        }

        btnUploadFile?.setOnClickListener {
            if (uploadMode != "file") {
                uploadMode = "file"
                updateModeVisibility()
            }
        }
        btnDriveLink?.setOnClickListener {
            if (uploadMode != "link") {
                uploadMode = "link"
                updateModeVisibility()
            }
        }

        name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                nameBox?.error = null
                updateLivePreview()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        urlField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                urlBox.error = null
                val u = s?.toString()?.trim().orEmpty()
                val isNonDrive = u.isNotBlank() && !FileVisuals.isGoogleDrive(u)
                driveWarning.visibility = if (isNonDrive && uploadMode == "link") View.VISIBLE else View.GONE
                updateLivePreview()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        category.setOnItemClickListener { _, _, _, _ ->
            categoryBox.error = null
            updateLivePreview()
        }

        subject.setOnItemClickListener { _, _, _, _ ->
            subjectBox.error = null
            updateLivePreview()
        }

        updatePickedFileUi()
        updateModeVisibility()

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
                nameBox?.error = if (isLink) "Resource title is required" else "File name is required"
                name.requestFocus()
                return@setOnClickListener
            }
            nameBox?.error = null

            val selSubjectText = subject.text.toString().trim()
            val course = courses.firstOrNull { it.displayLabel == selSubjectText || it.second == selSubjectText }
            if (course == null) {
                subjectBox.error = "Please select a subject"
                subject.requestFocus()
                return@setOnClickListener
            }
            subjectBox.error = null

            val kind = category.text.toString().trim().lowercase()
            if (kind.isBlank()) {
                categoryBox.error = "Please select a category"
                category.requestFocus()
                return@setOnClickListener
            }
            categoryBox.error = null

            val rawUrl = urlField.text.toString().trim()
            val urlWithScheme = if (rawUrl.matches(Regex("(?i)^https?://.*"))) rawUrl else "https://$rawUrl"

            if (isLink) {
                if (rawUrl.isBlank()) {
                    urlBox.error = "Google Drive link is required"
                    urlField.requestFocus()
                    return@setOnClickListener
                }
                try {
                    val u = java.net.URI(urlWithScheme)
                    if (u.host.isNullOrBlank() || !u.host.contains('.')) {
                        urlBox.error = "Please enter a valid link URL (e.g. https://drive.google.com/...)"
                        urlField.requestFocus()
                        return@setOnClickListener
                    }
                } catch (e: Exception) {
                    urlBox.error = "Please enter a valid link URL"
                    urlField.requestFocus()
                    return@setOnClickListener
                }
                urlBox.error = null
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
                            )
                            null
                        }
                    } else {
                        // Background upload via LibraryUploadManager
                        val selected = uri ?: return
                        status.text = "Preparing upload…"
                        lifecycleScope.launch {
                            try {
                                val mime = contentResolver.getType(selected) ?: "application/octet-stream"
                                val size = selectedFileSize.takeIf { it > 0 } ?: contentResolver.query(selected, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                                } ?: 0L

                                val taskId = LibraryUploadManager.enqueueUpload(
                                    context = applicationContext,
                                    uri = selected,
                                    fileName = selectedFileLabel.ifBlank { title },
                                    batchId = batch,
                                    courseId = course.offeringId,
                                    courseName = course.displayLabel,
                                    title = title,
                                    mimeType = mime,
                                    sizeBytes = size,
                                    category = kind
                                )
                                currentUploadTaskId = taskId
                                progressContainer.visibility = View.VISIBLE
                                determinateProgress.isIndeterminate = true
                                progressText.text = "Upload started in background…"
                            } catch (e: Exception) {
                                status.text = e.message ?: "Failed to start upload"
                                busy = false
                                submit.isEnabled = true
                            }
                        }
                    }
                }
            }

            // Check for duplicates before submission
            lifecycleScope.launch {
                busy = true
                submit.isEnabled = false
                status.visibility = View.VISIBLE
                status.text = "Checking for duplicates…"

                val existingFiles = runCatching {
                    ClassMateAuthApi.rows(
                        "file_metadata",
                        "select=id,title,category,semester_course_id,file_type,link_url&status=eq.active&semester_course_id=eq.${course.offeringId}&category=eq.$kind"
                    )
                }.getOrNull() ?: JSONArray()

                val newTitleClean = title.trim().lowercase()
                var duplicateId: String? = null
                var duplicateTitle: String? = null

                for (i in 0 until existingFiles.length()) {
                    val f = existingFiles.getJSONObject(i)
                    val fId = f.getString("id")
                    if (editingFile?.optString("id") == fId) continue

                    val fTitle = f.optString("title").trim()
                    val fTitleClean = fTitle.lowercase()

                    val isExact = fTitleClean == newTitleClean
                    val isSimilar = fTitleClean.replace(Regex("[^a-z0-9]"), "") == newTitleClean.replace(Regex("[^a-z0-9]"), "") ||
                            ((fTitleClean.contains(newTitleClean) || newTitleClean.contains(fTitleClean)) && Math.abs(fTitleClean.length - newTitleClean.length) <= 3)

                    if (isExact || isSimilar) {
                        duplicateId = fId
                        duplicateTitle = fTitle
                        break
                    }
                }

                busy = false
                submit.isEnabled = true
                status.visibility = View.GONE

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
