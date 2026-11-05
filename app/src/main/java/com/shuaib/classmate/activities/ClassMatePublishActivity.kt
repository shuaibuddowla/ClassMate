package com.shuaib.classmate.activities

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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

    data class SelectedCourseFile(
        val uri: Uri,
        val originalFileName: String,
        var title: String,
        val sizeBytes: Long,
        val mimeType: String
    )

    private val selectedCourseFiles = mutableListOf<SelectedCourseFile>()

    // Attachment state for General Notice (Material 3)
    data class NoticeAttachmentDraft(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val isPdf: Boolean = false
    )

    private val selectedNoticeAttachments = mutableListOf<NoticeAttachmentDraft>()
    private var onNoticeAttachmentChanged: (() -> Unit)? = null

    private val pickNoticePhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
        if (selectedUris.isNullOrEmpty()) return@registerForActivityResult
        selectedNoticeAttachments.removeAll { it.isPdf }

        val remaining = 5 - selectedNoticeAttachments.size
        if (remaining <= 0) {
            Toast.makeText(this, "Maximum 5 photos allowed per notice.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val toAdd = selectedUris.take(remaining)
        if (selectedUris.size > remaining) {
            Toast.makeText(this, "Only up to 5 photos can be attached. Added ${toAdd.size}.", Toast.LENGTH_SHORT).show()
        }

        toAdd.forEach { uri ->
            extractNoticeAttachmentDraft(uri, isPdf = false)?.let { selectedNoticeAttachments.add(it) }
        }
        onNoticeAttachmentChanged?.invoke()
    }

    private val pickNoticePdf = registerForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected == null) return@registerForActivityResult
        selectedNoticeAttachments.clear()
        extractNoticeAttachmentDraft(selected, isPdf = true)?.let { selectedNoticeAttachments.add(it) }
        onNoticeAttachmentChanged?.invoke()
    }

    private val requestMediaPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            pickNoticePhotos.launch("image/*")
        } else {
            showPermissionRationaleOrSettings(isPhoto = true)
        }
    }

    private val requestSinglePhotoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pickNoticePhotos.launch("image/*")
        } else {
            showPermissionRationaleOrSettings(isPhoto = true)
        }
    }

    private val requestPdfPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pickNoticePdf.launch("application/pdf")
        } else {
            showPermissionRationaleOrSettings(isPhoto = false)
        }
    }

    private fun checkAndRequestPhotoPermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val fullGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                val partialGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                if (fullGranted || partialGranted) {
                    pickNoticePhotos.launch("image/*")
                } else {
                    requestMediaPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                    pickNoticePhotos.launch("image/*")
                } else {
                    requestSinglePhotoPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    pickNoticePhotos.launch("image/*")
                } else {
                    requestSinglePhotoPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun checkAndRequestPdfPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                pickNoticePdf.launch("application/pdf")
            } else {
                requestPdfPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            pickNoticePdf.launch("application/pdf")
        }
    }

    private fun showPermissionRationaleOrSettings(isPhoto: Boolean) {
        val perm = if (isPhoto) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val mediaType = if (isPhoto) "photos" else "PDF documents"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && shouldShowRequestPermissionRationale(perm)) {
            Toast.makeText(this, "Permission to access $mediaType is required to attach files.", Toast.LENGTH_LONG).show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permission Required")
                .setMessage("ClassMate requires permission to access $mediaType on your device to attach them to notices. Please enable permission in Settings.")
                .setPositiveButton("Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun extractNoticeAttachmentDraft(selected: Uri, isPdf: Boolean): NoticeAttachmentDraft? {
        val rawMime = contentResolver.getType(selected).orEmpty().lowercase()
        val name = contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: if (isPdf) "document.pdf" else "photo.jpg"

        val size = contentResolver.query(selected, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L

        val ext = name.substringAfterLast('.', "").lowercase()
        val mimeType = if (isPdf) {
            if (rawMime.isNotBlank() && rawMime.contains("pdf")) rawMime else "application/pdf"
        } else {
            if (rawMime.isNotBlank()) rawMime else when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
        }

        if (isPdf) {
            if (mimeType != "application/pdf" && ext != "pdf") {
                Toast.makeText(this, "Only PDF documents are supported.", Toast.LENGTH_LONG).show()
                return null
            }
        } else {
            if (mimeType !in setOf("image/jpeg", "image/png", "image/webp") && ext !in setOf("jpg", "jpeg", "png", "webp")) {
                Toast.makeText(this, "Only JPG, PNG, or WebP images are supported.", Toast.LENGTH_LONG).show()
                return null
            }
        }

        if (size > 10L * 1024L * 1024L) {
            Toast.makeText(this, "File size exceeds the 10 MB limit.", Toast.LENGTH_LONG).show()
            return null
        }

        return NoticeAttachmentDraft(selected, name, mimeType, size, isPdf)
    }

    private val pickCourseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selectedUris ->
        if (selectedUris.isNullOrEmpty()) return@registerForActivityResult
        val maxFiles = 15
        val remaining = maxFiles - selectedCourseFiles.size
        if (remaining <= 0) {
            Toast.makeText(this, "Maximum 15 files can be uploaded at once.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val toAdd = selectedUris.take(remaining)
        if (selectedUris.size > remaining) {
            Toast.makeText(this, "Only up to 15 files can be selected at once. Added ${toAdd.size}.", Toast.LENGTH_SHORT).show()
        }

        toAdd.forEach { selected ->
            runCatching { contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val label = contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Selected file"

            val size = contentResolver.query(selected, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            } ?: 0L

            val mime = contentResolver.getType(selected) ?: "application/octet-stream"
            val cleanTitle = label.substringBeforeLast('.').replace(Regex("[_-]"), " ").trim()

            selectedCourseFiles.add(
                SelectedCourseFile(
                    uri = selected,
                    originalFileName = label,
                    title = cleanTitle.ifBlank { label },
                    sizeBytes = size,
                    mimeType = mime
                )
            )
        }

        if (selectedCourseFiles.isNotEmpty()) {
            val first = selectedCourseFiles.first()
            uri = first.uri
            selectedFileLabel = first.originalFileName
            selectedFileSize = first.sizeBytes
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
        val resCourseUris = state?.getStringArrayList("course_file_uris")
        val resCourseNames = state?.getStringArrayList("course_file_names")
        val resCourseTitles = state?.getStringArrayList("course_file_titles")
        @Suppress("UNCHECKED_CAST")
        val resCourseSizes = state?.getSerializable("course_file_sizes") as? ArrayList<Long>
        val resCourseMimes = state?.getStringArrayList("course_file_mimes")
        if (!resCourseUris.isNullOrEmpty() && resCourseNames != null && resCourseTitles != null) {
            selectedCourseFiles.clear()
            for (i in resCourseUris.indices) {
                selectedCourseFiles.add(
                    SelectedCourseFile(
                        uri = Uri.parse(resCourseUris[i]),
                        originalFileName = resCourseNames.getOrNull(i).orEmpty(),
                        title = resCourseTitles.getOrNull(i).orEmpty(),
                        sizeBytes = resCourseSizes?.getOrNull(i) ?: 0L,
                        mimeType = resCourseMimes?.getOrNull(i).orEmpty().ifBlank { "application/octet-stream" }
                    )
                )
            }
        } else if (uri != null && selectedFileLabel.isNotBlank() && selectedCourseFiles.isEmpty()) {
            val clean = selectedFileLabel.substringBeforeLast('.').replace(Regex("[_-]"), " ").trim()
            selectedCourseFiles.add(
                SelectedCourseFile(
                    uri = uri!!,
                    originalFileName = selectedFileLabel,
                    title = clean.ifBlank { selectedFileLabel },
                    sizeBytes = selectedFileSize,
                    mimeType = "application/octet-stream"
                )
            )
        }

        val restoredUris = state?.getStringArrayList("notice_attachment_uris")
        val restoredNames = state?.getStringArrayList("notice_attachment_names")
        val restoredMimes = state?.getStringArrayList("notice_attachment_mimes")
        val restoredSizes = state?.getSerializable("notice_attachment_sizes") as? ArrayList<Long>
        val restoredIsPdfs = state?.getSerializable("notice_attachment_is_pdfs") as? ArrayList<Boolean>
        if (!restoredUris.isNullOrEmpty() && restoredNames != null && restoredMimes != null && restoredSizes != null && restoredIsPdfs != null) {
            selectedNoticeAttachments.clear()
            for (i in restoredUris.indices) {
                selectedNoticeAttachments.add(
                    NoticeAttachmentDraft(
                        Uri.parse(restoredUris[i]),
                        restoredNames.getOrNull(i) ?: "attachment",
                        restoredMimes.getOrNull(i) ?: "image/jpeg",
                        restoredSizes.getOrNull(i) ?: 0L,
                        restoredIsPdfs.getOrNull(i) ?: false
                    )
                )
            }
        }


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
        val savedUris = ArrayList(selectedNoticeAttachments.map { it.uri.toString() })
        val savedNames = ArrayList(selectedNoticeAttachments.map { it.name })
        val savedMimes = ArrayList(selectedNoticeAttachments.map { it.mimeType })
        val savedSizes = ArrayList(selectedNoticeAttachments.map { it.sizeBytes })
        val savedIsPdfs = ArrayList(selectedNoticeAttachments.map { it.isPdf })
        outState.putStringArrayList("notice_attachment_uris", savedUris)
        outState.putStringArrayList("notice_attachment_names", savedNames)
        outState.putStringArrayList("notice_attachment_mimes", savedMimes)
        outState.putSerializable("notice_attachment_sizes", savedSizes)
        outState.putSerializable("notice_attachment_is_pdfs", savedIsPdfs)
        val savedCourseUris = ArrayList(selectedCourseFiles.map { it.uri.toString() })
        val savedCourseNames = ArrayList(selectedCourseFiles.map { it.originalFileName })
        val savedCourseTitles = ArrayList(selectedCourseFiles.map { it.title })
        val savedCourseSizes = ArrayList(selectedCourseFiles.map { it.sizeBytes })
        val savedCourseMimes = ArrayList(selectedCourseFiles.map { it.mimeType })
        outState.putStringArrayList("course_file_uris", savedCourseUris)
        outState.putStringArrayList("course_file_names", savedCourseNames)
        outState.putStringArrayList("course_file_titles", savedCourseTitles)
        outState.putSerializable("course_file_sizes", savedCourseSizes)
        outState.putStringArrayList("course_file_mimes", savedCourseMimes)

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
        val poll = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Interactive poll"
            setTextColor(getColor(R.color.cm_text_primary))
        }
        choices.addView(general)
        choices.addView(cancellation)
        choices.addView(poll)
        form.panel.addView(choices)

        val title = form.field("Notice title")
        val titleBox = title.parent.parent as View
        titleBox.visibility = View.GONE

        val message = form.field("Notice message · English, Banglish or Bengali", true).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
        }
        val messageBox = message.parent.parent as View

        val silentHint = form.label("Banglish auto-converts to Bengali. English remains English. Start with /silent to post without push alerts.")

        // Material 3 Notice Attachment Section (Exclusive to General Notice)
        val attachmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        form.panel.addView(attachmentContainer)

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val tvAttachLabel = TextView(this).apply {
            text = "NOTICE ATTACHMENT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_secondary))
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val tvAttachBadge = TextView(this).apply {
            text = "Optional"
            textSize = 10.5f
            setTextColor(getColor(R.color.cm_text_muted))
        }
        headerLayout.addView(tvAttachLabel)
        headerLayout.addView(tvAttachBadge)
        attachmentContainer.addView(headerLayout)

        val pickerButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        fun createPickerCard(
            titleText: String,
            subtitleText: String,
            iconRes: Int,
            accentColor: Int,
            bgTint: Int,
            onClick: () -> Unit
        ): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_picker_card_empty)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                val outVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                foreground = ContextCompat.getDrawable(this@ClassMatePublishActivity, outVal.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }

                val iconFrame = FrameLayout(this@ClassMatePublishActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(bgTint)
                    }
                    background = bg
                }
                val iconView = ImageView(this@ClassMatePublishActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                    setImageResource(iconRes)
                    setColorFilter(accentColor)
                }
                iconFrame.addView(iconView)
                addView(iconFrame)

                val col = LinearLayout(this@ClassMatePublishActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) }
                }
                val tvTitle = TextView(this@ClassMatePublishActivity).apply {
                    text = titleText
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_primary))
                }
                val tvSub = TextView(this@ClassMatePublishActivity).apply {
                    text = subtitleText
                    textSize = 10f
                    setTextColor(getColor(R.color.cm_text_muted))
                }
                col.addView(tvTitle)
                col.addView(tvSub)
                addView(col)
            }
        }

        val primaryColor = getColor(R.color.cm_primary)
        val primarySoft = Color.argb(32, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        val redColor = getColor(R.color.cm_cancel_red)
        val redSoft = Color.argb(32, Color.red(redColor), Color.green(redColor), Color.blue(redColor))

        val btnPhoto = createPickerCard(
            "Add Photo",
            "JPG, PNG, WebP",
            R.drawable.ic_add_photo_alternate,
            primaryColor,
            primarySoft
        ) {
            checkAndRequestPhotoPermission()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
        }

        val btnPdf = createPickerCard(
            "Attach PDF",
            "Routine, Circular",
            R.drawable.ic_picture_as_pdf,
            redColor,
            redSoft
        ) {
            checkAndRequestPdfPermission()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
        }

        pickerButtonsLayout.addView(btnPhoto)
        pickerButtonsLayout.addView(btnPdf)
        attachmentContainer.addView(pickerButtonsLayout)

        val activePreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_picker_card_empty)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            visibility = View.GONE
        }

        val previewIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val previewIconImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        previewIconFrame.addView(previewIconImg)
        activePreviewCard.addView(previewIconFrame)

        val previewCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        }
        val tvPreviewName = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val tvPreviewMeta = TextView(this).apply {
            textSize = 10.5f
            setTextColor(getColor(R.color.cm_text_muted))
        }
        previewCol.addView(tvPreviewName)
        previewCol.addView(tvPreviewMeta)
        activePreviewCard.addView(previewCol)

        val btnRemoveAttachment = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.cm_cancel_red))
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            setBackgroundResource(outVal.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedNoticeAttachments.clear()
                onNoticeAttachmentChanged?.invoke()
            }
        }
        activePreviewCard.addView(btnRemoveAttachment)
        attachmentContainer.addView(activePreviewCard)

        // Multi-Photo Carousel Thumbnails Container
        val photoThumbnailsScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
        }
        val photoThumbnailsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-2, -2)
        }
        photoThumbnailsScroll.addView(photoThumbnailsRow)
        attachmentContainer.addView(photoThumbnailsScroll)

        onNoticeAttachmentChanged = {
            if (selectedNoticeAttachments.isNotEmpty()) {
                pickerButtonsLayout.visibility = View.GONE
                val first = selectedNoticeAttachments.first()
                if (first.isPdf) {
                    activePreviewCard.visibility = View.VISIBLE
                    photoThumbnailsScroll.visibility = View.GONE
                    tvPreviewName.text = first.name
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(redSoft)
                    }
                    previewIconFrame.background = bg
                    previewIconImg.setImageResource(R.drawable.ic_picture_as_pdf)
                    previewIconImg.setColorFilter(redColor)
                    tvPreviewMeta.text = "${formatFileSize(first.sizeBytes)} · PDF Document"
                    tvAttachBadge.text = "1 PDF attached"
                    tvAttachBadge.setTextColor(primaryColor)
                } else {
                    activePreviewCard.visibility = View.GONE
                    photoThumbnailsScroll.visibility = View.VISIBLE
                    photoThumbnailsRow.removeAllViews()

                    selectedNoticeAttachments.forEachIndexed { index, draft ->
                        val thumbFrame = FrameLayout(this@ClassMatePublishActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                                marginEnd = dp(8)
                            }
                        }

                        val thumbImg = com.google.android.material.imageview.ShapeableImageView(this@ClassMatePublishActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(70), dp(70), Gravity.BOTTOM or Gravity.START)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                                .setAllCornerSizes(dp(12).toFloat())
                                .build()
                        }
                        com.bumptech.glide.Glide.with(this@ClassMatePublishActivity)
                            .load(draft.uri)
                            .centerCrop()
                            .into(thumbImg)
                        thumbFrame.addView(thumbImg)

                        val removeBtn = ImageView(this@ClassMatePublishActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
                            setImageResource(R.drawable.ic_close)
                            setColorFilter(Color.WHITE)
                            val bgCircle = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(getColor(R.color.cm_cancel_red))
                            }
                            background = bgCircle
                            val p = dp(5)
                            setPadding(p, p, p, p)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                selectedNoticeAttachments.removeAt(index)
                                onNoticeAttachmentChanged?.invoke()
                            }
                        }
                        thumbFrame.addView(removeBtn)
                        photoThumbnailsRow.addView(thumbFrame)
                    }

                    if (selectedNoticeAttachments.size < 5) {
                        val addMoreBtn = LinearLayout(this@ClassMatePublishActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_picker_card_empty)
                            layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)).apply {
                                topMargin = dp(6)
                            }
                            val outVal = android.util.TypedValue()
                            theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                            foreground = ContextCompat.getDrawable(this@ClassMatePublishActivity, outVal.resourceId)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                checkAndRequestPhotoPermission()
                            }
                        }
                        val addIcon = ImageView(this@ClassMatePublishActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                            setImageResource(R.drawable.ic_add_photo_alternate)
                            setColorFilter(primaryColor)
                        }
                        val addText = TextView(this@ClassMatePublishActivity).apply {
                            text = "+ Add"
                            textSize = 10f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(primaryColor)
                        }
                        addMoreBtn.addView(addIcon)
                        addMoreBtn.addView(addText)
                        photoThumbnailsRow.addView(addMoreBtn)
                    }

                    val count = selectedNoticeAttachments.size
                    tvAttachBadge.text = "$count/5 Photos attached"
                    tvAttachBadge.setTextColor(primaryColor)
                }
            } else {
                pickerButtonsLayout.visibility = View.VISIBLE
                activePreviewCard.visibility = View.GONE
                photoThumbnailsScroll.visibility = View.GONE
                tvAttachBadge.text = "Optional"
                tvAttachBadge.setTextColor(getColor(R.color.cm_text_muted))
            }
        }
        onNoticeAttachmentChanged?.invoke()

        val (courseBox, course) = dropdown(if (teacher) "Assigned course" else "Cancelled course", courses.map { it.second })
        val (dateBox, date) = dropdown("When?", listOf("Today", "Tomorrow"))
        status = form.status()

        val pollContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        form.panel.addView(pollContainer)

        val questionInput = form.field("Poll Question")
        (questionInput.parent.parent as? View)?.let {
            form.panel.removeView(it)
            pollContainer.addView(it)
        }

        val pollOptionsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pollContainer.addView(pollOptionsLayout)
        val optionInputs = mutableListOf<android.widget.EditText>()
        fun addOptionField(hintText: String) {
            val optField = form.field(hintText)
            (optField.parent.parent as? View)?.let {
                form.panel.removeView(it)
                pollOptionsLayout.addView(it)
                optionInputs.add(optField)
            }
        }
        addOptionField("Option 1")
        addOptionField("Option 2")

        val btnAddOpt = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ Add option"
            setTextColor(getColor(R.color.cm_primary))
            setOnClickListener {
                if (optionInputs.size < 10) {
                    addOptionField("Option ${optionInputs.size + 1}")
                }
            }
        }
        pollContainer.addView(btnAddOpt)

        val cbMulti = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Allow multiple answers"
            setTextColor(getColor(R.color.cm_text_primary))
        }
        val cbAnon = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Anonymous voting (hide voter names)"
            setTextColor(getColor(R.color.cm_text_primary))
        }
        pollContainer.addView(cbMulti)
        pollContainer.addView(cbAnon)

        val (durationBox, durationField) = createDropdown("Poll Duration", listOf("24 hours", "3 days (default)", "7 days", "No expiration"))
        pollContainer.addView(durationBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        choices.setOnCheckedChangeListener { _, checked ->
            val cancelled = checked == cancellation.id
            val isPoll = checked == poll.id
            if (cancelled) {
                titleBox.visibility = View.GONE
                messageBox.visibility = View.GONE
                silentHint.visibility = View.GONE
                courseBox.visibility = View.VISIBLE
                dateBox.visibility = View.VISIBLE
                pollContainer.visibility = View.GONE
                attachmentContainer.visibility = View.GONE
                submit.text = "Post cancellation"
            } else if (isPoll) {
                titleBox.visibility = View.GONE
                messageBox.visibility = View.GONE
                silentHint.visibility = View.GONE
                courseBox.visibility = View.GONE
                dateBox.visibility = View.GONE
                pollContainer.visibility = View.VISIBLE
                attachmentContainer.visibility = View.GONE
                submit.text = "Publish poll"
            } else {
                titleBox.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                messageBox.visibility = View.VISIBLE
                silentHint.visibility = View.VISIBLE
                courseBox.visibility = if (teacher) View.VISIBLE else View.GONE
                dateBox.visibility = View.GONE
                pollContainer.visibility = View.GONE
                attachmentContainer.visibility = View.VISIBLE
                submit.text = "Post notice"
            }
        }
        choices.check(general.id)
        submit.text = "Post notice"
        submit.isEnabled = true

        submit.setOnClickListener {
            if (choices.checkedRadioButtonId == poll.id) {
                val q = questionInput.text?.toString()?.trim().orEmpty()
                if (q.length < 2) {
                    questionInput.error = "Enter a question for the poll"
                    return@setOnClickListener
                }
                val validOpts = optionInputs.map { it.text?.toString()?.trim().orEmpty() }.filter { it.isNotBlank() }
                if (validOpts.size < 2) {
                    Toast.makeText(this@ClassMatePublishActivity, "Please provide at least 2 options", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val durationText = durationField.text.toString()
                val durationHours: Any = when (durationText) {
                    "24 hours" -> 24
                    "7 days" -> 168
                    "No expiration" -> JSONObject.NULL
                    else -> 72
                }
                perform {
                    ClassMateAuthApi.rpc(
                        "publish_batch_poll",
                        JSONObject()
                            .put("target_batch", batch)
                            .put("poll_question", q)
                            .put("poll_options", JSONArray(validOpts))
                            .put("poll_description", "")
                            .put("is_multiple_choice", cbMulti.isChecked)
                            .put("is_anonymous", cbAnon.isChecked)
                            .put("duration_hours", durationHours)
                    )
                }
                return@setOnClickListener
            }
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
                    val postRes = ClassMateAuthApi.rpc(
                        "post_notice",
                        JSONObject().put("target_batch", batch)
                            .put("target_course", if (teacher) selected!!.first else JSONObject.NULL)
                            .put("notice_title", manualTitle)
                            .put("notice_body", rawBody)
                    )
                    val newNoticeId = postRes?.optString("id").orEmpty()
                    uploadNoticeAttachmentIfAny(newNoticeId)
                    postRes
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
                    val newNoticeId = postRes?.optString("id").orEmpty()
                    uploadNoticeAttachmentIfAny(newNoticeId)

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
                submit.text = if (isLink) {
                    "Share Drive link"
                } else {
                    if (selectedCourseFiles.size > 1) "Upload ${selectedCourseFiles.size} files"
                    else if (selectedCourseFiles.size == 1) "Upload 1 file"
                    else "Upload files"
                }
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

        // --- 2. Multi-File Selection Container ---
        val filesSectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        }
        form.panel.addView(filesSectionContainer)

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
            filesSectionContainer.visibility = View.GONE
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
            filesSectionContainer.removeAllViews()

            if (selectedCourseFiles.isEmpty()) {
                val emptyCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_picker_card_empty)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (!busy) pickCourseFiles.launch(arrayOf("*/*"))
                    }
                }

                val iconFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                    setBackgroundResource(R.drawable.bg_picker_pill)
                }
                val iconImg = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                    setImageResource(R.drawable.ic_file_generic)
                    setColorFilter(getColor(R.color.cm_primary))
                }
                iconFrame.addView(iconImg)

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
                }
                val tvTitle = TextView(this).apply {
                    text = "Choose files to upload"
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_primary))
                }
                val tvSub = TextView(this).apply {
                    text = "Select one or multiple documents (up to 50MB each)"
                    textSize = 11.5f
                    setTextColor(getColor(R.color.cm_text_muted))
                }
                textCol.addView(tvTitle)
                textCol.addView(tvSub)

                val btnChoose = TextView(this).apply {
                    text = "Choose"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_primary))
                    setBackgroundResource(R.drawable.bg_picker_pill)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                }

                emptyCard.addView(iconFrame)
                emptyCard.addView(textCol)
                emptyCard.addView(btnChoose)

                filesSectionContainer.addView(emptyCard)
            } else {
                val totalBytes = selectedCourseFiles.sumOf { it.sizeBytes }

                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(8)
                    }
                }
                val tvSummary = TextView(this).apply {
                    text = "${selectedCourseFiles.size} file${if (selectedCourseFiles.size > 1) "s" else ""} selected · ${formatFileSize(totalBytes)}"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.cm_text_secondary))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                headerRow.addView(tvSummary)

                if (selectedCourseFiles.size < 15) {
                    val btnAddMore = TextView(this).apply {
                        text = "+ Add More"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(getColor(R.color.cm_primary))
                        setBackgroundResource(R.drawable.bg_picker_pill)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (!busy) pickCourseFiles.launch(arrayOf("*/*"))
                        }
                    }
                    headerRow.addView(btnAddMore)
                }
                filesSectionContainer.addView(headerRow)

                selectedCourseFiles.forEachIndexed { index, item ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_picker_card_empty)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = dp(10)
                        }
                    }

                    val topRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(-1, -2)
                    }

                    val iconFrame = FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                        setBackgroundResource(R.drawable.bg_picker_pill)
                    }
                    val iconImg = ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                        setImageResource(R.drawable.ic_file_generic)
                        setColorFilter(getColor(R.color.cm_primary))
                    }
                    iconFrame.addView(iconImg)

                    val tempPdf = PdfFile(
                        id = "picked_$index",
                        title = item.originalFileName,
                        fileType = item.originalFileName.substringAfterLast('.', "pdf"),
                        materialType = "notes",
                        sizeBytes = item.sizeBytes,
                        driveUrl = ""
                    )
                    val visuals = FileVisuals.getVisuals(tempPdf)
                    FileVisuals.applyVisuals(iconImg, iconFrame, visuals)

                    val metaCol = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                            marginStart = dp(10)
                            marginEnd = dp(8)
                        }
                    }
                    val tvOrigName = TextView(this).apply {
                        text = item.originalFileName
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(getColor(R.color.cm_text_primary))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                    val tvMeta = TextView(this).apply {
                        text = "${formatFileSize(item.sizeBytes)} · ${item.originalFileName.substringAfterLast('.', "").uppercase()}"
                        textSize = 11f
                        setTextColor(getColor(R.color.cm_text_muted))
                    }
                    metaCol.addView(tvOrigName)
                    metaCol.addView(tvMeta)

                    val btnRemove = ImageView(this).apply {
                        setImageResource(R.drawable.ic_close)
                        setColorFilter(getColor(R.color.cm_text_muted))
                        setBackgroundResource(R.drawable.bg_picker_pill)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            selectedCourseFiles.removeAt(index)
                            if (selectedCourseFiles.isNotEmpty()) {
                                val first = selectedCourseFiles.first()
                                uri = first.uri
                                selectedFileLabel = first.originalFileName
                                selectedFileSize = first.sizeBytes
                            } else {
                                uri = null
                                selectedFileLabel = ""
                                selectedFileSize = 0L
                            }
                            updatePickedFileUi()
                            updateTabsUi()
                        }
                    }

                    topRow.addView(iconFrame)
                    topRow.addView(metaCol)
                    topRow.addView(btnRemove)
                    card.addView(topRow)

                    val tvTitleLabel = TextView(this).apply {
                        text = "Title in Course *"
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(getColor(R.color.cm_text_secondary))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = dp(8)
                            bottomMargin = dp(4)
                        }
                    }
                    val etTitle = EditText(this).apply {
                        setBackgroundResource(R.drawable.bg_clean_input)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        textSize = 13f
                        setTextColor(getColor(R.color.cm_text_primary))
                        setHintTextColor(getColor(R.color.cm_text_disabled))
                        hint = "e.g. Chapter 1 Notes"
                        setSingleLine(true)
                        setText(item.title)
                        addTextChangedListener(object : TextWatcher {
                            override fun afterTextChanged(s: Editable?) {
                                item.title = s?.toString().orEmpty()
                            }
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        })
                    }
                    card.addView(tvTitleLabel)
                    card.addView(etTitle)

                    filesSectionContainer.addView(card)
                }
            }
            updateTabsUi()
        }

        onPickedFileChanged = {
            updatePickedFileUi()
        }

        updateModeVisibility = {
            val isLink = uploadMode == "link"
            updateTabsUi()
            linkSection.visibility = if (isLink) View.VISIBLE else View.GONE
            filesSectionContainer.visibility = if (!isLink && !isEditing) View.VISIBLE else View.GONE
            val showGlobalTitle = isLink || isEditing
            tvNameLabel.visibility = if (showGlobalTitle) View.VISIBLE else View.GONE
            name.visibility = if (showGlobalTitle) View.VISIBLE else View.GONE
            tvNameLabel.text = if (isLink) "Resource title *" else "Display Title"
            if (!showGlobalTitle) tvNameError.visibility = View.GONE
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
            val course = selectedCourse
            if (course == null) {
                Toast.makeText(this, "Please select a subject", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val kind = "notes"
            val rawUrl = urlField.text.toString().trim()
            val urlWithScheme = if (rawUrl.matches(Regex("(?i)^https?://.*"))) rawUrl else "https://$rawUrl"

            if (isLink) {
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    tvNameError.text = "Resource title is required"
                    tvNameError.visibility = View.VISIBLE
                    name.requestFocus()
                    return@setOnClickListener
                }
                tvNameError.visibility = View.GONE

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
            } else if (isEditing) {
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    tvNameError.text = "Display title is required"
                    tvNameError.visibility = View.VISIBLE
                    name.requestFocus()
                    return@setOnClickListener
                }
                tvNameError.visibility = View.GONE
            } else {
                if (selectedCourseFiles.isEmpty()) {
                    status.visibility = View.VISIBLE
                    status.text = "Please choose at least one file to upload"
                    Toast.makeText(this, "Please choose at least one file to upload", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val blankIdx = selectedCourseFiles.indexOfFirst { it.title.trim().isBlank() }
                if (blankIdx >= 0) {
                    Toast.makeText(this, "Please enter a title for \"${selectedCourseFiles[blankIdx].originalFileName}\"", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val oversize = selectedCourseFiles.firstOrNull { it.sizeBytes > 50L * 1024L * 1024L }
                if (oversize != null) {
                    Toast.makeText(this, "\"${oversize.originalFileName}\" exceeds the 50 MB limit.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            fun executeSubmission(targetResourceId: String?) {
                if (isLink) {
                    val title = name.text.toString().trim()
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
                } else if (isEditing) {
                    val title = name.text.toString().trim()
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
                } else {
                    if (busy) return
                    busy = true
                    submit.isEnabled = false
                    status.visibility = View.VISIBLE
                    status.text = "Preparing upload…"

                    val totalSelectedBytes = selectedCourseFiles.sumOf { it.sizeBytes }

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val stats = runCatching { ClassMateAuthApi.rpc("get_r2_storage_stats", JSONObject()) }.getOrNull()
                            val totalBytes = stats?.optLong("total_bytes", 0L) ?: 0L
                            val limitBytes = stats?.optLong("limit_bytes", 10_630_044_057L) ?: 10_630_044_057L
                            if (totalBytes >= limitBytes || totalBytes + totalSelectedBytes > limitBytes) {
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
                                val count = selectedCourseFiles.size
                                var lastTaskId: String? = null
                                for (item in selectedCourseFiles) {
                                    val taskId = LibraryUploadManager.enqueueUpload(
                                        context = this@ClassMatePublishActivity,
                                        uri = item.uri,
                                        fileName = item.originalFileName,
                                        batchId = batch,
                                        courseId = course.offeringId,
                                        courseName = course.code,
                                        title = item.title.trim(),
                                        mimeType = item.mimeType,
                                        sizeBytes = item.sizeBytes,
                                        category = kind
                                    )
                                    lastTaskId = taskId
                                }
                                currentUploadTaskId = lastTaskId
                                progressContainer.visibility = View.VISIBLE
                                progressText.text = if (count > 1) "Starting upload of $count files in background…" else "Starting background upload…"
                                determinateProgress.isIndeterminate = true
                                determinateProgress.progress = 0
                                status.text = if (count > 1) "$count files uploading in background. You may leave this screen." else "Upload running in background. You may leave this screen."
                                Toast.makeText(this@ClassMatePublishActivity, if (count > 1) "$count files queued for background upload" else "Upload started in background", Toast.LENGTH_SHORT).show()
                                submit.isEnabled = false
                                setResult(RESULT_OK)
                                finish()
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

            if (isLink) {
                val title = name.text.toString().trim()
                lifecycleScope.launch {
                    var duplicateId: String? = null
                    var duplicateTitle: String? = null
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

                    if (duplicateId != null && duplicateTitle != null) {
                        MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
                            .setTitle("Similar link already exists")
                            .setMessage("A resource titled \"$duplicateTitle\" already exists in ${course.code} under ${kind.replaceFirstChar { it.uppercase() }}.\n\nWould you like to add this as a new link, or update the existing resource instead?")
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
            } else {
                executeSubmission(targetResourceId = null)
            }
        }
    }

    private suspend fun uploadNoticeAttachmentIfAny(noticeId: String) {
        if (selectedNoticeAttachments.isEmpty() || noticeId.isBlank()) return
        val total = selectedNoticeAttachments.size
        val repository = com.shuaib.classmate.repositories.NoticeRepository.getInstance(this@ClassMatePublishActivity)

        selectedNoticeAttachments.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) {
                status.visibility = View.VISIBLE
                val label = if (item.isPdf) "Uploading PDF document…" else "Uploading photo ${index + 1} of $total…"
                status.text = "$label 0%"
            }
            repository.uploadSupabaseNoticeAttachment(
                this@ClassMatePublishActivity,
                noticeId,
                item.uri,
                item.name,
                item.mimeType,
                item.sizeBytes
            ) { pct ->
                runOnUiThread {
                    val label = if (item.isPdf) "Uploading PDF document…" else "Uploading photo ${index + 1} of $total…"
                    status.text = "$label $pct%"
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
                val successMsg = if (upload && uploadMode == "link") "Drive link shared successfully" else if (editingFile != null) "Changes saved" else "Notice posted successfully"
                Toast.makeText(this@ClassMatePublishActivity, successMsg, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                val errMsg = e.message ?: "Could not complete. Try again."
                status.text = errMsg
                Toast.makeText(this@ClassMatePublishActivity, errMsg, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                submit.isEnabled = true
            }
        }
    }
}
