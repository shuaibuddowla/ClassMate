package com.shuaib.classmate.activities

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
import java.util.Locale
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.shuaib.classmate.ui.library.CourseFileDraft
import com.shuaib.classmate.ui.library.CourseFileOption
import com.shuaib.classmate.ui.library.CourseFileUploadScreen
import com.shuaib.classmate.ui.notice.NoticeAttachment
import com.shuaib.classmate.ui.notice.NoticeCourseChoice
import com.shuaib.classmate.ui.notice.PostNoticeScreen
import com.shuaib.classmate.ui.notice.RecentNoticeItem
import com.shuaib.classmate.ui.notice.VoiceRecordingState

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

    // Compose State for PostNoticeScreen
    private val composeNoticeAttachments = mutableStateListOf<NoticeAttachment>()
    private var composeMessageText by mutableStateOf("")
    private var composeManualTitle by mutableStateOf("")
    private var composeShowManualTitleBox by mutableStateOf(false)
    private var composeVoiceState by mutableStateOf(VoiceRecordingState.IDLE)

    // Compose State for CourseFileUploadScreen
    private var composeCourseOptions by mutableStateOf<List<CourseFileOption>>(emptyList())
    private var composeSelectedCourseId by mutableStateOf<String?>(null)
    private var composeUploadMode by mutableStateOf("file")
    private val composeCourseFileDrafts = mutableStateListOf<CourseFileDraft>()
    private var composeLinkTitle by mutableStateOf("")
    private var composeLinkUrl by mutableStateOf("")
    private var composeUploadStatus by mutableStateOf<String?>(null)
    private var composeUploadBusy by mutableStateOf(false)

    private fun syncComposeCourseFiles() {
        composeCourseFileDrafts.clear()
        composeCourseFileDrafts.addAll(
            selectedCourseFiles.map {
                CourseFileDraft(
                    uri = it.uri,
                    originalFileName = it.originalFileName,
                    title = it.title,
                    sizeBytes = it.sizeBytes,
                    mimeType = it.mimeType
                )
            }
        )
    }
    private var composeVoiceTimerText by mutableStateOf("0:00")
    private var composeIsSubmitting by mutableStateOf(false)
    private var composeSubmissionStepText by mutableStateOf("")
    private var composeShowAiProgressDialog by mutableStateOf(false)
    private var composeCourses by mutableStateOf<List<NoticeCourseChoice>>(emptyList())
    private var composeSemesterLabel by mutableStateOf("")
    private var composeRecentNotices by mutableStateOf<List<RecentNoticeItem>>(emptyList())

    // Gemini Multimodal Voice Recording State
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var audioRecordFile: java.io.File? = null
    private var isRecordingVoice = false
    private var isTranscribingVoice = false
    private var voiceRecordStartTime = 0L
    private var voiceAmplitudeJob: kotlinx.coroutines.Job? = null
    private var onAudioPermissionGranted: (() -> Unit)? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onAudioPermissionGranted?.invoke()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

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

        val maxBytes = if (isPdf) 25L * 1024L * 1024L else 50L * 1024L * 1024L
        if (size > maxBytes) {
            val limitLabel = if (isPdf) "25 MB" else "50 MB"
            Toast.makeText(this, "File size exceeds the $limitLabel limit.", Toast.LENGTH_LONG).show()
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

    private fun getCachedCourses(batchId: String): List<CourseEntry> {
        val prefs = getSharedPreferences("cm_publish_cache", Context.MODE_PRIVATE)
        val raw = prefs.getString("courses_$batchId", null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    CourseEntry(obj.getString("offeringId"), obj.getString("code"), obj.getString("title"))
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }

        // Resilient fallback: Read from ClassMateAcademicCache
        val profileId = intent.getStringExtra("profile_id").orEmpty()
        val libCache = ClassMateAcademicCache.read(this, profileId, batchId, "library")
            ?: ClassMateAcademicCache.read(this, "default_user", batchId, "library")
            ?: run {
                val allPrefs = getSharedPreferences("classmate_academic_cache_${com.shuaib.classmate.BuildConfig.CLASSMATE_ENV}", Context.MODE_PRIVATE).all
                val matchingKey = allPrefs.keys.firstOrNull { it.endsWith(":$batchId:library") }
                matchingKey?.let { key -> runCatching { JSONObject(allPrefs[key] as String) }.getOrNull() }
            }

        if (libCache != null) {
            val offeringsArr = libCache.optJSONArray("offerings") ?: JSONArray()
            val coursesObj = libCache.optJSONObject("courses") ?: JSONObject()
            val result = mutableListOf<CourseEntry>()
            for (i in 0 until offeringsArr.length()) {
                val off = offeringsArr.optJSONObject(i) ?: continue
                val offId = off.optString("id")
                val courseId = off.optString("course_id")
                val courseMeta = coursesObj.optJSONObject(courseId)
                val code = courseMeta?.optString("course_code") ?: "Course"
                val title = courseMeta?.optString("course_title") ?: ""
                result.add(CourseEntry(offId, code, title))
            }
            if (result.isNotEmpty()) {
                saveCachedCourses(batchId, result, prefs.getString("sem_$batchId", null))
                return result
            }
        }

        return emptyList()
    }

    private fun saveCachedCourses(batchId: String, list: List<CourseEntry>, semTitle: String?) {
        val prefs = getSharedPreferences("cm_publish_cache", Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("offeringId", it.offeringId).put("code", it.code).put("title", it.title))
        }
        prefs.edit().putString("courses_$batchId", arr.toString())
            .putString("sem_$batchId", semTitle.orEmpty()).apply()
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
        lifecycleScope.launch(Dispatchers.IO) {
            ClassMateAuthApi.restoreSession()
        }
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

        composeMessageText = state?.getString("compose_message_text").orEmpty()
        composeManualTitle = state?.getString("compose_manual_title").orEmpty()
        composeShowManualTitleBox = state?.getBoolean("compose_show_manual_title", false) ?: false

        if (!upload) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }

            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!composeIsSubmitting) {
                        finish()
                    } else {
                        Toast.makeText(this@ClassMatePublishActivity, "Notice is publishing in background", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            })

            syncComposeAttachments()
            onNoticeAttachmentChanged = {
                syncComposeAttachments()
            }

            val cachedCourses = getCachedCourses(batch)
            courses = cachedCourses
            composeCourses = cachedCourses.map { NoticeCourseChoice(it.offeringId, it.code, it.title) }
            val cachedSem = getSharedPreferences("cm_publish_cache", Context.MODE_PRIVATE).getString("sem_$batch", null)
            if (!cachedSem.isNullOrBlank()) {
                composeSemesterLabel = cachedSem
            }

            loadRecentNotices(batch)

            val composeView = ComposeView(this).apply {
                setContent {
                    PostNoticeScreen(
                        batchLabel = intent.getStringExtra("batch_label").orEmpty(),
                        semesterLabel = composeSemesterLabel,
                        isTeacher = intent.getStringExtra("role") == "teacher",
                        courses = composeCourses,
                        recentNotices = composeRecentNotices,
                        messageText = composeMessageText,
                        onMessageChange = { composeMessageText = it },
                        attachments = composeNoticeAttachments,
                        onRemoveAttachment = { index ->
                            if (index in selectedNoticeAttachments.indices) {
                                selectedNoticeAttachments.removeAt(index)
                                syncComposeAttachments()
                            }
                        },
                        onAddPhotosClick = { checkAndRequestPhotoPermission() },
                        onAddPdfClick = { checkAndRequestPdfPermission() },
                        voiceState = composeVoiceState,
                        voiceTimerText = composeVoiceTimerText,
                        onVoiceClick = { handleVoiceClick() },
                        manualTitle = composeManualTitle,
                        onManualTitleChange = { composeManualTitle = it },
                        showManualTitleBox = composeShowManualTitleBox,
                        onToggleManualTitleBox = { show ->
                            composeShowManualTitleBox = show
                            if (!show) composeManualTitle = ""
                        },
                        onSubmitGeneralNotice = { title, body, courseOfferingId ->
                            submitComposeGeneralNotice(title, body, courseOfferingId)
                        },
                        onSubmitCancellation = { courseOfferingId, dateOption ->
                            submitComposeCancellation(courseOfferingId, dateOption)
                        },
                        onSubmitPoll = { q, opts, isMulti, isAnon, dur ->
                            submitComposePoll(q, opts, isMulti, isAnon, dur)
                        },
                        onClose = { finish() },
                        isSubmitting = composeIsSubmitting,
                        submissionStepText = composeSubmissionStepText,
                        showAiProgressDialog = composeShowAiProgressDialog,
                        onRunInBackground = {
                            Toast.makeText(applicationContext, "Notice is publishing in background…", Toast.LENGTH_SHORT).show()
                            composeShowAiProgressDialog = false
                            setResult(RESULT_OK)
                            finish()
                        }
                    )
                }
            }
            setContentView(composeView)

            lifecycleScope.launch {
                try {
                    val semesterObj = ClassMateAuthApi.rows("semesters", "select=id,semester_number&batch_id=eq.$batch&status=eq.active&limit=1").optJSONObject(0)
                    val semester = semesterObj?.optString("id")
                    val semNum = semesterObj?.optInt("semester_number", 0) ?: 0
                    val semTitle = if (semNum > 0) "Semester $semNum" else "Semester 3"
                    composeSemesterLabel = semTitle
                    val offerings = if (semester == null) JSONArray() else ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester")
                    val catalog = ClassMateCourses.catalog(batch)
                    val catalogMap = (0 until catalog.length()).map { catalog.getJSONObject(it) }.associateBy { it.getString("id") }

                    val teacher = intent.getStringExtra("role") == "teacher"
                    val assigned = if (teacher) ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.${intent.getStringExtra("profile_id")}&active=eq.true").let { rows ->
                        (0 until rows.length()).map { rows.getJSONObject(it).getString("semester_course_id") }.toSet()
                    } else null

                    val freshCourses = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                        .filter { assigned == null || it.getString("id") in assigned }
                        .map { off ->
                            val offId = off.getString("id")
                            val cat = catalogMap[off.getString("course_id")]
                            val code = cat?.optString("course_code") ?: "Course"
                            val title = cat?.optString("course_title") ?: ""
                            CourseEntry(offId, code, title)
                        }

                    var finalCourses = freshCourses
                    courses = finalCourses
                    composeCourses = finalCourses.map { NoticeCourseChoice(it.offeringId, it.code, it.title) }
                    saveCachedCourses(batch, finalCourses, semTitle)
                } catch (_: Exception) {}
            }
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        if (upload) {
            val isEditing = editingFile != null
            val editRawLink = editingFile?.optString("link_url")?.trim().orEmpty()
            val sanitizedEditLink = if (editRawLink.isBlank() || editRawLink.equals("null", ignoreCase = true)) "" else editRawLink
            val isEditingLink = isEditing && (editingFile!!.optString("file_type") == "link" || sanitizedEditLink.isNotBlank())

            if (isEditing) {
                composeUploadMode = if (isEditingLink) "link" else "file"
                composeLinkTitle = editingFile?.optString("title").orEmpty()
                composeLinkUrl = sanitizedEditLink
                composeSelectedCourseId = editingFile?.optString("semester_course_id")?.takeIf { it.isNotBlank() && it != "null" }
            } else {
                val preCourse = intent.getStringExtra("course_id")?.takeIf { it.isNotBlank() && it != "null" }
                if (preCourse != null) composeSelectedCourseId = preCourse
                if (restoredLinkUrl.isNotBlank()) {
                    composeUploadMode = "link"
                    composeLinkUrl = restoredLinkUrl
                }
            }

            syncComposeCourseFiles()
            onPickedFileChanged = { syncComposeCourseFiles() }

            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentUploadTaskId != null) {
                        Toast.makeText(this@ClassMatePublishActivity, "Upload continuing in background", Toast.LENGTH_SHORT).show()
                        finish()
                    } else if (!composeUploadBusy) {
                        finish()
                    } else {
                        Toast.makeText(this@ClassMatePublishActivity, "Please wait for completion", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            val cachedCourses = getCachedCourses(batch)
            courses = cachedCourses
            composeCourseOptions = cachedCourses.map { CourseFileOption(it.offeringId, it.code, it.title) }
            if (composeSelectedCourseId == null) {
                composeSelectedCourseId = cachedCourses.firstOrNull()?.offeringId
            }
            val cachedSem = getSharedPreferences("cm_publish_cache", Context.MODE_PRIVATE).getString("sem_$batch", null)
            if (!cachedSem.isNullOrBlank()) {
                composeSemesterLabel = cachedSem
            }

            val composeUploadView = ComposeView(this).apply {
                setContent {
                    CourseFileUploadScreen(
                        batchLabel = intent.getStringExtra("batch_label").orEmpty(),
                        semesterLabel = composeSemesterLabel,
                        isEditing = isEditing,
                        isEditingLink = isEditingLink,
                        courses = composeCourseOptions,
                        selectedCourseId = composeSelectedCourseId,
                        onSelectCourse = { composeSelectedCourseId = it },
                        uploadMode = composeUploadMode,
                        onModeChange = { composeUploadMode = it },
                        selectedFiles = composeCourseFileDrafts,
                        onPickFiles = {
                            pickCourseFiles.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.ms-powerpoint",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "image/*",
                                    "*/*"
                                )
                            )
                        },
                        onUpdateFileTitle = { idx, newTitle ->
                            if (idx in selectedCourseFiles.indices) {
                                selectedCourseFiles[idx].title = newTitle
                                composeCourseFileDrafts[idx] = composeCourseFileDrafts[idx].copy(title = newTitle)
                            }
                        },
                        onRemoveFile = { idx ->
                            if (idx in selectedCourseFiles.indices) {
                                selectedCourseFiles.removeAt(idx)
                                syncComposeCourseFiles()
                            }
                        },
                        linkTitle = composeLinkTitle,
                        onLinkTitleChange = { composeLinkTitle = it },
                        linkUrl = composeLinkUrl,
                        onLinkUrlChange = { composeLinkUrl = it },
                        statusMessage = composeUploadStatus,
                        isBusy = composeUploadBusy,
                        onSubmit = { submitComposeCourseFile(isEditing) },
                        onClose = {
                            if (currentUploadTaskId != null) {
                                Toast.makeText(this@ClassMatePublishActivity, "Upload continuing in background", Toast.LENGTH_SHORT).show()
                                finish()
                            } else if (!composeUploadBusy) {
                                finish()
                            }
                        }
                    )
                }
            }
            setContentView(composeUploadView)

            lifecycleScope.launch {
                try {
                    val semesterObj = ClassMateAuthApi.rows("semesters", "select=id,semester_number&batch_id=eq.$batch&status=eq.active&limit=1").optJSONObject(0)
                    val semester = semesterObj?.optString("id")
                    val semNum = semesterObj?.optInt("semester_number", 0) ?: 0
                    val semTitle = if (semNum > 0) "Semester $semNum" else "Semester 3"
                    composeSemesterLabel = semTitle
                    val offerings = if (semester == null) JSONArray() else ClassMateAuthApi.rows("semester_courses", "select=id,course_id&semester_id=eq.$semester")
                    val catalog = ClassMateCourses.catalog(batch)
                    val catalogMap = (0 until catalog.length()).map { catalog.getJSONObject(it) }.associateBy { it.getString("id") }

                    val teacher = intent.getStringExtra("role") == "teacher"
                    val assigned = if (teacher) ClassMateAuthApi.rows("teacher_course_assignments", "select=semester_course_id&teacher_id=eq.${intent.getStringExtra("profile_id")}&active=eq.true").let { rows ->
                        (0 until rows.length()).map { rows.getJSONObject(it).getString("semester_course_id") }.toSet()
                    } else null

                    val freshCourses = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                        .filter { assigned == null || it.getString("id") in assigned }
                        .map { off ->
                            val offId = off.getString("id")
                            val cat = catalogMap[off.getString("course_id")]
                            val code = cat?.optString("course_code") ?: "Course"
                            val title = cat?.optString("course_title") ?: ""
                            CourseEntry(offId, code, title)
                        }

                    var finalCourses = freshCourses
                    editingFile?.optString("semester_course_id")?.takeIf { it.isNotBlank() && it != "null" && finalCourses.none { c -> c.offeringId == it } }?.let { id ->
                        val offering = ClassMateAuthApi.rows("semester_courses", "select=course_id&id=eq.$id").optJSONObject(0)
                        val cat = offering?.optString("course_id")?.let { catalogMap[it] }
                        finalCourses = finalCourses + CourseEntry(id, cat?.optString("course_code") ?: "Course", cat?.optString("course_title") ?: "Current subject")
                    }

                    courses = finalCourses.sortedBy { it.code.trim().uppercase() }
                    composeCourseOptions = courses.map { CourseFileOption(it.offeringId, it.code, it.title) }
                    if (composeSelectedCourseId == null || courses.none { it.offeringId == composeSelectedCourseId }) {
                        composeSelectedCourseId = courses.firstOrNull()?.offeringId
                    }
                    saveCachedCourses(batch, courses, semTitle)
                } catch (e: Exception) {
                    if (composeCourseOptions.isEmpty()) {
                        composeUploadStatus = com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
                    }
                }
            }
            return
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

        var editorBuilt = false
        val cachedCourses = getCachedCourses(batch)
        val cachedSem = getSharedPreferences("cm_publish_cache", Context.MODE_PRIVATE).getString("sem_$batch", null)
        if (!cachedSem.isNullOrBlank()) {
            badgeSemester?.text = cachedSem
        }
        courses = cachedCourses
        loading.visibility = View.GONE
        if (upload) buildUpload() else buildNotice()
        editorBuilt = true

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

                val freshCourses = (0 until offerings.length()).map { offerings.getJSONObject(it) }
                    .filter { assigned == null || it.getString("id") in assigned }
                    .map { off ->
                        val offId = off.getString("id")
                        val cat = catalogMap[off.getString("course_id")]
                        val code = cat?.optString("course_code") ?: "Course"
                        val title = cat?.optString("course_title") ?: ""
                        CourseEntry(offId, code, title)
                    }

                var finalCourses = freshCourses
                editingFile?.optString("semester_course_id")?.takeIf { it.isNotBlank() && it != "null" && finalCourses.none { c -> c.offeringId == it } }?.let { id ->
                    val offering = ClassMateAuthApi.rows("semester_courses", "select=course_id&id=eq.$id").optJSONObject(0)
                    val cat = offering?.optString("course_id")?.let { catalogMap[it] }
                    finalCourses = finalCourses + CourseEntry(id, cat?.optString("course_code") ?: "Course", cat?.optString("course_title") ?: "Current subject")
                }

                courses = finalCourses
                saveCachedCourses(batch, finalCourses, semTitle)

                if (!editorBuilt) {
                    loading.visibility = View.GONE
                    if (upload) buildUpload() else buildNotice()
                    editorBuilt = true
                }
            } catch (e: Exception) {
                if (!editorBuilt) {
                    loading.text = com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
                    submit.text = "Retry"
                    submit.isEnabled = true
                    submit.setOnClickListener { recreate() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onClipboardCheckRequested?.invoke()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("compose_message_text", composeMessageText)
        outState.putString("compose_manual_title", composeManualTitle)
        outState.putBoolean("compose_show_manual_title", composeShowManualTitleBox)
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

    override fun onDestroy() {
        voiceAmplitudeJob?.cancel()
        try {
            if (isRecordingVoice) {
                mediaRecorder?.stop()
            }
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun dp(n: Float) = (n * resources.displayMetrics.density).toInt()

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

        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }
        val general = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "General notice"
            setTextColor(getColor(R.color.cm_text_primary))
            textSize = 14.5f
        }
        val cancellation = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Class cancellation"
            setTextColor(getColor(R.color.cm_text_primary))
            textSize = 14.5f
        }
        val poll = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Interactive poll"
            setTextColor(getColor(R.color.cm_text_primary))
            textSize = 14.5f
        }
        choices.addView(general)
        choices.addView(cancellation)
        choices.addView(poll)
        form.panel.addView(choices)

        val title = form.field("Notice title")
        val titleBox = title.parent.parent as View
        titleBox.visibility = View.GONE

        // Modern Notice Composer Section (Image 1 reference)
        val messageComposerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }
        form.panel.addView(messageComposerBox)

        val primaryColor = getColor(R.color.cm_primary)
        val primarySoft = Color.argb(32, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        val redColor = getColor(R.color.cm_cancel_red)
        val redSoft = Color.argb(32, Color.red(redColor), Color.green(redColor), Color.blue(redColor))

        // Selected Attachment Preview Container (above the chat input capsule)
        val attachmentPreviewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
            visibility = View.GONE
        }
        messageComposerBox.addView(attachmentPreviewBox)

        // Single PDF preview pill
        val activePreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_picker_card_empty)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            visibility = View.GONE
        }
        val previewIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val previewIconImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        }
        previewIconFrame.addView(previewIconImg)
        activePreviewCard.addView(previewIconFrame)

        val previewCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) }
        }
        val tvPreviewName = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val tvPreviewMeta = TextView(this).apply {
            textSize = 10f
            setTextColor(getColor(R.color.cm_text_muted))
        }
        previewCol.addView(tvPreviewName)
        previewCol.addView(tvPreviewMeta)
        activePreviewCard.addView(previewCol)

        val btnRemoveAttachment = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.cm_cancel_red))
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            setBackgroundResource(outVal.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedNoticeAttachments.clear()
                onNoticeAttachmentChanged?.invoke()
            }
        }
        activePreviewCard.addView(btnRemoveAttachment)
        attachmentPreviewBox.addView(activePreviewCard)

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
        attachmentPreviewBox.addView(photoThumbnailsScroll)

        onNoticeAttachmentChanged = {
            if (selectedNoticeAttachments.isNotEmpty()) {
                attachmentPreviewBox.visibility = View.VISIBLE
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
                } else {
                    activePreviewCard.visibility = View.GONE
                    photoThumbnailsScroll.visibility = View.VISIBLE
                    photoThumbnailsRow.removeAllViews()

                    selectedNoticeAttachments.forEachIndexed { index, draft ->
                        val thumbFrame = FrameLayout(this@ClassMatePublishActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                                marginEnd = dp(8)
                            }
                        }
                        val thumbImg = com.google.android.material.imageview.ShapeableImageView(this@ClassMatePublishActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.START)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                                .setAllCornerSizes(dp(10).toFloat())
                                .build()
                        }
                        com.bumptech.glide.Glide.with(this@ClassMatePublishActivity)
                            .load(draft.uri)
                            .centerCrop()
                            .into(thumbImg)
                        thumbFrame.addView(thumbImg)

                        val removeBtn = ImageView(this@ClassMatePublishActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
                            setImageResource(R.drawable.ic_close)
                            setColorFilter(Color.WHITE)
                            val bgCircle = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(getColor(R.color.cm_cancel_red))
                            }
                            background = bgCircle
                            val p = dp(4)
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
                            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply {
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
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                            setImageResource(R.drawable.ic_gallery_photo)
                            setColorFilter(primaryColor)
                        }
                        val addText = TextView(this@ClassMatePublishActivity).apply {
                            text = "+ Add"
                            textSize = 9.5f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(primaryColor)
                        }
                        addMoreBtn.addView(addIcon)
                        addMoreBtn.addView(addText)
                        photoThumbnailsRow.addView(addMoreBtn)
                    }
                }
            } else {
                attachmentPreviewBox.visibility = View.GONE
                activePreviewCard.visibility = View.GONE
                photoThumbnailsScroll.visibility = View.GONE
            }
        }
        onNoticeAttachmentChanged?.invoke()

        // Modern Chat Input Capsule (Image 1 reference: + Message [photo] [mic])
        val chatInputCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_chat_input_capsule)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        messageComposerBox.addView(chatInputCapsule)

        // Document / PDF button (+) on the far left
        val btnAttachDoc = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(primaryColor)
            val p = dp(8)
            setPadding(p, p, p, p)
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            setBackgroundResource(outVal.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
            isFocusable = true
            contentDescription = "Attach Document"
            setOnClickListener {
                checkAndRequestPdfPermission()
            }
        }
        chatInputCapsule.addView(btnAttachDoc)

        // Middle container for auto-expanding text message & voice status
        val middleBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        chatInputCapsule.addView(middleBox)

        // Dynamic auto-expanding notice message input
        val message = EditText(this).apply {
            background = null
            hint = "Notice message · English, Banglish..."
            setHintTextColor(getColor(R.color.cm_text_muted))
            setTextColor(getColor(R.color.cm_text_primary))
            textSize = 15f
            minLines = 1
            maxLines = 8
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLineSpacing(0f, 1.2f)
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)
        }
        middleBox.addView(message)
        com.shuaib.classmate.notices.WhatsAppEditorHelper.attach(message)

        // Recording Live Elements (pulsing red dot + timer + dynamic EQ wave bars)
        val recordingActiveBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START)
        }
        val ivRecordDot = ImageView(this).apply {
            setImageResource(R.drawable.ic_recording_dot)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(8)
            }
        }
        recordingActiveBox.addView(ivRecordDot)

        val tvVoiceTimer = TextView(this).apply {
            text = "0:00"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.cm_cancel_red))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(10)
            }
        }
        recordingActiveBox.addView(tvVoiceTimer)

        fun createSignalBar(initialHeightDp: Int): View {
            return View(this).apply {
                setBackgroundResource(R.drawable.bg_voice_signal_bar_red)
                layoutParams = LinearLayout.LayoutParams(dp(3.5f), dp(initialHeightDp)).apply {
                    marginEnd = dp(3f)
                }
            }
        }
        val barSignal1 = createSignalBar(8)
        val barSignal2 = createSignalBar(14)
        val barSignal3 = createSignalBar(20)
        val barSignal4 = createSignalBar(10)
        recordingActiveBox.addView(barSignal1)
        recordingActiveBox.addView(barSignal2)
        recordingActiveBox.addView(barSignal3)
        recordingActiveBox.addView(barSignal4)
        middleBox.addView(recordingActiveBox)

        // Transcribing Elements (smooth spinner + "✨ Transcribing with Gemini...")
        val transcribingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START)
        }
        val pBarTranscribe = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginEnd = dp(8)
            }
        }
        val tvTranscribing = TextView(this).apply {
            text = "✨ Transcribing with Gemini..."
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        transcribingBox.addView(pBarTranscribe)
        transcribingBox.addView(tvTranscribing)
        middleBox.addView(transcribingBox)

        // Photo / Gallery button on the right
        val btnAttachPhoto = ImageView(this).apply {
            setImageResource(R.drawable.ic_gallery_photo)
            setColorFilter(primaryColor)
            val p = dp(8)
            setPadding(p, p, p, p)
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            setBackgroundResource(outVal.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
            isFocusable = true
            contentDescription = "Attach Photo"
            setOnClickListener {
                checkAndRequestPhotoPermission()
            }
        }
        chatInputCapsule.addView(btnAttachPhoto)

        // Voice Action Button (Material Mic icon matching Image 1)
        val btnVoiceInput = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(primaryColor)
            val p = dp(8)
            setPadding(p, p, p, p)
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            setBackgroundResource(outVal.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(2)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Voice Input"
        }
        chatInputCapsule.addView(btnVoiceInput)



        var dotPulseAnimator: android.animation.ObjectAnimator? = null
        fun startDotPulse() {
            dotPulseAnimator?.cancel()
            dotPulseAnimator = android.animation.ObjectAnimator.ofFloat(ivRecordDot, "alpha", 1f, 0.25f).apply {
                duration = 600
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
        fun stopDotPulse() {
            dotPulseAnimator?.cancel()
            dotPulseAnimator = null
            ivRecordDot.alpha = 1f
        }

        fun updateVoiceUi(isRecording: Boolean, isTranscribing: Boolean) {
            val outVal = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)

            if (isRecording) {
                message.visibility = View.INVISIBLE
                recordingActiveBox.visibility = View.VISIBLE
                transcribingBox.visibility = View.GONE
                startDotPulse()

                btnVoiceInput.isEnabled = true
                btnVoiceInput.setImageResource(R.drawable.ic_voice_stop)
                btnVoiceInput.setColorFilter(Color.WHITE)
                btnVoiceInput.setBackgroundResource(R.drawable.bg_voice_recording_btn)
                btnAttachPhoto.isEnabled = false
                btnAttachPhoto.alpha = 0.35f
                btnAttachDoc.isEnabled = false
                btnAttachDoc.alpha = 0.35f
            } else if (isTranscribing) {
                stopDotPulse()
                message.visibility = View.INVISIBLE
                recordingActiveBox.visibility = View.GONE
                transcribingBox.visibility = View.VISIBLE

                btnVoiceInput.isEnabled = false
                btnVoiceInput.setImageResource(R.drawable.ic_mic)
                btnVoiceInput.setColorFilter(getColor(R.color.cm_text_muted))
                btnVoiceInput.setBackgroundResource(outVal.resourceId)
                btnAttachPhoto.isEnabled = false
                btnAttachPhoto.alpha = 0.35f
                btnAttachDoc.isEnabled = false
                btnAttachDoc.alpha = 0.35f
            } else {
                stopDotPulse()
                message.visibility = View.VISIBLE
                recordingActiveBox.visibility = View.GONE
                transcribingBox.visibility = View.GONE

                btnVoiceInput.isEnabled = true
                btnVoiceInput.setImageResource(R.drawable.ic_mic)
                btnVoiceInput.setColorFilter(primaryColor)
                btnVoiceInput.setBackgroundResource(outVal.resourceId)
                btnAttachPhoto.isEnabled = true
                btnAttachPhoto.alpha = 1.0f
                btnAttachDoc.isEnabled = true
                btnAttachDoc.alpha = 1.0f
            }
        }

        fun stopVoiceRecordingAndTranscribe() {
            if (!isRecordingVoice) return
            isRecordingVoice = false
            voiceAmplitudeJob?.cancel()
            voiceAmplitudeJob = null
            btnVoiceInput.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

            val elapsed = System.currentTimeMillis() - voiceRecordStartTime
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                android.util.Log.w("ClassMateVoice", "MediaRecorder stop failed: ${e.message}")
            }
            mediaRecorder = null

            val file = audioRecordFile
            if (elapsed < 500 || file == null || !file.exists() || file.length() < 100) {
                updateVoiceUi(isRecording = false, isTranscribing = false)
                file?.delete()
                Toast.makeText(this@ClassMatePublishActivity, "Tap to speak, tap again to finish", Toast.LENGTH_SHORT).show()
                return
            }

            isTranscribingVoice = true
            updateVoiceUi(isRecording = false, isTranscribing = true)

            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    try { file.readBytes() } catch (_: Exception) { ByteArray(0) }
                }
                withContext(Dispatchers.IO) {
                    try { file.delete() } catch (_: Exception) {}
                }

                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isTranscribingVoice = false
                        updateVoiceUi(isRecording = false, isTranscribing = false)
                        Toast.makeText(this@ClassMatePublishActivity, "Could not capture audio", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.restoreSession()
                }

                val result = withContext(Dispatchers.IO) {
                    com.shuaib.classmate.services.AIService.transcribeAudio(bytes, "audio/mp4")
                }

                withContext(Dispatchers.Main) {
                    isTranscribingVoice = false
                    updateVoiceUi(isRecording = false, isTranscribing = false)

                    if (result.isSuccess) {
                        val text = result.getOrNull().orEmpty().trim()
                        if (text.isNotBlank()) {
                            btnVoiceInput.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            val existing = message.text?.toString().orEmpty().trim()
                            val combined = if (existing.isEmpty()) text else "$existing $text"
                            message.setText(combined)
                            message.setSelection(combined.length)
                            message.requestFocus()
                        } else {
                            Toast.makeText(this@ClassMatePublishActivity, "No speech recognized in audio", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Voice transcription failed"
                        android.util.Log.e("ClassMateVoice", "Gemini transcription error: $err", result.exceptionOrNull())
                        Toast.makeText(this@ClassMatePublishActivity, "Gemini transcription error: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        fun startVoiceRecording() {
            try {
                val cacheDir = externalCacheDir ?: cacheDir
                audioRecordFile = java.io.File(cacheDir, "gemini_voice_${System.currentTimeMillis()}.m4a")

                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(this@ClassMatePublishActivity)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }).apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16000)
                    setAudioEncodingBitRate(16000)
                    setAudioChannels(1)
                    setOutputFile(audioRecordFile!!.absolutePath)
                    prepare()
                    start()
                }

                isRecordingVoice = true
                voiceRecordStartTime = System.currentTimeMillis()
                updateVoiceUi(isRecording = true, isTranscribing = false)
                btnVoiceInput.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

                voiceAmplitudeJob?.cancel()
                voiceAmplitudeJob = lifecycleScope.launch {
                    val density = resources.displayMetrics.density
                    val maxH = 22 * density
                    val minH = 6 * density
                    var smoothedAmp = 0f

                    while (isRecordingVoice) {
                        val rawAmp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                        val rawFactor = if (rawAmp > 0) {
                            (Math.log10(rawAmp.toDouble()) / 4.4).toFloat().coerceIn(0.12f, 1.0f)
                        } else {
                            0.12f
                        }
                        smoothedAmp = smoothedAmp * 0.4f + rawFactor * 0.6f

                        val elapsed = (System.currentTimeMillis() - voiceRecordStartTime) / 1000
                        val mins = elapsed / 60
                        val secs = elapsed % 60

                        runOnUiThread {
                            if (!isRecordingVoice) return@runOnUiThread
                            tvVoiceTimer.text = String.format(Locale.US, "%d:%02d", mins, secs)

                            barSignal1.layoutParams = barSignal1.layoutParams.apply {
                                height = (minH + (maxH - minH) * (smoothedAmp * 0.7f)).toInt()
                            }
                            barSignal2.layoutParams = barSignal2.layoutParams.apply {
                                height = (minH + (maxH - minH) * (smoothedAmp * 1.0f)).toInt()
                            }
                            barSignal3.layoutParams = barSignal3.layoutParams.apply {
                                height = (minH + (maxH - minH) * (smoothedAmp * 0.85f)).toInt()
                            }
                            barSignal4.layoutParams = barSignal4.layoutParams.apply {
                                height = (minH + (maxH - minH) * (smoothedAmp * 0.55f)).toInt()
                            }

                            if (elapsed >= 35) {
                                stopVoiceRecordingAndTranscribe()
                            }
                        }
                        delay(65)
                    }
                }
            } catch (e: Exception) {
                isRecordingVoice = false
                updateVoiceUi(isRecording = false, isTranscribing = false)
                Toast.makeText(this@ClassMatePublishActivity, "Recording error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnVoiceInput.setOnClickListener {
            if (isTranscribingVoice) return@setOnClickListener
            if (isRecordingVoice) {
                stopVoiceRecordingAndTranscribe()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecording()
                } else {
                    onAudioPermissionGranted = { startVoiceRecording() }
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        val (courseBox, course) = dropdown(if (teacher) "Assigned course" else "Cancelled course", courses.map { it.second })
        val (dateBox, date) = dropdown("When?", listOf("Today", "Tomorrow"))
        courseBox.visibility = if (teacher) View.VISIBLE else View.GONE
        dateBox.visibility = View.GONE
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

        val (durationBox, durationField) = createDropdown("Poll Duration", listOf("1 hour", "6 hours", "12 hours", "24 hours", "2 days", "3 days (default)", "7 days", "No expiration"))
        pollContainer.addView(durationBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        fun updateTypeUi(checked: Int) {
            val cancelled = checked == cancellation.id
            val isPoll = checked == poll.id
            if (cancelled) {
                titleBox.visibility = View.GONE
                messageComposerBox.visibility = View.GONE
                courseBox.visibility = View.VISIBLE
                dateBox.visibility = View.VISIBLE
                pollContainer.visibility = View.GONE
                submit.text = "Post cancellation"
            } else if (isPoll) {
                titleBox.visibility = View.GONE
                messageComposerBox.visibility = View.GONE
                courseBox.visibility = View.GONE
                dateBox.visibility = View.GONE
                pollContainer.visibility = View.VISIBLE
                submit.text = "Publish poll"
            } else {
                titleBox.visibility = if (title.text.isNullOrBlank()) View.GONE else View.VISIBLE
                messageComposerBox.visibility = View.VISIBLE
                courseBox.visibility = if (teacher) View.VISIBLE else View.GONE
                dateBox.visibility = View.GONE
                pollContainer.visibility = View.GONE
                submit.text = "Post notice"
            }
        }

        choices.setOnCheckedChangeListener { _, checked ->
            updateTypeUi(checked)
        }
        choices.check(general.id)
        updateTypeUi(choices.checkedRadioButtonId)
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
                    "1 hour" -> 1
                    "6 hours" -> 6
                    "12 hours" -> 12
                    "24 hours" -> 24
                    "2 days" -> 48
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

            if (busy) return@setOnClickListener
            busy = true
            submit.isEnabled = false
            status.visibility = View.VISIBLE
            status.text = if (manualTitle.isNotBlank()) "Publishing notice to batch…" else "Detecting title & formatting notice with ClassMate AI…"

            val progressView = LinearLayout(this@ClassMatePublishActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(16))
            }

            val tvDialogHeader = TextView(this@ClassMatePublishActivity).apply {
                text = "✨ CLASSMATE AI ENGINE"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_primary))
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            }
            val tvDialogTitle = TextView(this@ClassMatePublishActivity).apply {
                text = if (manualTitle.isNotBlank()) "Publishing Notice" else "AI Title Detection"
                textSize = 17.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.cm_text_primary))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }

            val pBar = ProgressBar(this@ClassMatePublishActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            }

            val tvStepStatus = TextView(this@ClassMatePublishActivity).apply {
                text = if (manualTitle.isNotBlank()) "Publishing notice to batch feed…" else "Analyzing notice content & language structure…"
                textSize = 13.5f
                setTextColor(getColor(R.color.cm_text_secondary))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            progressView.addView(tvDialogHeader)
            progressView.addView(tvDialogTitle)
            progressView.addView(pBar)
            progressView.addView(tvStepStatus)

            val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
                .setView(progressView)
                .setCancelable(true)
                .setPositiveButton("Run in background") { dialog, _ ->
                    Toast.makeText(applicationContext, "Notice is publishing in background…", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    setResult(RESULT_OK)
                    finish()
                }
                .create()
            progressDialog.show()

            val appCtx = applicationContext
            val courseIdParam = if (teacher) selected?.first else null

            // If offline from the start, queue immediately without blocking
            if (!com.shuaib.classmate.activities.ClassMateAcademicCache.online(this@ClassMatePublishActivity)) {
                val fallbackTitle = manualTitle.ifBlank { rawBody.lines().firstOrNull()?.trim()?.take(60) ?: "Notice" }
                val pending = com.shuaib.classmate.workers.PendingNotice(
                    batchId = batch,
                    title = if (manualTitle.isNotBlank()) manualTitle else null,
                    body = rawBody,
                    courseId = courseIdParam,
                    isTeacher = teacher
                )
                com.shuaib.classmate.workers.OfflineNoticeQueue.enqueue(appCtx, pending)
                runCatching { if (progressDialog.isShowing) progressDialog.dismiss() }
                Toast.makeText(appCtx, "Offline: Notice saved to queue. It will post automatically once connection returns.", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                var finalTitle = manualTitle
                var finalBody = rawBody

                if (finalTitle.isBlank()) {
                    try {
                        withContext(Dispatchers.Main) {
                            runCatching {
                                tvStepStatus.text = "Detecting optimal title & smart category with ClassMate AI…"
                            }
                        }
                        val reqId = java.util.UUID.randomUUID().toString()
                        val response = ClassMateAuthApi.ai(
                            JSONObject()
                                .put("mode", "auto_notice")
                                .put("batch_id", batch)
                                .put("request_id", reqId)
                                .put("text", rawBody)
                        )
                        val result = response.optJSONObject("result")
                        val detected = result?.optString("title")?.trim().orEmpty()
                        val formatted = result?.optString("body")?.trim().orEmpty()
                        if (detected.isNotBlank()) finalTitle = detected
                        if (formatted.isNotBlank()) finalBody = formatted
                    } catch (_: Exception) {
                        finalTitle = rawBody.lines().firstOrNull()?.trim()?.take(60) ?: "Notice"
                    }
                }

                if (finalTitle.isBlank()) {
                    finalTitle = rawBody.lines().firstOrNull()?.trim()?.take(60) ?: "Notice"
                }

                val clientRequestId = java.util.UUID.randomUUID().toString()
                var noticePostedOnServer = false
                try {
                    val stagedAttachments = stageNoticeAttachmentsForPublish { text ->
                        runCatching { tvStepStatus.text = text }
                    }
                    withContext(Dispatchers.Main) {
                        runCatching {
                            tvStepStatus.text = "Publishing notice to batch feed…"
                        }
                    }
                    val postRes = ClassMateAuthApi.rpc(
                        "post_notice",
                        JSONObject().put("target_batch", batch)
                            .put("target_course", if (courseIdParam != null) courseIdParam else JSONObject.NULL)
                            .put("notice_title", finalTitle)
                            .put("notice_body", finalBody)
                            .put("target_attachments", stagedAttachments)
                            .put("client_request_id", clientRequestId)
                    )
                    noticePostedOnServer = true
                    if (selectedNoticeAttachments.isNotEmpty()) {
                        postRes.put("_local_attachments", buildLocalAttachmentPreviewArray())
                    }

                    withContext(Dispatchers.Main) {
                        runCatching { if (progressDialog.isShowing) progressDialog.dismiss() }
                        Toast.makeText(appCtx, "Notice published successfully!", Toast.LENGTH_SHORT).show()
                        val data = Intent().putExtra("created_notice", postRes.toString())
                        setResult(RESULT_OK, data)
                        finish()
                    }
                } catch (netErr: Exception) {
                    val isConnectivityFailure = !noticePostedOnServer && (
                        !com.shuaib.classmate.activities.ClassMateAcademicCache.online(appCtx) ||
                        netErr is java.io.IOException && !Regex("^4\\d\\d:").containsMatchIn(netErr.message.orEmpty())
                    )
                    if (isConnectivityFailure) {
                        com.shuaib.classmate.workers.OfflineNoticeQueue.enqueue(
                            appCtx,
                            com.shuaib.classmate.workers.PendingNotice(
                                id = clientRequestId,
                                batchId = batch,
                                title = finalTitle,
                                body = finalBody,
                                courseId = courseIdParam,
                                isTeacher = teacher,
                                attachments = buildPendingAttachments()
                            )
                        )
                        withContext(Dispatchers.Main) {
                            runCatching { if (progressDialog.isShowing) progressDialog.dismiss() }
                            Toast.makeText(
                                appCtx,
                                "Offline: Notice saved to queue. It will post automatically once connection returns.",
                                Toast.LENGTH_LONG
                            ).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            runCatching { if (progressDialog.isShowing) progressDialog.dismiss() }
                            Toast.makeText(
                                appCtx,
                                com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(netErr),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
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
                                status.text = "Failed to start upload: " + com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
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

    private suspend fun stageNoticeAttachmentsForPublish(onProgressText: ((String) -> Unit)? = null): JSONArray {
        val staged = JSONArray()
        if (selectedNoticeAttachments.isEmpty()) return staged
        val total = selectedNoticeAttachments.size
        selectedNoticeAttachments.forEachIndexed { index, item ->
            val initialLabel = if (item.isPdf) "Uploading PDF document…" else "Uploading photo ${index + 1} of $total…"
            withContext(Dispatchers.Main) {
                if (::status.isInitialized) {
                    status.visibility = View.VISIBLE
                    status.text = "$initialLabel 0%"
                }
                onProgressText?.invoke("$initialLabel 0%")
            }
            val uploaded = ClassMateAuthApi.uploadStagedNoticeAttachment(
                context = this@ClassMatePublishActivity,
                uri = item.uri,
                fileName = item.name,
                mimeType = item.mimeType,
                sizeHint = item.sizeBytes
            ) { pct ->
                runOnUiThread {
                    val label = if (item.isPdf) "Uploading PDF document…" else "Uploading photo ${index + 1} of $total…"
                    if (::status.isInitialized) {
                        status.text = "$label $pct%"
                    }
                    onProgressText?.invoke("$label $pct%")
                }
            }
            staged.put(uploaded)
        }
        return staged
    }

    private fun buildPendingAttachments(): List<com.shuaib.classmate.workers.PendingNoticeAttachment> =
        selectedNoticeAttachments.map {
            com.shuaib.classmate.workers.PendingNoticeAttachment(
                uri = it.uri.toString(),
                name = it.name,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                isPdf = it.isPdf
            )
        }

    private fun buildLocalAttachmentPreviewArray(): JSONArray = JSONArray().apply {
        selectedNoticeAttachments.forEachIndexed { index, draft ->
            put(
                JSONObject()
                    .put("id", "local_$index")
                    .put("storage_key", "")
                    .put("file_name", draft.name)
                    .put("mime_type", draft.mimeType)
                    .put("size_bytes", draft.sizeBytes)
                    .put("url", draft.uri.toString())
            )
        }
    }

    private suspend fun uploadNoticeAttachmentIfAny(noticeId: String, onProgressText: ((String) -> Unit)? = null) {
        if (selectedNoticeAttachments.isEmpty() || noticeId.isBlank()) return
        val total = selectedNoticeAttachments.size
        val repository = com.shuaib.classmate.repositories.NoticeRepository.getInstance(this@ClassMatePublishActivity)

        selectedNoticeAttachments.forEachIndexed { index, item ->
            val initialLabel = if (item.isPdf) "Uploading PDF document…" else "Uploading photo ${index + 1} of $total…"
            withContext(Dispatchers.Main) {
                if (::status.isInitialized) {
                    status.visibility = View.VISIBLE
                    status.text = "$initialLabel 0%"
                }
                onProgressText?.invoke("$initialLabel 0%")
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
                    if (::status.isInitialized) {
                        status.text = "$label $pct%"
                    }
                    onProgressText?.invoke("$label $pct%")
                }
            }
        }
    }

    private fun syncComposeAttachments() {
        composeNoticeAttachments.clear()
        selectedNoticeAttachments.forEach { draft ->
            composeNoticeAttachments.add(
                NoticeAttachment(
                    uri = draft.uri,
                    name = draft.name,
                    mimeType = draft.mimeType,
                    sizeBytes = draft.sizeBytes,
                    isPdf = draft.isPdf
                )
            )
        }
    }

    private fun checkAndRequestAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            onAudioPermissionGranted = onGranted
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleVoiceClick() {
        if (composeVoiceState == VoiceRecordingState.RECORDING) {
            stopVoiceRecordingAndTranscribeCompose()
        } else if (composeVoiceState == VoiceRecordingState.IDLE) {
            checkAndRequestAudioPermission {
                startVoiceRecordingCompose()
            }
        }
    }

    private fun startVoiceRecordingCompose() {
        try {
            val cacheDir = externalCacheDir ?: cacheDir
            audioRecordFile = java.io.File(cacheDir, "gemini_voice_${System.currentTimeMillis()}.m4a")

            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this@ClassMatePublishActivity)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(16000)
                setAudioChannels(1)
                setOutputFile(audioRecordFile!!.absolutePath)
                prepare()
                start()
            }

            isRecordingVoice = true
            voiceRecordStartTime = System.currentTimeMillis()
            composeVoiceState = VoiceRecordingState.RECORDING
            composeVoiceTimerText = "0:00"

            voiceAmplitudeJob?.cancel()
            voiceAmplitudeJob = lifecycleScope.launch {
                while (isRecordingVoice) {
                    val sec = ((System.currentTimeMillis() - voiceRecordStartTime) / 1000).toInt()
                    val m = sec / 60
                    val s = sec % 60
                    composeVoiceTimerText = String.format(Locale.getDefault(), "%d:%02d", m, s)
                    delay(300)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ClassMateVoice", "Failed to start recording: ${e.message}")
            Toast.makeText(this, "Could not start microphone recording", Toast.LENGTH_SHORT).show()
            mediaRecorder = null
            isRecordingVoice = false
            composeVoiceState = VoiceRecordingState.IDLE
        }
    }

    private fun stopVoiceRecordingAndTranscribeCompose() {
        if (!isRecordingVoice) return
        isRecordingVoice = false
        voiceAmplitudeJob?.cancel()
        voiceAmplitudeJob = null

        val elapsed = System.currentTimeMillis() - voiceRecordStartTime
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            android.util.Log.w("ClassMateVoice", "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder = null

        val file = audioRecordFile
        if (elapsed < 500 || file == null || !file.exists() || file.length() < 100) {
            composeVoiceState = VoiceRecordingState.IDLE
            file?.delete()
            Toast.makeText(this, "Tap to speak, tap again to finish", Toast.LENGTH_SHORT).show()
            return
        }

        composeVoiceState = VoiceRecordingState.TRANSCRIBING

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { file.readBytes() } catch (_: Exception) { ByteArray(0) }
            }
            withContext(Dispatchers.IO) {
                try { file.delete() } catch (_: Exception) {}
            }

            if (bytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    composeVoiceState = VoiceRecordingState.IDLE
                    Toast.makeText(this@ClassMatePublishActivity, "Could not capture audio", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.IO) {
                ClassMateAuthApi.restoreSession()
            }

            val result = withContext(Dispatchers.IO) {
                com.shuaib.classmate.services.AIService.transcribeAudio(bytes, "audio/mp4")
            }

            withContext(Dispatchers.Main) {
                composeVoiceState = VoiceRecordingState.IDLE
                if (result.isSuccess) {
                    val text = result.getOrNull().orEmpty().trim()
                    if (text.isNotBlank()) {
                        val existing = composeMessageText.trim()
                        composeMessageText = if (existing.isEmpty()) text else "$existing $text"
                    } else {
                        Toast.makeText(this@ClassMatePublishActivity, "No speech recognized in audio", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Voice transcription failed"
                    android.util.Log.e("ClassMateVoice", "Gemini transcription error: $err", result.exceptionOrNull())
                    Toast.makeText(this@ClassMatePublishActivity, "Gemini transcription error: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun submitComposeCourseFile(isEditing: Boolean) {
        val course = courses.firstOrNull { it.offeringId == composeSelectedCourseId } ?: courses.firstOrNull()
        if (course == null) {
            Toast.makeText(this, "Please select a course", Toast.LENGTH_SHORT).show()
            return
        }
        val isLink = composeUploadMode == "link"
        val kind = "notes"
        val rawUrl = composeLinkUrl.trim()
        val urlWithScheme = if (rawUrl.matches(Regex("(?i)^https?://.*"))) rawUrl else "https://$rawUrl"

        if (!isLink && !isEditing) {
            if (selectedCourseFiles.isEmpty()) {
                composeUploadStatus = "Please choose at least one file to upload"
                Toast.makeText(this, "Please choose at least one file to upload", Toast.LENGTH_SHORT).show()
                return
            }
            val blankIdx = selectedCourseFiles.indexOfFirst { it.title.trim().isBlank() }
            if (blankIdx >= 0) {
                Toast.makeText(this, "Please enter a title for \"${selectedCourseFiles[blankIdx].originalFileName}\"", Toast.LENGTH_SHORT).show()
                return
            }
            val oversize = selectedCourseFiles.firstOrNull { it.sizeBytes > 50L * 1024L * 1024L }
            if (oversize != null) {
                Toast.makeText(this, "\"${oversize.originalFileName}\" exceeds the 50 MB limit.", Toast.LENGTH_LONG).show()
                return
            }
        }

        fun executeSubmission(targetResourceId: String?) {
            if (isLink || isEditing) {
                val title = composeLinkTitle.trim()
                composeUploadBusy = true
                composeUploadStatus = if (isEditing) "Saving changes…" else "Sharing Drive link…"
                lifecycleScope.launch {
                    try {
                        if (isLink) {
                            if (targetResourceId != null || editingFile != null) {
                                val resId = targetResourceId ?: editingFile!!.getString("id")
                                ClassMateAuthApi.rpc(
                                    "edit_resource_metadata",
                                    JSONObject()
                                        .put("target_resource", resId)
                                        .put("target_title", title)
                                        .put("target_category", kind)
                                        .put("target_course", course.offeringId)
                                        .put("target_link_url", urlWithScheme)
                                )
                            } else {
                                ClassMateAuthApi.rpc(
                                    "create_library_link",
                                    JSONObject()
                                        .put("target_batch", batch)
                                        .put("target_course", course.offeringId)
                                        .put("target_title", title)
                                        .put("target_category", kind)
                                        .put("target_url", urlWithScheme)
                                )
                            }
                        } else {
                            val resId = targetResourceId ?: editingFile!!.getString("id")
                            ClassMateAuthApi.rpc(
                                "edit_resource_metadata",
                                JSONObject()
                                    .put("target_resource", resId)
                                    .put("target_title", title)
                                    .put("target_category", kind)
                                    .put("target_course", course.offeringId)
                                    .put("target_link_url", JSONObject.NULL)
                            )
                        }
                        Toast.makeText(this@ClassMatePublishActivity, if (isEditing) "Updated successfully!" else "Drive link shared!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } catch (e: Exception) {
                        composeUploadBusy = false
                        composeUploadStatus = com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
                    }
                }
            } else {
                if (composeUploadBusy) return
                composeUploadBusy = true
                composeUploadStatus = "Preparing upload…"
                val totalSelectedBytes = selectedCourseFiles.sumOf { it.sizeBytes }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val stats = runCatching { ClassMateAuthApi.rpc("get_r2_storage_stats", JSONObject()) }.getOrNull()
                        val totalBytes = stats?.optLong("total_bytes", 0L) ?: 0L
                        val limitBytes = stats?.optLong("limit_bytes", 10_630_044_057L) ?: 10_630_044_057L
                        if (totalBytes >= limitBytes || totalBytes + totalSelectedBytes > limitBytes) {
                            withContext(Dispatchers.Main) {
                                composeUploadBusy = false
                                composeUploadStatus = "Storage limit reached (9.9 GB). Please share via Drive Link."
                                MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
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
                                lastTaskId = LibraryUploadManager.enqueueUpload(
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
                            }
                            currentUploadTaskId = lastTaskId
                            Toast.makeText(
                                this@ClassMatePublishActivity,
                                if (count > 1) "$count files queued for background upload" else "Upload started in background",
                                Toast.LENGTH_SHORT
                            ).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            composeUploadBusy = false
                            composeUploadStatus = "Failed to start upload: " + com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
                        }
                    }
                }
            }
        }

        if (isLink && !isEditing) {
            val title = composeLinkTitle.trim()
            lifecycleScope.launch {
                var duplicateId: String? = null
                var duplicateTitle: String? = null
                try {
                    val checkRes = ClassMateAuthApi.rows(
                        "file_metadata",
                        "select=id,title&semester_course_id=eq.${course.offeringId}&batch_id=eq.$batch&status=eq.active"
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
                        .setMessage("A resource titled \"$duplicateTitle\" already exists in ${course.code}.\n\nWould you like to add this as a new link, or update the existing resource instead?")
                        .setPositiveButton("Update existing") { _, _ -> executeSubmission(targetResourceId = duplicateId) }
                        .setNegativeButton("Add as new") { _, _ -> executeSubmission(targetResourceId = null) }
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

    private fun submitComposeGeneralNotice(manualTitle: String, rawBody: String, courseOfferingId: String?) {
        if (composeIsSubmitting) return
        if (rawBody.isBlank() && selectedNoticeAttachments.isEmpty()) {
            Toast.makeText(this, "Write a notice message or attach a file first", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveBody = rawBody.ifBlank {
            val first = selectedNoticeAttachments.firstOrNull()
            if (first?.isPdf == true) "Shared PDF document: ${first.name}"
            else if (selectedNoticeAttachments.size > 1) "Shared ${selectedNoticeAttachments.size} photos"
            else "Shared an image"
        }

        val appCtx = applicationContext
        val teacher = intent.getStringExtra("role") == "teacher"
        if (teacher && courseOfferingId.isNullOrBlank()) {
            Toast.makeText(this, "No assigned course available", Toast.LENGTH_SHORT).show()
            return
        }

        composeIsSubmitting = true
        composeShowAiProgressDialog = true
        composeSubmissionStepText = if (manualTitle.isNotBlank() || rawBody.isBlank()) "Publishing notice to batch feed…" else "Analyzing notice content & language structure with ClassMate AI…"

        if (!com.shuaib.classmate.activities.ClassMateAcademicCache.online(this)) {
            val pending = com.shuaib.classmate.workers.PendingNotice(
                batchId = batch,
                title = if (manualTitle.isNotBlank()) manualTitle else if (rawBody.isBlank()) effectiveBody else null,
                body = if (rawBody.isBlank()) "" else effectiveBody,
                courseId = courseOfferingId,
                isTeacher = teacher,
                attachments = buildPendingAttachments()
            )
            com.shuaib.classmate.workers.OfflineNoticeQueue.enqueue(appCtx, pending)
            composeShowAiProgressDialog = false
            composeIsSubmitting = false
            Toast.makeText(appCtx, "Offline: Notice saved to queue. It will post automatically once connection returns.", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var finalTitle = manualTitle
            var finalBody = if (rawBody.isBlank()) "" else effectiveBody

            if (finalTitle.isBlank()) {
                if (rawBody.isBlank()) {
                    finalTitle = effectiveBody
                } else {
                    try {
                        withContext(Dispatchers.Main) {
                            composeSubmissionStepText = "Detecting optimal title & smart category with ClassMate AI…"
                        }
                        val reqId = java.util.UUID.randomUUID().toString()
                        val response = ClassMateAuthApi.ai(
                            JSONObject()
                                .put("mode", "auto_notice")
                                .put("batch_id", batch)
                                .put("request_id", reqId)
                                .put("text", effectiveBody)
                        )
                        val result = response.optJSONObject("result")
                        val detected = result?.optString("title")?.trim().orEmpty()
                        val formatted = result?.optString("body")?.trim().orEmpty()
                        if (detected.isNotBlank()) finalTitle = detected
                        if (formatted.isNotBlank()) finalBody = formatted
                    } catch (e: Exception) {
                        android.util.Log.w("ClassMatePublish", "AI title detection failed: ${e.message}")
                    }
                }
            }

            // Fallback check: if AI detection failed and title remains blank
            if (finalTitle.isBlank()) {
                if (isFinishing || isDestroyed) {
                    finalTitle = effectiveBody.lines().firstOrNull()?.trim()?.take(60)?.ifBlank { "Notice" } ?: "Notice"
                } else {
                    withContext(Dispatchers.Main) {
                        composeIsSubmitting = false
                        composeShowAiProgressDialog = false
                        composeShowManualTitleBox = true
                        Toast.makeText(appCtx, "AI title detection could not generate a title. Please enter a title manually.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }

            val clientRequestId = java.util.UUID.randomUUID().toString()
            var noticePostedOnServer = false
            try {
                val stagedAttachments = stageNoticeAttachmentsForPublish { text ->
                    composeSubmissionStepText = text
                }
                withContext(Dispatchers.Main) {
                    composeSubmissionStepText = "Publishing notice to batch feed…"
                }
                val postRes = ClassMateAuthApi.rpc(
                    "post_notice",
                    JSONObject().put("target_batch", batch)
                        .put("target_course", if (courseOfferingId != null) courseOfferingId else JSONObject.NULL)
                        .put("notice_title", finalTitle)
                        .put("notice_body", finalBody)
                        .put("target_attachments", stagedAttachments)
                        .put("client_request_id", clientRequestId)
                )
                noticePostedOnServer = true
                if (selectedNoticeAttachments.isNotEmpty()) {
                    postRes.put("_local_attachments", buildLocalAttachmentPreviewArray())
                }

                withContext(Dispatchers.Main) {
                    composeShowAiProgressDialog = false
                    composeIsSubmitting = false
                    Toast.makeText(appCtx, "Notice published successfully!", Toast.LENGTH_SHORT).show()
                    val data = Intent().putExtra("created_notice", postRes.toString())
                    setResult(RESULT_OK, data)
                    finish()
                }
            } catch (netErr: Exception) {
                val isConnectivityFailure = !noticePostedOnServer && (
                    !com.shuaib.classmate.activities.ClassMateAcademicCache.online(appCtx) ||
                    (netErr is java.io.IOException && !Regex("^4\\d\\d:").containsMatchIn(netErr.message.orEmpty()))
                )
                if (isConnectivityFailure) {
                    com.shuaib.classmate.workers.OfflineNoticeQueue.enqueue(
                        appCtx,
                        com.shuaib.classmate.workers.PendingNotice(
                            id = clientRequestId,
                            batchId = batch,
                            title = finalTitle,
                            body = finalBody,
                            courseId = courseOfferingId,
                            isTeacher = teacher,
                            attachments = buildPendingAttachments()
                        )
                    )

                    withContext(Dispatchers.Main) {
                        composeShowAiProgressDialog = false
                        composeIsSubmitting = false
                        Toast.makeText(
                            appCtx,
                            "Offline: Notice saved to queue. It will post automatically once connection returns.",
                            Toast.LENGTH_LONG
                        ).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        composeShowAiProgressDialog = false
                        composeIsSubmitting = false
                        Toast.makeText(
                            appCtx,
                            com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(netErr),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun submitComposeCancellation(courseOfferingId: String, dateOption: String) {
        if (composeIsSubmitting) return
        composeIsSubmitting = true
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val changeDate = LocalDate.now().plusDays(if (dateOption == "Tomorrow") 1 else 0).toString()
                val res = ClassMateAuthApi.rpc(
                    "post_cancellation_notice",
                    JSONObject().put("target_batch", batch)
                        .put("target_course", courseOfferingId)
                        .put("change_date", changeDate)
                )
                withContext(Dispatchers.Main) {
                    composeIsSubmitting = false
                    Toast.makeText(appCtx, "Class cancellation posted!", Toast.LENGTH_SHORT).show()
                    val data = Intent().putExtra("created_notice", res.toString())
                    setResult(RESULT_OK, data)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    composeIsSubmitting = false
                    Toast.makeText(appCtx, com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun submitComposePoll(
        question: String,
        options: List<String>,
        isMulti: Boolean,
        isAnon: Boolean,
        durationHours: Any
    ) {
        if (composeIsSubmitting) return
        if (question.isBlank() || options.size < 2) {
            Toast.makeText(this, "Please enter a question and at least 2 options", Toast.LENGTH_SHORT).show()
            return
        }
        composeIsSubmitting = true
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val res = ClassMateAuthApi.rpc(
                    "publish_batch_poll",
                    JSONObject()
                        .put("target_batch", batch)
                        .put("poll_question", question)
                        .put("poll_options", JSONArray(options))
                        .put("poll_description", "")
                        .put("is_multiple_choice", isMulti)
                        .put("is_anonymous", isAnon)
                        .put("duration_hours", durationHours)
                )
                withContext(Dispatchers.Main) {
                    composeIsSubmitting = false
                    Toast.makeText(appCtx, "Poll published successfully!", Toast.LENGTH_SHORT).show()
                    val data = Intent().putExtra("created_notice", res.toString())
                    setResult(RESULT_OK, data)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    composeIsSubmitting = false
                    Toast.makeText(appCtx, com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseRecentNoticeCategory(categoryRaw: String, typeRaw: String): String {
        val raw = categoryRaw.ifBlank { typeRaw }.trim().lowercase()
        return when (raw) {
            "exam", "ct", "class_test", "classtest" -> "CLASS TEST"
            "assignment", "deadline", "task" -> "ASSIGNMENT"
            "cancellation", "cancel", "class_cancel" -> "CLASS CANCEL"
            "poll", "survey" -> "POLL"
            "routine", "schedule" -> "ROUTINE"
            "resource", "material" -> "RESOURCE"
            "", "notice", "general", "general_notice", "general notice" -> "GENERAL"
            else -> raw.uppercase()
        }
    }

    private fun resolveNoticeAuthor(notice: JSONObject, authorsMap: Map<String, JSONObject>?): String {
        val id = notice.optString("id")
        val authorId = notice.optString("author_id").ifBlank { notice.optString("posted_by") }.ifBlank { notice.optString("created_by") }
        val name = authorsMap?.get(id)?.optString("full_name")?.takeIf { it.isNotBlank() }
            ?: authorsMap?.get(authorId)?.optString("full_name")?.takeIf { it.isNotBlank() }
            ?: authorsMap?.get(authorId)?.optString("name")?.takeIf { it.isNotBlank() }
            ?: notice.optString("author_name").takeIf { it.isNotBlank() }
            ?: notice.optString("created_by_name").takeIf { it.isNotBlank() }
        return if (!name.isNullOrBlank()) "By $name" else "By Class Representative"
    }

    private fun loadRecentNotices(batchId: String) {
        if (batchId.isBlank()) return
        val currentUserId = ClassMateAuthApi.notificationIdentity()?.optString("id") ?: ""

        // 1. Instant local cache read
        val cached = com.shuaib.classmate.notices.NoticeCacheStore.readCache(this, currentUserId, batchId)
        if (cached != null && cached.notices.isNotEmpty()) {
            composeRecentNotices = cached.notices.take(3).map { n ->
                val id = n.optString("id")
                RecentNoticeItem(
                    id = id,
                    title = n.optString("title").ifBlank { "Announcement" },
                    body = n.optString("body").trim(),
                    category = parseRecentNoticeCategory(n.optString("category"), n.optString("type")),
                    authorName = resolveNoticeAuthor(n, cached.authors),
                    timeAgo = formatNoticeTimeAgo(n.optString("published_at")),
                    hasAttachment = n.optString("resource_id").isNotBlank() && n.optString("resource_id") != "null"
                )
            }
        }

        // 2. Background fresh sync
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = ClassMateAuthApi.rows("notices", "select=*&batch_id=eq.$batchId&order=published_at.desc&limit=3")
                if (rows.length() > 0) {
                    val targetIds = JSONArray()
                    val noticeList = mutableListOf<JSONObject>()
                    for (i in 0 until rows.length()) {
                        rows.optJSONObject(i)?.let {
                            noticeList.add(it)
                            targetIds.put(it.optString("id"))
                        }
                    }

                    val details = runCatching {
                        ClassMateAuthApi.rpc("notice_feed_details", JSONObject().put("target_ids", targetIds))
                    }.getOrNull()

                    val authorsMap = mutableMapOf<String, JSONObject>()
                    details?.optJSONArray("authors")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { a ->
                                val key = a.optString("notice_id").ifBlank { a.optString("id") }
                                authorsMap[key] = a
                            }
                        }
                    }

                    val attachmentNoticeIds = mutableSetOf<String>()
                    details?.optJSONArray("attachments")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("notice_id")?.takeIf { it.isNotBlank() }?.let {
                                attachmentNoticeIds.add(it)
                            }
                        }
                    }

                    val items = noticeList.map { n ->
                        val id = n.optString("id")
                        RecentNoticeItem(
                            id = id,
                            title = n.optString("title").ifBlank { "Announcement" },
                            body = n.optString("body").trim(),
                            category = parseRecentNoticeCategory(n.optString("category"), n.optString("type")),
                            authorName = resolveNoticeAuthor(n, authorsMap),
                            timeAgo = formatNoticeTimeAgo(n.optString("published_at")),
                            hasAttachment = (n.optString("resource_id").isNotBlank() && n.optString("resource_id") != "null") || id in attachmentNoticeIds
                        )
                    }

                    withContext(Dispatchers.Main) {
                        composeRecentNotices = items
                    }
                }
            } catch (_: Exception) {
                // Silently fallback to cached notices
            }
        }
    }

    private fun formatNoticeTimeAgo(value: String): String = runCatching {
        if (value.isBlank()) return ""
        val instant = runCatching { java.time.Instant.parse(value) }.getOrNull()
            ?: return value.replace('T', ' ').take(16)
        val zonedDateTime = instant.atZone(java.time.ZoneId.systemDefault())
        val localDate = zonedDateTime.toLocalDate()
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(localDate, today)
        val timeStr = zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH))

        when {
            daysBetween == 0L -> {
                val diffMinutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
                when {
                    diffMinutes < 1 -> "Just now"
                    diffMinutes < 60 -> "${diffMinutes}m ago"
                    else -> "Today, $timeStr"
                }
            }
            daysBetween == 1L -> "Yesterday"
            daysBetween in 2..6 -> zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH))
            localDate.year == today.year -> zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH))
            else -> zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH))
        }
    }.getOrDefault("")

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
                val errMsg = com.shuaib.classmate.utils.NetworkErrorSanitizer.sanitize(e)
                status.text = errMsg
                Toast.makeText(this@ClassMatePublishActivity, errMsg, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                submit.isEnabled = true
            }
        }
    }
}
