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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.onesignal.OneSignal
import com.shuaib.classmate.activities.LoginActivity
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.AdminPanelActivity
import com.shuaib.classmate.activities.AiSettingsActivity
import com.shuaib.classmate.activities.UserManagementActivity
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.activities.PostNoticeActivity
import com.shuaib.classmate.adapters.PdfAdapter

import com.shuaib.classmate.adapters.SubjectAdapter
import com.shuaib.classmate.databinding.DialogEditProfileBinding
import com.shuaib.classmate.databinding.FragmentProfileBinding
import com.shuaib.classmate.models.PdfFile

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
import com.shuaib.classmate.utils.AppUpdateManager
import com.shuaib.classmate.utils.AppUpdateInfo
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
    private lateinit var subjectAdapter: SubjectAdapter
    private lateinit var pdfAdapter: PdfAdapter
    private var favoriteSubjects = emptyList<Subject>()
    private var favoritePdfIdsSet = emptySet<String>()
    private var pdfCounts = emptyMap<String, Int>()
    private var isSubjectsExpanded = false
    private var isPdfsExpanded = false
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
        setupSavedResources()
        listenToFriendsConfig()
        fetchUserProfile()

        GlowHelper.pulseGlow(binding.profileBorder, ThemeColors.primary(requireContext()))

        binding.fabEditPhoto.setOnClickListener {
            showPhotoOptions()
        }

        binding.cardPersonalInfo.applyClickAnimation {
            currentUser?.let { showEditProfileDialog(it) }
        }

        binding.cardSeeFriends.applyClickAnimation {
            (activity as? MainActivity)?.openChildDestination(R.id.nav_profile, R.id.nav_friends)
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
            checkUpdatesManually()
        }
    }

    private fun checkUpdatesManually() {
        binding.pbCheckUpdates.visibility = View.VISIBLE
        binding.ivCheckUpdatesChevron.visibility = View.GONE
        binding.layoutCheckUpdates.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updateInfo = AppUpdateManager.checkLatestRelease()
                val isAvailable = AppUpdateManager.isUpdateAvailable(updateInfo.latestVersionName)
                
                binding.pbCheckUpdates.visibility = View.GONE
                binding.ivCheckUpdatesChevron.visibility = View.VISIBLE
                binding.layoutCheckUpdates.isClickable = true

                if (isAvailable) {
                    AppUpdateManager.showUpdateDialog(requireContext(), updateInfo, viewLifecycleOwner.lifecycleScope)
                } else {
                    Toast.makeText(requireContext(), "ClassMate is up to date!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.pbCheckUpdates.visibility = View.GONE
                binding.ivCheckUpdatesChevron.visibility = View.VISIBLE
                binding.layoutCheckUpdates.isClickable = true
                Toast.makeText(requireContext(), "Failed to check for updates: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
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

    private fun showEditProfileDialog(user: User) {
        val dialogBinding = DialogEditProfileBinding.inflate(layoutInflater)

        dialogBinding.etEditName.setText(user.name)
        dialogBinding.etEditStudentId.setText(user.studentId)
        dialogBinding.etEditPhone.setText(user.phone)
        dialogBinding.etEditBlood.setText(user.bloodGroup)
        dialogBinding.etEditDistrict.setText(user.homeDistrict)
        dialogBinding.etEditAddress.setText(user.address)

        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ClassMate_Dialog)
            .setView(dialogBinding.root)
            .setPositiveButton("Update") { _, _ ->
                val updatedData = mapOf(
                    "name" to dialogBinding.etEditName.text.toString().trim(),
                    "studentId" to dialogBinding.etEditStudentId.text.toString().trim(),
                    "phone" to dialogBinding.etEditPhone.text.toString().trim(),
                    "bloodGroup" to dialogBinding.etEditBlood.text.toString().trim(),
                    "homeDistrict" to dialogBinding.etEditDistrict.text.toString().trim(),
                    "address" to dialogBinding.etEditAddress.text.toString().trim()
                )
                updateUserInFirestore(updatedData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUserInFirestore(data: Map<String, Any>) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).update(data)
            .addOnSuccessListener {
                Toast.makeText(context, "Profile updated!", Toast.LENGTH_SHORT).show()
                fetchUserProfile()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.setNotificationsEnabled(isChecked)
            if (isChecked) OneSignal.User.pushSubscription.optIn()
            else OneSignal.User.pushSubscription.optOut()
        }
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

    private fun setupSavedResources() {
        subjectAdapter = SubjectAdapter(emptyList()) { subject ->
            (activity as? MainActivity)?.openChildDestination(
                R.id.nav_pdf,
                R.id.fragment_subject_pdf_list,
                Bundle().apply {
                    putString("subjectName", subject.name)
                }
            )
        }
        binding.rvSavedResources.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = subjectAdapter
        }

        pdfAdapter = PdfAdapter(emptyList(), false, emptySet())
        pdfAdapter.onItemClick = { pdf -> LibraryUrlOpener.open(requireContext(), pdf) }
        pdfAdapter.onFavoriteClick = { pdf -> togglePdfFavorite(pdf) }
        binding.rvSavedFiles.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pdfAdapter
        }

        binding.rowSavedSubjects.setOnClickListener {
            isSubjectsExpanded = !isSubjectsExpanded
            binding.containerSavedSubjects.visibility = if (isSubjectsExpanded) View.VISIBLE else View.GONE
            val rotation = if (isSubjectsExpanded) 90f else 0f
            binding.ivSavedSubjectsChevron.animate().rotation(rotation).setDuration(150).start()
        }

        binding.rowSavedPdfs.setOnClickListener {
            isPdfsExpanded = !isPdfsExpanded
            binding.containerSavedPdfs.visibility = if (isPdfsExpanded) View.VISIBLE else View.GONE
            val rotation = if (isPdfsExpanded) 90f else 0f
            binding.ivSavedPdfsChevron.animate().rotation(rotation).setDuration(150).start()
        }
    }

    private fun fetchSavedResources(favoriteNames: List<String>, favoritePdfIds: List<String>) {
        favoritePdfIdsSet = favoritePdfIds.toSet()

        val hasSubjects = favoriteNames.isNotEmpty()
        val hasPdfs = favoritePdfIds.isNotEmpty()

        if (hasSubjects || hasPdfs) {
            binding.savedResourcesTagSection.visibility = View.VISIBLE

            // Subjects Row Visibility
            binding.rowSavedSubjects.visibility = if (hasSubjects) View.VISIBLE else View.GONE
            binding.containerSavedSubjects.visibility = if (hasSubjects && isSubjectsExpanded) View.VISIBLE else View.GONE
            binding.tvSavedSubjectsTitle.text = "Saved Subjects (${favoriteNames.size})"
            binding.ivSavedSubjectsChevron.rotation = if (isSubjectsExpanded) 90f else 0f

            // PDFs Row Visibility
            binding.rowSavedPdfs.visibility = if (hasPdfs) View.VISIBLE else View.GONE
            binding.containerSavedPdfs.visibility = if (hasPdfs && isPdfsExpanded) View.VISIBLE else View.GONE
            binding.tvSavedPdfsTitle.text = "Saved PDFs (${favoritePdfIds.size})"
            binding.ivSavedPdfsChevron.rotation = if (isPdfsExpanded) 90f else 0f

            // Divider visibility
            binding.dividerSavedResources.visibility = if (hasSubjects && hasPdfs) View.VISIBLE else View.GONE
        } else {
            binding.savedResourcesTagSection.visibility = View.GONE
        }

        // Fetch Subjects
        if (hasSubjects) {
            favoriteSubjects = SubjectList.subjects.filter { it.name in favoriteNames }
            if (favoriteSubjects.isNotEmpty() && !hasPdfs) {
                updateSubjectCountsOnly()
            }
        }

        // Fetch Files
        if (hasPdfs) {
            fetchFavoritePdfs(favoritePdfIds)
        }
    }

    private fun updateSubjectCountsOnly() {
        firestore.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                val files = snapshot.documents.map { doc -> doc.getString("subject") ?: "" }
                pdfCounts = files.groupBy { it }.mapValues { it.value.size }
                subjectAdapter.updateList(favoriteSubjects, pdfCounts)
            }
    }

    private fun fetchFavoritePdfs(favoritePdfIds: List<String>) {
        firestore.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                val allFiles = snapshot.documents.map { doc -> doc.toPdfFile() }
                pdfCounts = allFiles.groupBy { it.subject }.mapValues { it.value.size }

                val favoritePdfs = allFiles.filter { it.id in favoritePdfIds }

                if (favoriteSubjects.isNotEmpty()) {
                    subjectAdapter.updateList(favoriteSubjects, pdfCounts)
                }

                if (favoritePdfs.isNotEmpty()) {
                    pdfAdapter.updateList(favoritePdfs, favoritePdfIdsSet)
                    binding.tvSavedPdfsTitle.text = "Saved PDFs (${favoritePdfs.size})"
                }
            }
    }

    private fun togglePdfFavorite(pdf: PdfFile) {
        val uid = auth.currentUser?.uid ?: return
        val isNowFavorite = !favoritePdfIdsSet.contains(pdf.id)
        val wasFavoritePdfIds = favoritePdfIdsSet

        if (isNowFavorite) {
            favoritePdfIdsSet = favoritePdfIdsSet + pdf.id
            firestore.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayUnion(pdf.id))
                .addOnFailureListener {
                    favoritePdfIdsSet = wasFavoritePdfIds
                    Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            favoritePdfIdsSet = favoritePdfIdsSet - pdf.id
            firestore.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayRemove(pdf.id))
                .addOnFailureListener {
                    favoritePdfIdsSet = wasFavoritePdfIds
                    Toast.makeText(context, "Remove failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }

        fetchUserFavorites()
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

        fetchUserFavorites()

        // Update summary info under Personal Information
        binding.tvUserSubInfo.text = if (!user.studentId.isNullOrEmpty()) {
            "${user.studentId} - ${user.email}"
        } else {
            user.email
        }

        val isAdmin = user.isAdmin()
        binding.adminSection.isVisible = isAdmin

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
        binding.fabEditPhoto.isEnabled = false

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
                        binding.fabEditPhoto.isEnabled = true
                        fetchUserProfile()
                        Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    }
            },
            onFailure = { error ->
                if (_binding == null || !isAdded) return@uploadImage
                binding.pbUpload.isVisible = false
                binding.fabEditPhoto.isEnabled = true
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun fetchUserFavorites() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                val favoriteNames = doc.get("favoriteSubjects") as? List<String> ?: emptyList()
                val favoritePdfIds = doc.get("favoritePdfIds") as? List<String> ?: emptyList()
                fetchSavedResources(favoriteNames, favoritePdfIds)
            }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toPdfFile(): PdfFile {
        return PdfFile(
            id = id,
            title = getString("title") ?: "No Title",
            subject = getString("subject") ?: "",
            description = getString("description") ?: "",
            uploadedBy = getString("uploadedByName") ?: getString("uploadedBy") ?: "",
            telegramUrl = getString("telegramUrl") ?: "",
            driveUrl = getString("driveUrl") ?: "",
            fileId = getString("fileId") ?: "",
            timestamp = getTimestamp("timestamp") ?: getTimestamp("createdAt"),
            courseCode = getString("courseCode") ?: "",
            courseType = getString("courseType") ?: "",
            fileType = getString("fileType") ?: "other",
            mimeType = getString("mimeType") ?: "application/octet-stream",
            sizeBytes = getLong("sizeBytes") ?: 0L,
            provider = getString("provider") ?: "",
            downloadUrl = getString("downloadUrl") ?: "",
            githubAssetId = getLong("githubAssetId") ?: 0L,
            githubAssetName = getString("githubAssetName") ?: getString("title") ?: "",
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt"),
            downloadCount = getLong("downloadCount") ?: 0L,
            isDeleted = getBoolean("isDeleted") ?: false
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
        val showSeeFriends = user.role == "superadmin" || isFriendsPublic
        binding.cardSeeFriends.isVisible = showSeeFriends
        binding.dividerSeeFriends.isVisible = showSeeFriends
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

    override fun onDestroyView() {
        super.onDestroyView()
        profileListener?.remove()
        configListener?.remove()
        _binding = null
    }
}
