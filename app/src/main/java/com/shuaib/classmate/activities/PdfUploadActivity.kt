package com.shuaib.classmate.activities

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityPdfUploadBinding
import com.shuaib.classmate.storage.GitHubReleaseStorageClient
import com.shuaib.classmate.utils.NotificationSender
import com.shuaib.classmate.utils.SubjectList

class PdfUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfUploadBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var selectedFileUri: Uri? = null
    private var selectedFileInfo: SelectedFileInfo? = null
    private var currentUserName = ""
    private var isLinkMode = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val info = getSelectedFileInfo(it)
            selectedFileUri = it
            selectedFileInfo = info
            binding.tvSelectedFile.text = "${info.displayName} - ${formatBytes(info.sizeBytes)} - ${info.fileType.uppercase()}"
            binding.tvSelectedFile.setTextColor(Color.parseColor("#34D399"))
            binding.btnSave.isEnabled = true

            if (binding.etTitle.text.isNullOrBlank()) {
                val titleSuggestion = info.displayName.substringBeforeLast(".")
                    .replace("_", " ")
                    .replace("-", " ")
                binding.etTitle.setText(titleSuggestion)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.btnBack.setOnClickListener { finish() }
        updateMode(false)

        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: "Admin"
            }

        val subjectNames = SubjectList.subjects.map { it.name }
        binding.dropdownSubject.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, subjectNames)
        )
        binding.dropdownSubject.setOnItemClickListener { _, _, position, _ ->
            binding.etCourseCode.setText(SubjectList.subjects.getOrNull(position)?.code.orEmpty())
        }

        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) updateMode(checkedId == R.id.btnModeDrive)
        }

        binding.btnPickFile.setOnClickListener {
            filePicker.launch("*/*")
        }

        binding.btnSave.setOnClickListener {
            if (isLinkMode) {
                handleLinkUpload()
            } else {
                handleGitHubUpload()
            }
        }
    }

    private fun updateMode(linkMode: Boolean) {
        isLinkMode = linkMode
        binding.layoutTelegram.isVisible = !linkMode
        binding.layoutDrive.isVisible = linkMode
        binding.btnSave.isEnabled = linkMode || selectedFileUri != null
        binding.btnSave.text = if (linkMode) "Add Link to Library" else "Upload to Library"
    }

    private fun handleLinkUpload() {
        val input = validateCommonInputs() ?: return
        val link = binding.etDriveUrl.text.toString().trim()
        if (link.isEmpty()) {
            binding.etDriveUrl.error = "Link required"
            return
        }
        saveExternalLink(input, link)
    }

    private fun handleGitHubUpload() {
        val input = validateCommonInputs() ?: return
        val uri = selectedFileUri ?: run {
            Toast.makeText(this, "Pick a file first", Toast.LENGTH_SHORT).show()
            return
        }
        val info = selectedFileInfo ?: getSelectedFileInfo(uri)

        if (!isSupportedMimeType(info.mimeType)) {
            Toast.makeText(this, "Unsupported file type: ${info.mimeType}", Toast.LENGTH_LONG).show()
            return
        }
        if (info.sizeBytes > MAX_UPLOAD_BYTES) {
            Toast.makeText(this, "File too large. Keep library uploads under 100 MB.", Toast.LENGTH_LONG).show()
            return
        }

        val safeName = createSafeAssetFileName(input.courseCode, input.subject, info.displayName)
        setUploading(true, "Preparing GitHub upload...")

        Thread {
            try {
                val result = GitHubReleaseStorageClient(applicationContext).uploadAsset(
                    fileUri = uri,
                    assetName = safeName,
                    mimeType = info.mimeType,
                    onProgress = { progress ->
                        runOnUiThread { binding.tvProgress.text = "Uploading to GitHub... $progress%" }
                    }
                )
                runOnUiThread {
                    saveGitHubMetadata(input, info, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                runOnUiThread {
                    handleUploadError(
                        when {
                            e.message?.contains("release not found", true) == true -> "GitHub release not found. Check owner/repo/tag."
                            e.message?.contains("token invalid", true) == true -> "GitHub token invalid or expired"
                            e.message?.contains("name may already exist", true) == true -> "A file with this name may already exist."
                            else -> e.message ?: "Upload failed. Check internet connection."
                        }
                    )
                }
            }
        }.start()
    }

    private fun saveGitHubMetadata(
        input: UploadInput,
        info: SelectedFileInfo,
        result: com.shuaib.classmate.storage.GitHubUploadResult
    ) {
        binding.tvProgress.text = "Saving to library..."
        val uid = auth.currentUser?.uid.orEmpty()
        val courseType = courseTypeFor(input.subject)
        val data = hashMapOf(
            "title" to input.title,
            "subject" to input.subject,
            "courseCode" to input.courseCode,
            "courseType" to courseType,
            "description" to input.description,
            "fileType" to info.fileType,
            "mimeType" to result.mimeType,
            "sizeBytes" to result.sizeBytes,
            "provider" to "github_releases",
            "downloadUrl" to result.downloadUrl,
            "driveUrl" to "",
            "githubAssetId" to result.assetId,
            "githubAssetName" to result.assetName,
            "uploadedByUid" to uid,
            "uploadedByName" to currentUserName,
            "uploadedBy" to currentUserName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "timestamp" to FieldValue.serverTimestamp(),
            "downloadCount" to 0L,
            "isDeleted" to false
        )

        db.collection("library_files")
            .add(data)
            .addOnSuccessListener { doc ->
                postResourceNotice(input.title, input.subject, doc.id, input.description)
                setUploading(false)
                Toast.makeText(this, "Successfully added to library!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore error: ${e.message}")
                handleUploadError("Firestore error: ${e.message}")
            }
    }

    private fun saveExternalLink(input: UploadInput, link: String) {
        setUploading(true, "Saving to library...")
        val uid = auth.currentUser?.uid.orEmpty()
        val data = hashMapOf(
            "title" to input.title,
            "subject" to input.subject,
            "courseCode" to input.courseCode,
            "courseType" to courseTypeFor(input.subject),
            "description" to input.description,
            "fileType" to "other",
            "mimeType" to "application/octet-stream",
            "sizeBytes" to 0L,
            "provider" to "external_link",
            "downloadUrl" to link,
            "driveUrl" to link,
            "githubAssetId" to 0L,
            "githubAssetName" to "",
            "uploadedByUid" to uid,
            "uploadedByName" to currentUserName,
            "uploadedBy" to currentUserName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "timestamp" to FieldValue.serverTimestamp(),
            "downloadCount" to 0L,
            "isDeleted" to false
        )
        db.collection("library_files")
            .add(data)
            .addOnSuccessListener { doc ->
                postResourceNotice(input.title, input.subject, doc.id, input.description)
                setUploading(false)
                Toast.makeText(this, "Successfully added to library!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                handleUploadError("Firestore error: ${e.message}")
            }
    }

    private fun postResourceNotice(title: String, subject: String, resourceId: String, description: String = "") {
        val noticeBody = if (description.isNotBlank()) {
            description
        } else {
            "New study material has been posted for $subject."
        }

        val noticeData = hashMapOf(
            "title" to "New Material: $title",
            "body" to noticeBody,
            "postedBy" to currentUserName,
            "timestamp" to FieldValue.serverTimestamp(),
            "isResource" to true,
            "subject" to subject,
            "pdfId" to resourceId
        )
        db.collection("notices").add(noticeData)
        NotificationSender.sendResourceAlert(
            title = title,
            subject = subject,
            onSuccess = { Log.d(TAG, "Resource notification sent") },
            onFailure = { err -> Log.e(TAG, "Resource notification failed: $err") }
        )
    }

    private fun validateCommonInputs(): UploadInput? {
        val title = binding.etTitle.text.toString().trim()
        val subject = binding.dropdownSubject.text.toString().trim()
        val courseCode = binding.etCourseCode.text.toString().trim().uppercase()
            .ifBlank { SubjectList.codeFor(subject).uppercase() }
        val description = binding.etDescription.text.toString().trim()

        if (title.isEmpty()) {
            binding.etTitle.error = "Title required"
            return null
        }
        if (subject.isEmpty()) {
            Toast.makeText(this, "Select a subject", Toast.LENGTH_SHORT).show()
            return null
        }
        if (courseCode.isEmpty()) {
            binding.etCourseCode.error = "Course code required"
            return null
        }
        return UploadInput(title, subject, courseCode, description)
    }

    private fun getSelectedFileInfo(uri: Uri): SelectedFileInfo {
        var displayName = "library_file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameCol >= 0) displayName = cursor.getString(nameCol) ?: displayName
                if (sizeCol >= 0) size = cursor.getLong(sizeCol)
            }
        }
        val mimeType = normalizeMimeType(contentResolver.getType(uri), displayName)
        return SelectedFileInfo(
            displayName = displayName,
            mimeType = mimeType,
            extension = displayName.substringAfterLast(".", ""),
            sizeBytes = size.coerceAtLeast(0L),
            fileType = fileTypeFor(mimeType, displayName)
        )
    }

    private fun normalizeMimeType(rawMimeType: String?, fileName: String): String {
        if (!rawMimeType.isNullOrBlank()) return rawMimeType
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun isSupportedMimeType(mimeType: String): Boolean {
        return mimeType in SUPPORTED_MIME_TYPES || mimeType.startsWith("image/")
    }

    private fun fileTypeFor(mimeType: String, fileName: String): String {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return when {
            mimeType == "application/pdf" || extension == "pdf" -> "pdf"
            extension == "pptx" -> "pptx"
            mimeType.contains("presentation") || extension == "ppt" -> "ppt"
            mimeType == "application/msword" || extension == "doc" -> "doc"
            extension == "docx" -> "docx"
            mimeType == "application/vnd.ms-excel" || extension == "xls" -> "xls"
            extension == "xlsx" -> "xlsx"
            mimeType.startsWith("image/") -> "image"
            mimeType == "application/zip" || extension == "zip" -> "zip"
            else -> "other"
        }
    }

    private fun courseTypeFor(subject: String): String {
        return when {
            subject.endsWith("Lab", ignoreCase = true) -> "lab"
            subject.equals("Other Document", ignoreCase = true) -> "other"
            else -> "regular"
        }
    }

    private fun createSafeAssetFileName(courseCode: String, subject: String, originalName: String): String {
        val extension = originalName.substringAfterLast(".", "").takeIf { it.isNotBlank() }
        val baseName = originalName.substringBeforeLast(".")
        val raw = listOf(courseCode, subject, System.currentTimeMillis().toString(), baseName)
            .joinToString("_")
            .replace("\\s+".toRegex(), "_")
            .replace("[^A-Za-z0-9._-]".toRegex(), "_")
            .replace("_+".toRegex(), "_")
            .trim('_')
        return if (extension == null) raw else "$raw.${extension.lowercase()}"
    }

    private fun setUploading(uploading: Boolean, message: String = "") {
        binding.progressBar.isVisible = uploading
        binding.tvProgress.isVisible = uploading
        binding.tvProgress.text = message
        binding.btnSave.isEnabled = !uploading
        binding.btnPickFile.isEnabled = !uploading
    }

    private fun handleUploadError(error: String) {
        setUploading(false)
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Unknown size"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
    }

    private data class UploadInput(
        val title: String,
        val subject: String,
        val courseCode: String,
        val description: String
    )

    private data class SelectedFileInfo(
        val displayName: String,
        val mimeType: String,
        val extension: String,
        val sizeBytes: Long,
        val fileType: String
    )

    companion object {
        private const val TAG = "GitHubStorageDebug"
        private const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L
        private val SUPPORTED_MIME_TYPES = setOf(
            "application/pdf",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "image/png",
            "image/jpeg",
            "image/webp",
            "application/zip",
            "application/octet-stream"
        )
    }
}
