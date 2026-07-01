// com/shuaib/classmate/activities/PostNoticeActivity.kt
package com.shuaib.classmate.activities

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityPostNoticeBinding
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.models.Assignment
import com.shuaib.classmate.services.AIService
import com.shuaib.classmate.utils.CloudinaryUploader
import com.shuaib.classmate.utils.CountdownManager
import com.shuaib.classmate.utils.DateHelper
import com.shuaib.classmate.utils.NotificationSender
import com.shuaib.classmate.utils.SubjectList
import com.shuaib.classmate.utils.TelegramUploader
import com.shuaib.classmate.utils.WidgetUpdater
import com.shuaib.classmate.models.AcademicCalendarException
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.utils.ClassReminderWorkCoordinator
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

class PostNoticeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostNoticeBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var userName: String = "Admin"
    private var selectedSubmissionDate = ""
    private var selectedDeadlineType = "assignment"
    private var editNoticeId: String? = null

    private var isAiPostingMode = false
    private var aiPolishedNotice: com.shuaib.classmate.models.AiNoticeDraft? = null

    // Attachment fields
    private var selectedPdfUri: Uri? = null
    private var selectedImageUri: Uri? = null
    private var attachmentType = "none"
    private var attachmentFileName = ""
    private var uploadedAttachmentUrl = ""

    // File pickers
    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedPdfUri = it
            selectedImageUri = null
            val name = getFileName(it) ?: "document.pdf"
            binding.pdfPreview.isVisible = true
            binding.imagePreview.isVisible = false
            binding.attachmentPreview.isVisible = true
            binding.tvPdfName.text = name
            attachmentType = "pdf"
            attachmentFileName = name
        }
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            selectedPdfUri = null
            val name = getFileName(it) ?: "image.jpg"
            binding.imagePreview.isVisible = true
            binding.pdfPreview.isVisible = false
            binding.attachmentPreview.isVisible = true
            binding.tvImageName.text = name
            Glide.with(this)
                .load(it)
                .centerCrop()
                .into(binding.ivAttachmentPreview)
            attachmentType = "image"
            attachmentFileName = name
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostNoticeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupToolbar()
        setupTypeSelector()
        setupPostingMode()
        binding.rbSub.isVisible = false
        binding.sectionSubTeacher.isVisible = false
        setupSubjectPicker()
        setupAttachmentButtons()
        fetchAdminName()
        setupEditModeIfNeeded()

        setupVacationSection()

        binding.btnPublish.setOnClickListener {
            editNoticeId?.let {
                updateExistingNotice(it)
                return@setOnClickListener
            }
            if (isAiPostingMode) {
                publishAiNotice()
                return@setOnClickListener
            }

            when (binding.rgNoticeType.checkedRadioButtonId) {
                R.id.rbNormal -> publishNormalNotice()
                R.id.rbCancel -> publishCancellationNotice()
                R.id.rbSub -> publishSubstituteNotice()
                R.id.rbVacation -> publishVacationNotice()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupEditModeIfNeeded() {
        editNoticeId = intent.getStringExtra("NOTICE_ID") ?: return

        binding.toolbar.title = "Edit Notice"
        binding.tabLayout.isVisible = false
        binding.sectionAiComposeContainer.isVisible = false
        binding.rgNoticeType.isVisible = false
        binding.sectionNormal.isVisible = true
        binding.sectionSubjectPicker.isVisible = false
        binding.sectionAttachment.isVisible = false
        binding.btnPublish.text = "Update Notice"

        binding.etTitle.setText(intent.getStringExtra("NOTICE_TITLE").orEmpty())
        binding.etBody.setText(intent.getStringExtra("NOTICE_BODY").orEmpty())
    }



    private fun updateExistingNotice(noticeId: String) {
        val titleText = binding.etTitle.text.toString().trim()
        val bodyText = binding.etBody.text.toString().trim()

        if (titleText.isEmpty()) {
            binding.etTitle.error = "Title required"
            return
        }
        if (bodyText.length > 5000) {
            Toast.makeText(this, "Body text exceeds 5000 characters limit", Toast.LENGTH_SHORT).show()
            return
        }

        if (bodyText.length > 5000) {
            Toast.makeText(this, "Body text exceeds 5000 characters limit", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        db.collection("notices")
            .document(noticeId)
            .update(
                mapOf(
                    "title" to titleText,
                    "body" to bodyText,
                    "content" to bodyText,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "editedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                WidgetUpdater.refresh(this)
                binding.progressBar.isVisible = false
                Toast.makeText(this, "Notice updated", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setupTypeSelector() {
        binding.rgNoticeType.setOnCheckedChangeListener { _, checkedId ->
            binding.sectionNormal.isVisible = checkedId == R.id.rbNormal
            binding.sectionSubjectPicker.isVisible = checkedId == R.id.rbCancel || checkedId == R.id.rbSub
            binding.sectionSubTeacher.isVisible = checkedId == R.id.rbSub
            binding.sectionVacationDetails.isVisible = checkedId == R.id.rbVacation
            if (checkedId == R.id.rbPoll) {
                startActivity(Intent(this, CreatePollActivity::class.java))
                finish()
            }
        }
    }

    private fun setupPostingMode() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                isAiPostingMode = tab?.position == 1
                binding.sectionAiComposeContainer.isVisible = isAiPostingMode
                binding.rgNoticeType.isVisible = !isAiPostingMode
                binding.btnPublish.text = if (isAiPostingMode) "Publish AI Notice" else "Publish Notice"
                if (isAiPostingMode) {
                    binding.sectionNormal.isVisible = true
                    binding.sectionAttachment.isVisible = false
                    binding.sectionSubjectPicker.isVisible = false
                } else {
                    binding.rgNoticeType.check(binding.rgNoticeType.checkedRadioButtonId.takeIf { it != -1 } ?: R.id.rbNormal)
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        binding.btnAiAnalyze.setOnClickListener { analyzeAiDraft() }
    }

    private fun analyzeAiDraft() {
        val messy = binding.etAiMessyInput.text.toString().trim()
        if (messy.isBlank()) {
            binding.etAiMessyInput.error = "Paste the messy draft first"
            return
        }
        binding.btnAiAnalyze.isEnabled = false
        binding.btnAiAnalyze.text = "Drafting..."
        binding.progressBar.isVisible = true
        lifecycleScope.launch {
            val result = AIService.analyzeAndPolishNotice(
                messyText = messy,
                currentDateStr = DateHelper.today(),
                currentDayName = DateHelper.todayDayString(),
                subjects = SubjectList.subjects.map { it.name }
            )
            binding.progressBar.isVisible = false
            binding.btnAiAnalyze.isEnabled = true
            binding.btnAiAnalyze.text = "Draft with AI"
            if (result == null) {
                Toast.makeText(this@PostNoticeActivity, "AI could not prepare this notice. Try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            applyAiDraft(result)
        }
    }

    private fun applyAiDraft(result: com.shuaib.classmate.models.AiNoticeDraft) {
        val type = normalizeAiType(result.type ?: "GENERAL")
        aiPolishedNotice = result.copy(type = type)
        binding.etTitle.setText(result.title ?: "")
        binding.etBody.setText(result.body ?: "")
        binding.dropdownSubject.setText(result.subject ?: "", false)
        
        val displayDate = result.deadline ?: result.date ?: ""
        if (displayDate.isNotBlank()) {
            selectedSubmissionDate = displayDate
        }

        binding.sectionNormal.isVisible = true
        binding.sectionAttachment.isVisible = false
        when (type) {
            "cancellation" -> {
                binding.rgNoticeType.check(R.id.rbCancel)
                binding.sectionSubjectPicker.isVisible = true
            }
            else -> {
                binding.rgNoticeType.check(R.id.rbNormal)
                binding.sectionSubjectPicker.isVisible = false
            }
        }

        binding.tvAiDetected.text = buildString {
            append("✨ Notice Draft Polished!\n\n")
            append("🏷️ Type: ${type.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } }}\n")
            append("📚 Course/Subject: ${result.subject?.ifBlank { "General" } ?: "General"}\n")
            if (displayDate.isNotBlank()) {
                append("📅 Date Detected: $displayDate\n")
            }
            if (result.isUrgent == true) {
                append("⚠️ Priority: URGENT\n")
            } else {
                append("🟢 Priority: NORMAL\n")
            }
            val missing = result.missingFields
            if (!missing.isNullOrEmpty()) {
                append("\n🔍 Missing details you might want to add: ${missing.filterNotNull().joinToString(", ")}")
            }
        }

        Toast.makeText(this, "✅ AI polished notice applied below!", Toast.LENGTH_LONG).show()

        binding.scrollView.post {
            binding.scrollView.smoothScrollTo(0, binding.sectionManualModeContainer.top)
        }
    }

    private fun setupSubjectPicker() {
        val subjectNames = SubjectList.subjects.map { it.name }
        val subjectAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, subjectNames)
        binding.dropdownSubject.setAdapter(subjectAdapter)
    }

    private fun setupAttachmentButtons() {
        binding.btnAttachPdf.setOnClickListener {
            pdfPicker.launch("application/pdf")
        }
        binding.btnAttachImage.setOnClickListener {
            imagePicker.launch("image/*")
        }
        binding.btnRemovePdf.setOnClickListener {
            selectedPdfUri = null
            attachmentType = "none"
            binding.attachmentPreview.isVisible = false
        }
        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            attachmentType = "none"
            binding.attachmentPreview.isVisible = false
        }
    }



    private fun fetchAdminName() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                userName = doc.getString("name") ?: "Admin"
            }
    }

    private fun publishAiNotice() {
        val result = aiPolishedNotice
        if (result == null) {
            Toast.makeText(this, "Draft with AI before publishing.", Toast.LENGTH_SHORT).show()
            return
        }
        val title = binding.etTitle.text.toString().trim()
        val body = binding.etBody.text.toString().trim()
        if (title.isBlank()) {
            binding.etTitle.error = "Title required"
            return
        }
        if (body.isBlank()) {
            binding.etBody.error = "Body required"
            return
        }
        if (body.length > 5000) {
            Toast.makeText(this, "Body text exceeds 5000 characters limit", Toast.LENGTH_SHORT).show()
            return
        }

        when (normalizeAiType(result.type ?: "GENERAL")) {
            "cancellation" -> publishAiCancellation(result.copy(title = title, body = body))
            else -> {
                attachmentType = "none"
                uploadedAttachmentUrl = ""
                attachmentFileName = ""
                saveNoticeToFirestore(title, body)
            }
        }
    }

    private fun publishAiCancellation(result: com.shuaib.classmate.models.AiNoticeDraft) {
        val subject = result.subject?.ifBlank { binding.dropdownSubject.text.toString().trim() } ?: binding.dropdownSubject.text.toString().trim()
        val targetDate = result.date?.ifBlank { DateHelper.today() } ?: DateHelper.today()
        val targetDay = dayStringFromIso(targetDate) ?: DateHelper.todayDayString()
        val whenText = when (targetDate) {
            DateHelper.today() -> "today"
            DateHelper.tomorrow() -> "tomorrow"
            else -> targetDate
        }
        if (subject.isBlank() || subject == "General") {
            Toast.makeText(this, "AI needs a subject for class cancellation.", Toast.LENGTH_LONG).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val noticeData = hashMapOf(
            "title" to (result.title ?: "Class Cancelled"),
            "body" to (result.body ?: ""),
            "content" to (result.body ?: ""),
            "type" to "notice",
            "priority" to "high",
            "subject" to subject,
            "isCancel" to true,
            "isSub" to false,
            "isAssignment" to false,
            "isClassTest" to false,
            "isResource" to false,
            "postedBy" to userName,
            "createdBy" to (auth.currentUser?.uid ?: ""),
            "createdByName" to userName,
            "targetSubject" to subject,
            "targetDay" to targetDay,
            "cancelDate" to targetDate,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isPinned" to false,
            "isDeleted" to false,
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("notices")
            .add(noticeData)
            .addOnSuccessListener { docRef ->
                markPeriodAsCancelled(subject, targetDay, targetDate, whenText, docRef.id)
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun publishNormalNotice() {
        val titleText = binding.etTitle.text.toString().trim()
        val bodyText = binding.etBody.text.toString().trim()

        if (titleText.isEmpty()) {
            binding.etTitle.error = "Title required"
            return
        }
        if (bodyText.length > 5000) {
            Toast.makeText(this, "Body text exceeds 5000 characters limit", Toast.LENGTH_SHORT).show()
            return
        }

        if (bodyText.length > 5000) {
            Toast.makeText(this, "Body text exceeds 5000 characters limit", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        // If there's an attachment, upload first
        when (attachmentType) {
            "pdf" -> {
                binding.attachmentProgress.isVisible = true
                binding.tvAttachmentProgress.text = "Uploading PDF to Telegram..."

                TelegramUploader.uploadPdf(
                    context = this,
                    fileUri = selectedPdfUri!!,
                    title = titleText,
                    subject = "Notice Attachment",
                    uploadedBy = userName,
                    onProgress = { msg ->
                        binding.tvAttachmentProgress.text = msg
                    },
                    onSuccess = { telegramUrl, _ ->
                        binding.attachmentProgress.isVisible = false
                        uploadedAttachmentUrl = telegramUrl
                        saveNoticeToFirestore(titleText, bodyText)
                    },
                    onFailure = { error ->
                        binding.progressBar.isVisible = false
                        binding.btnPublish.isEnabled = true
                        binding.attachmentProgress.isVisible = false
                        Toast.makeText(this, "PDF upload failed: $error", Toast.LENGTH_LONG).show()
                    }
                )
            }
            "image" -> {
                binding.attachmentProgress.isVisible = true
                binding.tvAttachmentProgress.text = "Uploading image..."

                CloudinaryUploader.uploadImage(
                    context = this,
                    fileUri = selectedImageUri!!,
                    folder = "notice_images",
                    onSuccess = { imageUrl, _ ->
                        binding.attachmentProgress.isVisible = false
                        uploadedAttachmentUrl = imageUrl
                        saveNoticeToFirestore(titleText, bodyText)
                    },
                    onFailure = { error ->
                        binding.progressBar.isVisible = false
                        binding.btnPublish.isEnabled = true
                        binding.attachmentProgress.isVisible = false
                        Toast.makeText(this, "Image upload failed: $error", Toast.LENGTH_LONG).show()
                    }
                )
            }
            else -> {
                saveNoticeToFirestore(titleText, bodyText)
            }
        }
    }

    private fun saveNoticeToFirestore(title: String, body: String) {
        val noticeData = hashMapOf(
            "title" to title,
            "body" to body,
            "content" to body,
            "type" to "notice",
            "priority" to "normal",
            "subject" to "General",
            "isCancel" to false,
            "isSub" to false,
            "isAssignment" to false,
            "isClassTest" to false,
            "isResource" to false,
            "attachmentType" to attachmentType,
            "attachmentUrl" to uploadedAttachmentUrl,
            "attachmentName" to attachmentFileName,
            "attachments" to attachmentPayload(),
            "postedBy" to userName,
            "createdBy" to (auth.currentUser?.uid ?: ""),
            "createdByName" to userName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isPinned" to false,
            "isDeleted" to false,
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("notices")
            .add(noticeData)
            .addOnSuccessListener { docRef ->
                WidgetUpdater.refresh(this)
                NotificationSender.sendNoticeAlert(
                    title = title,
                    body = body,
                    noticeId = docRef.id,
                    onSuccess = {
                        binding.progressBar.isVisible = false
                        Toast.makeText(this, "✅ Notice posted!", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onFailure = {
                        binding.progressBar.isVisible = false
                        finish()
                    }
                )
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun publishCancellationNotice() {
        val selectedSubject = binding.dropdownSubject.text.toString().trim()

        if (selectedSubject.isEmpty()) {
            Toast.makeText(this, "Select a subject", Toast.LENGTH_SHORT).show()
            return
        }

        val isToday = binding.rbToday.isChecked
        val targetDayString = if (isToday) DateHelper.todayDayString() else DateHelper.tomorrowDayString()
        val targetDate = if (isToday) DateHelper.today() else DateHelper.tomorrow()
        val whenText = if (isToday) "today" else "tomorrow"

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val noticeData = hashMapOf(
            "title" to "Class Cancelled",
            "body" to "$selectedSubject class has been cancelled for $whenText",
            "content" to "$selectedSubject class has been cancelled for $whenText",
            "type" to "notice",
            "priority" to "high",
            "subject" to selectedSubject,
            "isCancel" to true,
            "isSub" to false,
            "isAssignment" to false,
            "isClassTest" to false,
            "isResource" to false,
            "postedBy" to userName,
            "createdBy" to (auth.currentUser?.uid ?: ""),
            "createdByName" to userName,
            "targetSubject" to selectedSubject,
            "targetDay" to targetDayString,
            "cancelDate" to targetDate,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isPinned" to false,
            "isDeleted" to false,
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("notices")
            .add(noticeData)
            .addOnSuccessListener { docRef ->
                markPeriodAsCancelled(
                    subject = selectedSubject,
                    day = targetDayString,
                    cancelDate = targetDate,
                    whenText = whenText,
                    noticeId = docRef.id
                )
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun markPeriodAsCancelled(subject: String, day: String, cancelDate: String, whenText: String, noticeId: String? = null) {
        db.collection("timetable")
            .document(day)
            .collection("periods")
            .whereEqualTo("subject", subject)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    sendCancellationNotification(subject, whenText, day, noticeId)
                    return@addOnSuccessListener
                }

                val batch = db.batch()
                snapshot.documents.forEach { doc ->
                    batch.update(doc.reference, mapOf(
                        "isCancelled" to true,
                        "cancelDate" to cancelDate
                    ))
                }

                batch.commit()
                    .addOnSuccessListener {
                        WidgetUpdater.refresh(this)
                        sendCancellationNotification(subject, whenText, day, noticeId)
                    }
                    .addOnFailureListener { e ->
                        sendCancellationNotification(subject, whenText, day, noticeId)
                        Log.e("CANCEL", "Timetable update failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                sendCancellationNotification(subject, whenText, day, noticeId)
                Log.e("CANCEL", "Query failed: ${e.message}")
            }
    }

    private fun sendCancellationNotification(subject: String, whenText: String, day: String, noticeId: String? = null) {
        NotificationSender.sendCancellationAlert(
            subject = subject,
            whenText = whenText,
            day = day,
            noticeId = noticeId,
            onSuccess = {
                binding.progressBar.isVisible = false
                Toast.makeText(this, "Cancellation published!", Toast.LENGTH_SHORT).show()
                finish()
            },
            onFailure = {
                binding.progressBar.isVisible = false
                Toast.makeText(this, "Notice saved!", Toast.LENGTH_SHORT).show()
                finish()
            }
        )
    }

    private fun publishSubstituteNotice() {
        val selectedSubject = binding.dropdownSubject.text.toString().trim()
        val subTeacher = binding.etSubTeacher.text.toString().trim()

        if (selectedSubject.isEmpty() || subTeacher.isEmpty()) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val isToday = binding.rbToday.isChecked
        val targetDayString = if (isToday) DateHelper.todayDayString() else DateHelper.tomorrowDayString()
        val targetDate = if (isToday) DateHelper.today() else DateHelper.tomorrow()
        val whenText = if (isToday) "today" else "tomorrow"

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val noticeData = hashMapOf(
            "title" to "Substitute Class",
            "body" to "$selectedSubject will be taken by $subTeacher $whenText",
            "content" to "$selectedSubject will be taken by $subTeacher $whenText",
            "type" to "notice",
            "priority" to "normal",
            "subject" to selectedSubject,
            "isCancel" to false,
            "isSub" to true,
            "isAssignment" to false,
            "isClassTest" to false,
            "isResource" to false,
            "postedBy" to userName,
            "createdBy" to (auth.currentUser?.uid ?: ""),
            "createdByName" to userName,
            "targetSubject" to selectedSubject,
            "targetDay" to targetDayString,
            "substituteTeacher" to subTeacher,
            "substituteDate" to targetDate,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isPinned" to false,
            "isDeleted" to false,
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("notices")
            .add(noticeData)
            .addOnSuccessListener {
                db.collection("timetable")
                    .document(targetDayString)
                    .collection("periods")
                    .whereEqualTo("subject", selectedSubject)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val batch = db.batch()
                        snapshot.documents.forEach { doc ->
                            batch.update(doc.reference, mapOf(
                                "isSubstitute" to true,
                                "substituteTeacher" to subTeacher,
                                "substituteDate" to targetDate
                            ))
                        }
                        batch.commit()
                            .addOnSuccessListener {
                                WidgetUpdater.refresh(this)
                                NotificationSender.sendSubstituteAlert(
                                    subject = selectedSubject,
                                    substituteTeacher = subTeacher,
                                    whenText = whenText,
                                    day = targetDayString,
                                    onSuccess = {
                                        binding.progressBar.isVisible = false
                                        Toast.makeText(this, "🔄 Substitute published!", Toast.LENGTH_SHORT).show()
                                        finish()
                                    },
                                    onFailure = {
                                        binding.progressBar.isVisible = false
                                        finish()
                                    }
                                )
                            }
                    }
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }



    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) {
                    name = cursor.getString(col)
                }
            }
        } catch (e: Exception) {
            Log.e("POST_NOTICE", "Error getting filename", e)
        }
        return name
    }

    private fun attachmentPayload(): List<Map<String, Any>> {
        if (attachmentType == "none" || uploadedAttachmentUrl.isBlank()) return emptyList()
        return listOf(
            mapOf(
                "name" to attachmentFileName.ifBlank { "Attachment" },
                "url" to uploadedAttachmentUrl,
                "type" to attachmentType,
                "size" to ""
            )
        )
    }

    private fun deadlineTimestamp(value: String): Timestamp? {
        if (value.isBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.let { Timestamp(it) }
        }.getOrNull()
    }

    private fun normalizeAiType(type: String): String {
        return when (type.trim().lowercase(Locale.US).replace("-", "_").replace(" ", "_")) {
            "cancel", "cancelled", "canceled", "class_cancel", "class_cancellation", "cancellation" -> "cancellation"
            "assignment", "deadline", "homework" -> "assignment"
            "classtest", "class_test", "quiz", "test", "exam" -> "class_test"
            else -> "normal"
        }
    }

    private fun dayStringFromIso(value: String): String? {
        return runCatching {
            val date: Date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) ?: return@runCatching null
            val cal = Calendar.getInstance().apply { time = date }
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SATURDAY -> "saturday"
                Calendar.SUNDAY -> "sunday"
                Calendar.MONDAY -> "monday"
                Calendar.TUESDAY -> "tuesday"
                Calendar.WEDNESDAY -> "wednesday"
                Calendar.THURSDAY -> "thursday"
                Calendar.FRIDAY -> "friday"
                else -> null
            }
        }.getOrNull()
    }
    private fun setupVacationSection() {
        val vacationTypes = listOf("Vacation", "Holiday", "Classes Suspended")
        val vacationAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, vacationTypes)
        binding.dropdownVacationType.setAdapter(vacationAdapter)
        binding.dropdownVacationType.setText(vacationTypes[0], false)

        binding.etVacationStart.setOnClickListener {
            val currentText = binding.etVacationStart.text.toString()
            showDatePicker(currentText) { dateStr ->
                binding.etVacationStart.setText(dateStr)
            }
        }
        binding.etVacationEnd.setOnClickListener {
            val currentText = binding.etVacationEnd.text.toString()
            showDatePicker(currentText) { dateStr ->
                binding.etVacationEnd.setText(dateStr)
            }
        }
    }

    private fun showDatePicker(initial: String, onDate: (String) -> Unit) {
        val date = try {
            java.time.LocalDate.parse(initial)
        } catch (_: Exception) {
            java.time.LocalDate.now()
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                onDate(java.time.LocalDate.of(year, month + 1, dayOfMonth).toString())
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }

    private fun publishVacationNotice() {
        val vacationTypeInput = binding.dropdownVacationType.text.toString().trim()
        val titleText = binding.etVacationTitle.text.toString().trim()
        val reasonText = binding.etVacationReason.text.toString().trim()
        val startDateText = binding.etVacationStart.text.toString().trim()
        val endDateText = binding.etVacationEnd.text.toString().trim()

        if (titleText.isEmpty()) {
            binding.etVacationTitle.error = "Title required"
            return
        }
        if (startDateText.isEmpty()) {
            binding.etVacationStart.error = "Start date required"
            return
        }
        if (endDateText.isEmpty()) {
            binding.etVacationEnd.error = "End date required"
            return
        }

        val startLocalDate = try { java.time.LocalDate.parse(startDateText) } catch (_: Exception) { null }
        val endLocalDate = try { java.time.LocalDate.parse(endDateText) } catch (_: Exception) { null }

        if (startLocalDate == null || endLocalDate == null) {
            Toast.makeText(this, "Dates must use yyyy-MM-dd format", Toast.LENGTH_SHORT).show()
            return
        }
        if (endLocalDate.isBefore(startLocalDate)) {
            Toast.makeText(this, "End date cannot be before start date", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val exceptionType = when (vacationTypeInput) {
            "Holiday" -> AcademicCalendarException.TYPE_HOLIDAY
            "Classes Suspended" -> AcademicCalendarException.TYPE_CLASS_SUSPENDED
            else -> AcademicCalendarException.TYPE_VACATION
        }

        val badge = when (exceptionType) {
            AcademicCalendarException.TYPE_HOLIDAY -> "[Holiday]"
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "[Suspension]"
            else -> "[Vacation]"
        }

        val noticeTitle = "$badge $titleText"
        val noticeBody = "Type: $vacationTypeInput\nPeriod: $startDateText to $endDateText\n\n$reasonText"

        val exception = AcademicCalendarException(
            title = titleText,
            type = exceptionType,
            startDate = startDateText,
            endDate = endDateText,
            scope = AcademicCalendarException.SCOPE_ALL_CLASSES,
            reason = reasonText,
            isActive = true,
            createdBy = auth.currentUser?.uid ?: ""
        )

        lifecycleScope.launch {
            val repository = AcademicCalendarRepository()
            val result = repository.saveException(exception)
            
            if (result.isSuccess) {
                val today = java.time.LocalDate.now()
                if (!today.isBefore(startLocalDate) && !today.isAfter(endLocalDate)) {
                    ClassReminderWorkCoordinator.cancelTodayClassReminders(this@PostNoticeActivity)
                }

                val noticeData = hashMapOf(
                    "title" to noticeTitle,
                    "body" to noticeBody,
                    "content" to noticeBody,
                    "type" to "notice",
                    "priority" to "high",
                    "subject" to "General",
                    "isCancel" to false,
                    "isSub" to false,
                    "isAssignment" to false,
                    "isClassTest" to false,
                    "isResource" to false,
                    "postedBy" to userName,
                    "createdBy" to (auth.currentUser?.uid ?: ""),
                    "createdByName" to userName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "isPinned" to true,
                    "isDeleted" to false,
                    "timestamp" to FieldValue.serverTimestamp()
                )

                db.collection("notices")
                    .add(noticeData)
                    .addOnSuccessListener { docRef ->
                        WidgetUpdater.refresh(this@PostNoticeActivity)
                        NotificationSender.sendNoticeAlert(
                            title = noticeTitle,
                            body = noticeBody,
                            noticeId = docRef.id,
                            onSuccess = {
                                binding.progressBar.isVisible = false
                                Toast.makeText(this@PostNoticeActivity, "✅ Vacation notice and exception published!", Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            onFailure = {
                                binding.progressBar.isVisible = false
                                finish()
                            }
                        )
                    }
                    .addOnFailureListener { e ->
                        binding.progressBar.isVisible = false
                        binding.btnPublish.isEnabled = true
                        Toast.makeText(this@PostNoticeActivity, "Notice creation failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            } else {
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                Toast.makeText(this@PostNoticeActivity, "Calendar Exception failed: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
