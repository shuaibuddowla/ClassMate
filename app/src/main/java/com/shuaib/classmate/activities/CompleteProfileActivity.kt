package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityCompleteProfileBinding
import com.shuaib.classmate.utils.CloudinaryUploader
import com.shuaib.classmate.utils.StudentIdUtils

class CompleteProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompleteProfileBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var currentStep = 0
    private var selectedImageUri: Uri? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            binding.ivProfile.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompleteProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadInitialData()

        binding.btnNext2.setOnClickListener { validateAndNextStep2() }
        binding.tvSkip2.setOnClickListener { navigateToMain() }
        binding.tvSkipPhoto.setOnClickListener { navigateToMain() }
        binding.btnChangePhoto.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.btnFinish.setOnClickListener { uploadAndFinish() }
    }

    private fun setupUI() {
        // Step 2 Dropdown (now Step 1)
        val bloodGroups = arrayOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
        val adapterBlood = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bloodGroups)
        binding.spinnerBlood.setAdapter(adapterBlood)
    }

    private fun loadInitialData() {
        val user = auth.currentUser ?: return
        
        // Load existing photo if available
        user.photoUrl?.let {
            Glide.with(this)
                .load(it)
                .placeholder(R.drawable.ic_default_avatar)
                .into(binding.ivProfile)
        }
    }

    private fun validateAndNextStep2() {
        val phone = binding.etPhone.text.toString().trim()
        val blood = binding.spinnerBlood.text.toString().trim()
        val district = binding.etDistrict.text.toString().trim()

        // Optional fields but we save whatever is there
        saveDataToFirestore(mapOf(
            "phone" to phone,
            "bloodGroup" to blood,
            "homeDistrict" to district
        ))
        nextStep()
    }

    private fun nextStep() {
        currentStep++
        binding.viewFlipper.showNext()
        updateProgressDots()
    }

    private fun updateProgressDots() {
        binding.dotStep1.setBackgroundResource(if (currentStep == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
        binding.dotStep2.setBackgroundResource(if (currentStep == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
    }

    private fun uploadAndFinish() {
        val uri = selectedImageUri
        if (uri != null) {
            setLoading(true)
            CloudinaryUploader.uploadImage(this, uri, "profiles", 
                onSuccess = { url, _ ->
                    saveDataToFirestore(mapOf("photoUrl" to url))
                    setLoading(false)
                    navigateToMain()
                },
                onFailure = { error ->
                    setLoading(false)
                    Toast.makeText(this, "Upload failed: $error", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            navigateToMain()
        }
    }

    private fun saveDataToFirestore(data: Map<String, Any>) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .set(data, SetOptions.merge())
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnFinish.isEnabled = !loading
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
