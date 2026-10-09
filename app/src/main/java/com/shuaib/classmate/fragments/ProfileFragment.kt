// C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/fragments/ProfileFragment.kt
package com.shuaib.classmate.fragments

import com.shuaib.classmate.BuildConfig
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.google.firebase.firestore.ListenerRegistration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONObject
import java.util.Locale
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.onesignal.OneSignal
import com.shuaib.classmate.activities.LoginActivity
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.AdminPanelActivity
import com.shuaib.classmate.activities.AiSettingsActivity
import com.shuaib.classmate.activities.ClassMateBloodActivity
import com.shuaib.classmate.activities.UserManagementActivity
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.activities.PostNoticeActivity
import com.shuaib.classmate.adapters.PdfAdapter

import com.shuaib.classmate.adapters.SubjectAdapter
import com.shuaib.classmate.databinding.DialogEditProfileBinding
import com.shuaib.classmate.databinding.FragmentProfileBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.repositories.ArchiveLibraryRepository

import com.shuaib.classmate.models.User
import com.shuaib.classmate.storage.LibraryUrlOpener
import com.shuaib.classmate.ui.GlowHelper
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.utils.CloudinaryUploader
import com.shuaib.classmate.utils.Subject
import com.shuaib.classmate.utils.SubjectList
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import kotlinx.coroutines.launch
import java.security.MessageDigest
import com.shuaib.classmate.update.UpdateActionActivity
import com.shuaib.classmate.update.UpdateCoordinator
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.databinding.DialogAppUpdateBinding
import kotlinx.coroutines.Job

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var currentUser: User? = null
    private var tempImageUri: Uri? = null
    private var profileListener: ListenerRegistration? = null
    private var configListener: ListenerRegistration? = null
    private var isFriendsPublic = false

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadProfilePicture(it) }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) tempImageUri?.let { uploadProfilePicture(it) }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            com.shuaib.classmate.services.ShakeToTorchService.start(requireContext())
        } else {
            Toast.makeText(context, "Camera permission is required to turn on the flashlight.", Toast.LENGTH_SHORT).show()
            binding.switchShakeToTorch.isChecked = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupDarkModeToggle()
        setupNotificationsToggle()
        setupAutoMuteToggle()
        setupShakeToTorchToggle()
        setupAiSettings()
        setupSemesterManagement()
        updateOfflineCacheSize()
        listenToFriendsConfig()
        fetchUserProfile()

        GlowHelper.pulseGlow(binding.profileBorder, ThemeColors.primary(requireContext()))

        binding.cardPersonalInfo.applyClickAnimation {
            currentUser?.let { showEditProfileDialog(it) }
        }

        binding.cardBloodEmergency.applyClickAnimation {
            val batchId = currentUser?.batchId.orEmpty().ifBlank { com.shuaib.classmate.utils.AppContextManager.getBatchId() }
            val intent = Intent(requireContext(), ClassMateBloodActivity::class.java).apply {
                if (batchId.isNotBlank()) putExtra("batch_id", batchId)
            }
            startActivity(intent)
        }

        binding.layoutSubInfo.setOnClickListener {
            copyIdentifierToClipboard()
        }

        binding.ivCopyId.setOnClickListener {
            copyIdentifierToClipboard()
        }

        binding.tileBloodGroup.applyClickAnimation {
            val batchId = currentUser?.batchId.orEmpty().ifBlank { com.shuaib.classmate.utils.AppContextManager.getBatchId() }
            val intent = Intent(requireContext(), ClassMateBloodActivity::class.java).apply {
                if (batchId.isNotBlank()) putExtra("batch_id", batchId)
            }
            startActivity(intent)
        }

        binding.tileBatchSession.applyClickAnimation {
            currentUser?.let { showEditProfileDialog(it) }
        }

        binding.tileHomeDistrict.applyClickAnimation {
            currentUser?.let { showEditProfileDialog(it) }
        }

        binding.tileContactPhone.applyClickAnimation {
            currentUser?.let { showEditProfileDialog(it) }
        }

        binding.cardAdminPanel.applyClickAnimation {
            startActivity(Intent(requireContext(), AdminPanelActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        binding.layoutUserManagement.setOnClickListener {
            startActivity(Intent(requireContext(), UserManagementActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        binding.layoutAddWidget.setOnClickListener {
            val context = requireContext()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
                val myProvider = android.content.ComponentName(context, com.shuaib.classmate.widget.ClassMateWidget::class.java)

                if (appWidgetManager.isRequestPinAppWidgetSupported) {
                    val successCallback = android.app.PendingIntent.getBroadcast(
                        context, 0,
                        android.content.Intent(context, com.shuaib.classmate.widget.ClassMateWidget::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
                    Toast.makeText(context, "Requesting to pin ClassMate Widget...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Launcher does not support automatic pinning. Long press home screen > Widgets > Find ClassMate Widget",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    "Long press your home screen > Widgets > Find ClassMate Widget",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnLogout.applyClickAnimation {
            signOut()
        }

        binding.layoutDeleteOfflineCache.setOnClickListener {
            val context = requireContext()
            val offlineFiles = com.shuaib.classmate.storage.LibraryDownloadManager.getDownloadedFiles(context)
            if (offlineFiles.isEmpty()) {
                Toast.makeText(context, "No offline cache found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_ClassMate_Dialog)
                .setTitle("Delete Offline Cache")
                .setMessage("Are you sure you want to delete all offline PDF files? This will free up storage space.")
                .setPositiveButton("Delete") { _, _ ->
                    offlineFiles.forEach { pdf ->
                        com.shuaib.classmate.storage.LibraryDownloadManager.deleteDownload(context, pdf.id)
                    }
                    Toast.makeText(context, "Offline cache cleared", Toast.LENGTH_SHORT).show()
                    updateOfflineCacheSize()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }



        binding.switchFriendsPublic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isFriendsPublic) return@setOnCheckedChangeListener
            firestore.collection("config").document("friends")
                .set(mapOf("isPublic" to isChecked))
                .addOnSuccessListener {
                    Toast.makeText(context, "Friends list visibility updated!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to update visibility: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.switchFriendsPublic.isChecked = isFriendsPublic
                }
        }
        
        setupAppVersion()
    }

    private fun setupAppVersion() {
        val currentVersion = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        binding.tvAppVersion.text = currentVersion
        
        binding.layoutCheckUpdates.setOnClickListener {
            startActivity(Intent(requireContext(), UpdateActionActivity::class.java)
                .setAction(UpdateActionActivity.ACTION_CHECK))
        }
        val prefs = AppPreferences(requireContext())
        binding.switchAutoUpdates.isChecked = prefs.isAutoUpdateEnabled()
        binding.switchAutoUpdates.setOnCheckedChangeListener { _, checked ->
            prefs.setAutoUpdateEnabled(checked)
            UpdateCoordinator.schedule(requireContext())
            if (checked) UpdateCoordinator.enqueueForegroundCheck(requireContext())
        }
        binding.switchWifiOnlyUpdates.isChecked = prefs.isWifiOnlyUpdates()
        binding.switchWifiOnlyUpdates.setOnCheckedChangeListener { _, checked ->
            prefs.setWifiOnlyUpdates(checked)
            UpdateCoordinator.schedule(requireContext())
            if (!checked) UpdateCoordinator.enqueueForegroundCheck(requireContext())
        }
    }

    private fun signOut() {
        val context = requireContext()
        val uid = auth.currentUser?.uid
        if (uid != null) {
            val updates = mapOf(
                "oneSignalPlayerId" to com.google.firebase.firestore.FieldValue.delete(),
                "onesignalPlayerId" to com.google.firebase.firestore.FieldValue.delete(),
                "playerId" to com.google.firebase.firestore.FieldValue.delete()
            )
            FirebaseFirestore.getInstance().document("users/$uid")
                .update(updates)
                .addOnFailureListener { e ->
                    android.util.Log.e("ProfileFragment", "Failed to clear push ID in Firestore: ${e.message}")
                }
        }
        
        try {
            OneSignal.User.pushSubscription.optOut()
            OneSignal.logout()
        } catch (e: Exception) {
            android.util.Log.e("ProfileFragment", "OneSignal signout failed: ${e.message}")
        }
        
        com.shuaib.classmate.chat.ChatRepository.close()
        auth.signOut()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
            }
            val intent = Intent(context, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            requireActivity().finishAffinity()
        }
    }

    private fun copyIdentifierToClipboard() {
        val user = currentUser ?: return
        val idToCopy = user.studentId.ifBlank { user.email }
        if (idToCopy.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Student Identifier", idToCopy)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied: $idToCopy", Toast.LENGTH_SHORT).show()
    }

    private fun updateOfflineCacheSize() {
        val ctx = context ?: return
        val offlineFiles = com.shuaib.classmate.storage.LibraryDownloadManager.getDownloadedFiles(ctx)
        val totalBytes = offlineFiles.sumOf { it.sizeBytes }
        val sizeText = when {
            totalBytes <= 0 -> "0 KB cached"
            totalBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB cached", totalBytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB cached", totalBytes / (1024.0 * 1024.0))
        }
        binding.tvCacheStorageSize.text = sizeText
    }

    private fun showEditProfileDialog(user: User) {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_ClassMate_Dialog)
        val dialogBinding = DialogEditProfileBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        dialogBinding.etEditName.setText(user.name)
        dialogBinding.etEditStudentId.setText(user.studentId)
        dialogBinding.etEditPhone.setText(user.phone)
        dialogBinding.etEditBlood.setText(user.bloodGroup)
        dialogBinding.etEditDistrict.setText(user.homeDistrict)
        dialogBinding.etEditAddress.setText(user.address)

        val bloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown")
        val bloodAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, bloodGroups)
        dialogBinding.etEditBlood.setAdapter(bloodAdapter)

        dialogBinding.btnCancelProfile.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveProfile.setOnClickListener {
            val name = dialogBinding.etEditName.text.toString().trim()
            val studentId = dialogBinding.etEditStudentId.text.toString().trim()
            val phone = dialogBinding.etEditPhone.text.toString().trim()
            val blood = dialogBinding.etEditBlood.text.toString().trim()
            val district = dialogBinding.etEditDistrict.text.toString().trim()
            val address = dialogBinding.etEditAddress.text.toString().trim()

            dialogBinding.btnSaveProfile.isEnabled = false
            dialogBinding.btnCancelProfile.isEnabled = false
            dialogBinding.pbSavingProfile.isVisible = true

            val updatedData = mapOf(
                "name" to name,
                "fullName" to name,
                "studentId" to studentId,
                "phone" to phone,
                "bloodGroup" to blood,
                "homeDistrict" to district,
                "address" to address
            )

            val uid = auth.currentUser?.uid
            if (uid != null) {
                firestore.collection("users").document(uid).update(updatedData)
                    .addOnSuccessListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                ClassMateAuthApi.rpc(
                                    "save_profile_details",
                                    JSONObject().apply {
                                        put("target_mobile", phone)
                                        put("target_town", district)
                                        put("target_blood", blood)
                                        put("target_residence", address)
                                    }
                                )
                            }
                        }
                        Toast.makeText(context, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        fetchUserProfile()
                    }
                    .addOnFailureListener { e ->
                        dialogBinding.btnSaveProfile.isEnabled = true
                        dialogBinding.btnCancelProfile.isEnabled = true
                        dialogBinding.pbSavingProfile.isVisible = false
                        Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showPhotoOptions() {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_ClassMate_Dialog)
        val view = layoutInflater.inflate(R.layout.dialog_photo_options, null)

        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            dialog.dismiss()
            openCamera()
        }
        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            dialog.dismiss()
            galleryLauncher.launch("image/*")
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun openCamera() {
        val photoFile = File(requireContext().cacheDir, "profile_temp.jpg")
        tempImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", photoFile)
        cameraLauncher.launch(tempImageUri)
    }

    private fun setupDarkModeToggle() {
        val prefs = AppPreferences(requireContext())
        val isDarkMode = prefs.isDarkMode()
        binding.switchDarkMode.isChecked = isDarkMode

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.isDarkMode() == isChecked) return@setOnCheckedChangeListener
            prefs.setDarkMode(isChecked)

            // Re-apply theme with a slight delay to allow the switch animation to finish
            // and avoid immediate activity recreation in the middle of a callback
            binding.root.postDelayed({
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }, 150)
        }
    }

    private fun setupNotificationsToggle() {
        val prefs = AppPreferences(requireContext())
        val isNotificationsEnabled = prefs.isNotificationsEnabled()
        binding.switchNotifications.isChecked = isNotificationsEnabled
        updatePushStatusPill(isNotificationsEnabled)

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.setNotificationsEnabled(isChecked)
            updatePushStatusPill(isChecked)
            if (isChecked) OneSignal.User.pushSubscription.optIn()
            else OneSignal.User.pushSubscription.optOut()
        }
    }

    private fun updatePushStatusPill(enabled: Boolean) {
        binding.tvPushStatusPill.text = if (enabled) "Active" else "Disabled"
        binding.tvPushStatusPill.setBackgroundResource(
            if (enabled) R.drawable.bg_status_pill_active else R.drawable.bg_status_pill_inactive
        )
        binding.tvPushStatusPill.setTextColor(
            if (enabled) Color.parseColor("#10B981") else Color.parseColor("#94A3B8")
        )
    }

    private fun setupAutoMuteToggle() {
        val prefs = AppPreferences(requireContext())
        binding.switchAutoMute.isChecked = prefs.isAutoMuteEnabled()

        binding.switchAutoMute.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoMuteEnabled(isChecked)
            
            if (isChecked) {
                val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val hasPolicyAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    notificationManager?.isNotificationPolicyAccessGranted == true
                } else {
                    true
                }

                if (!hasPolicyAccess) {
                    showDndPermissionDialog()
                }
            }

            com.shuaib.classmate.services.AutoMuteScheduler.scheduleAlarms(requireContext())
        }
    }

    private fun showDndPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Silent Mode Access Required")
            .setMessage("To allow ClassMate to shift your phone into complete Silent mode during class, Do Not Disturb access is required. If not granted, the app will fall back to Vibrate mode.")
            .setPositiveButton("Grant Access") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Use Vibrate Mode Only") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setupAiSettings() {
        binding.cardAiSettings.applyClickAnimation {
            startActivity(Intent(requireContext(), AiSettingsActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }



    private fun fetchUserProfile() {
        val uid = auth.currentUser?.uid ?: return
        profileListener?.remove()
        profileListener = firestore.collection("users").document(uid)
            .addSnapshotListener { document, error ->
                if (_binding == null || !isAdded) return@addSnapshotListener
                if (error != null) {
                    android.util.Log.e("ProfileFragment", "Error listening to profile", error)
                    return@addSnapshotListener
                }
                if (document != null && document.exists()) {
                    val user = document.toObject(User::class.java)
                    user?.let {
                        currentUser = it
                        updateUI(it)
                        _binding?.let { activeBinding ->
                            loadProfilePicture(it.email, it.photoUrl, activeBinding.ivProfile)
                        }
                    }
                }
            }
    }

    private fun applyRoleBadge(textView: TextView, role: String) {
        val fill = when (role.lowercase()) {
            "superadmin" -> "#FF4D6D"
            "admin" -> "#FFB347"
            else -> "#34D399"
        }
        textView.setTextColor(ThemeColors.textInverse(textView.context))
        textView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor(fill))
        }
    }

    private fun updateUI(user: User) {
        if (_binding == null || !isAdded) return
        binding.tvProfileName.text = user.name
        binding.tvRoleBadge.text = user.role.uppercase()
        applyRoleBadge(binding.tvRoleBadge, user.role)

        binding.cardBloodEmergency.isVisible = user.role.lowercase() != "teacher"

        // Update summary info under Personal Information
        binding.tvUserSubInfo.text = if (!user.studentId.isNullOrEmpty()) {
            "${user.studentId} • ${user.email}"
        } else {
            user.email
        }

        // Update Identity Metric Tiles
        val blood = user.bloodGroup.ifBlank { "Not set" }
        binding.tvMetricBlood.text = blood
        binding.tvMetricBloodSub.text = if (user.bloodGroup.isNotBlank()) "Tap for Blood Network" else "Add to help batchmates"

        val batchName = com.shuaib.classmate.models.Batch.formatName(user.batchId).ifBlank {
            user.batchId.ifBlank { "Batch N/A" }
        }
        val dept = user.department.ifBlank { "MBSTU" }.uppercase()
        binding.tvMetricBatch.text = "$dept · $batchName"
        binding.tvMetricBatchSub.text = "Academic Program"

        val district = user.homeDistrict.ifBlank { "Not set" }
        binding.tvMetricDistrict.text = district
        binding.tvMetricDistrictSub.text = if (user.address.isNotBlank()) user.address else if (user.homeDistrict.isNotBlank()) "District origin" else "Tap to set"

        val phone = user.phone.ifBlank { "Not set" }
        binding.tvMetricPhone.text = phone
        binding.tvMetricPhoneSub.text = if (user.phone.isNotBlank()) "Shared with batch directory" else "Tap to set" 

        val isAdmin = user.isAdmin()
        binding.adminSection.isVisible = isAdmin
        binding.cardSemesterManagement.isVisible = user.isBatchAdmin(
            com.shuaib.classmate.utils.AppContextManager.getManagedBatchId()
        )

        val isSuperAdmin = user.role == "superadmin"

        val canManageUsers = user.canManageUsers()
        binding.layoutUserManagement.isVisible = canManageUsers
        binding.dividerUserManagement.isVisible = canManageUsers

        binding.dividerFriendsToggle.isVisible = isSuperAdmin
        binding.layoutFriendsToggle.isVisible = isSuperAdmin

        updateSeeFriendsVisibility(user)
    }

    private fun loadProfilePicture(email: String, customUrl: String?, imageView: ImageView) {
        if (!isAdded) return
        val emailHash = MessageDigest.getInstance("MD5")
            .digest(email.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val gravatarUrl = "https://www.gravatar.com/avatar/$emailHash?s=200&d=identicon"
        val url = if (!customUrl.isNullOrEmpty()) customUrl else gravatarUrl

        Glide.with(this)
            .load(url)
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .placeholder(R.drawable.ic_default_avatar)
            .into(imageView)
    }

    private fun uploadProfilePicture(uri: Uri) {
        val uid = auth.currentUser?.uid ?: return
        binding.pbUpload.isVisible = true

        CloudinaryUploader.uploadImage(
            context = requireContext(),
            fileUri = uri,
            folder = "profile_pictures",
            onSuccess = { url, _ ->
                firestore.collection("users").document(uid)
                    .update("photoUrl", url)
                    .addOnSuccessListener {
                        if (_binding == null || !isAdded) return@addOnSuccessListener
                        binding.pbUpload.isVisible = false
                        fetchUserProfile()
                        Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    }
            },
            onFailure = { error ->
                if (_binding == null || !isAdded) return@uploadImage
                binding.pbUpload.isVisible = false
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun listenToFriendsConfig() {
        configListener?.remove()
        configListener = firestore.collection("config").document("friends")
            .addSnapshotListener { document, error ->
                if (_binding == null || !isAdded) return@addSnapshotListener
                if (error != null) {
                    android.util.Log.e("ProfileFragment", "Error listening to config/friends", error)
                    return@addSnapshotListener
                }

                isFriendsPublic = document?.getBoolean("isPublic") ?: false
                binding.switchFriendsPublic.isChecked = isFriendsPublic
                currentUser?.let { updateSeeFriendsVisibility(it) }
            }
    }

    private fun updateSeeFriendsVisibility(user: User) {
        // Redundant friends card removed from profile
    }

    override fun onResume() {
        super.onResume()
    }

    private fun setupShakeToTorchToggle() {
        val prefs = AppPreferences(requireContext())
        binding.switchShakeToTorch.isChecked = prefs.isShakeToTorchEnabled()

        binding.switchShakeToTorch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setShakeToTorchEnabled(isChecked)
            if (isChecked) {
                val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasCameraPermission) {
                    com.shuaib.classmate.services.ShakeToTorchService.start(requireContext())
                } else {
                    requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            } else {
                com.shuaib.classmate.services.ShakeToTorchService.stop(requireContext())
            }
        }
    }

    private fun setupSemesterManagement() {
        val chipMap = mapOf(
            "1st" to binding.chipSem1,
            "2nd" to binding.chipSem2,
            "3rd" to binding.chipSem3,
            "4th" to binding.chipSem4,
            "5th" to binding.chipSem5,
            "6th" to binding.chipSem6,
            "7th" to binding.chipSem7,
            "8th" to binding.chipSem8
        )

        fun updateChipSelection(activeSem: String) {
            val normalized = com.shuaib.classmate.utils.SemesterManager.normalizeSemester(activeSem)
            binding.tvActiveSemesterBadge.text = com.shuaib.classmate.utils.SemesterManager.formatDisplay(normalized).uppercase()
            val targetChip = chipMap[normalized] ?: binding.chipSem2
            targetChip.isChecked = true
            binding.btnPublishSemester.isVisible = false
        }

        updateChipSelection(com.shuaib.classmate.utils.SemesterManager.getActiveSemester())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                com.shuaib.classmate.utils.SemesterManager.activeSemesterFlow.collect { activeSem ->
                    updateChipSelection(activeSem)
                }
            }
        }

        chipMap.forEach { (sem, chip) ->
            chip.setOnClickListener {
                val user = currentUser
                if (user == null || !user.isBatchAdmin(com.shuaib.classmate.utils.AppContextManager.getManagedBatchId())) {
                    Toast.makeText(context, "You are not authorized to manage this batch.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val currentActive = com.shuaib.classmate.utils.SemesterManager.getActiveSemester()
                if (sem != currentActive) {
                    val display = com.shuaib.classmate.utils.SemesterManager.formatDisplay(sem)
                    binding.btnPublishSemester.text = "Publish $display"
                    binding.btnPublishSemester.isVisible = true
                } else {
                    binding.btnPublishSemester.isVisible = false
                }
            }
        }

        binding.btnPublishSemester.setOnClickListener {
            val user = currentUser
            if (user == null || !user.isBatchAdmin(com.shuaib.classmate.utils.AppContextManager.getManagedBatchId())) {
                Toast.makeText(context, "You are not authorized to manage this batch.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedChipId = binding.chipGroupSemesters.checkedChipId
            val selectedSem = chipMap.entries.firstOrNull { it.value.id == selectedChipId }?.key ?: "2nd"
            val displayTitle = com.shuaib.classmate.utils.SemesterManager.formatDisplay(selectedSem)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Publish Semester Switch?")
                .setMessage("Switching ${com.shuaib.classmate.models.Batch.formatName(com.shuaib.classmate.utils.AppContextManager.getManagedBatchId())} to $displayTitle will refresh that batch's academic resources. Notices remain batch-scoped.")
                .setPositiveButton("Publish & Shift All") { _, _ ->
                    binding.btnPublishSemester.isEnabled = false
                    com.shuaib.classmate.utils.SemesterManager.updateActiveSemester(
                        newSemester = selectedSem,
                        onSuccess = {
                            if (_binding != null) {
                                binding.btnPublishSemester.isEnabled = true
                                binding.btnPublishSemester.isVisible = false
                                Toast.makeText(context, "✅ $displayTitle is now active system-wide!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFailure = { err ->
                            if (_binding != null) {
                                binding.btnPublishSemester.isEnabled = true
                                Toast.makeText(context, "Failed to switch semester: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileListener?.remove()
        configListener?.remove()
        _binding = null
    }
}
