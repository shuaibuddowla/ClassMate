// com/shuaib/classmate/activities/ResultUploadActivity.kt
package com.shuaib.classmate.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityResultUploadBinding
import com.shuaib.classmate.utils.TelegramUploader
import com.shuaib.classmate.utils.applyClickAnimation

class ResultUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultUploadBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    private var selectedPdfUri: Uri? = null
    private var userName: String = "Admin"

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedPdfUri = it
            binding.tvSelectedFileName.text = getFileName(it)
            Log.d("UPLOAD", "Result file selected: $it")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        fetchAdminName()

        binding.toolbar.setNavigationOnClickListener { 
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        binding.btnSelectFile.applyClickAnimation {
            pdfPickerLauncher.launch("application/pdf")
        }

        binding.btnUpload.applyClickAnimation {
            uploadResult()
        }
    }

    private fun fetchAdminName() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                userName = document.getString("name") ?: "Admin"
            }
    }

    private fun getFileName(uri: Uri): String {
        var name = "result.pdf"
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun uploadResult() {
        val titleText = binding.etResultTitle.text.toString().trim()
        val uri = selectedPdfUri

        if (uri == null || titleText.isEmpty()) {
            Toast.makeText(this, "Please select a file and enter a title", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnUpload.isEnabled = false

        Log.d("UPLOAD", "Starting result upload for: $titleText")

        TelegramUploader.uploadPdf(
            context = this,
            fileUri = uri,
            title = titleText,
            subject = "Result",
            uploadedBy = userName,
            onProgress = { message ->
                // You could add a progress text view if needed
            },
            onSuccess = { telegramUrl, fileId ->
                saveMetadataToFirestore(titleText, telegramUrl, fileId)
            },
            onFailure = { error ->
                Log.e("UPLOAD", "Result upload failed: $error")
                binding.progressBar.isVisible = false
                binding.btnUpload.isEnabled = true
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun saveMetadataToFirestore(title: String, telegramUrl: String, fileId: String) {
        val data = hashMapOf(
            "title" to title,
            "telegramUrl" to telegramUrl,
            "fileId" to fileId,
            "uploadedBy" to userName,
            "timestamp" to FieldValue.serverTimestamp()
        )

        firestore.collection("results")
            .add(data)
            .addOnSuccessListener {
                binding.progressBar.isVisible = false
                Toast.makeText(this, "Result uploaded!", Toast.LENGTH_SHORT).show()
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnUpload.isEnabled = true
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
