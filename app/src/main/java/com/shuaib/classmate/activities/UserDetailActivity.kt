package com.shuaib.classmate.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityUserDetailBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.ThemeColors

class UserDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserDetailBinding
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var currentUser = User()
    private var targetUser = User()

    private val permissionLabels = linkedMapOf(
        "canCreatePolls" to "Create/manage polls",
        "canEditTimetable" to "Edit class schedule",
        "canPostNotices" to "Post official notices",
        "canSendClassCancel" to "Issue cancellations",
        "canUploadPDF" to "Upload study materials",
        "canUploadLibrary" to "Manage PDF library",
        "canUploadResult" to "Publish exam results",
        "canUploadSeatPlan" to "Manage seat plans",
        "canManageUsers" to "Manage users",
        "canManageAdmins" to "Manage administrators"
    )

    private val switchMap: Map<String, SwitchMaterial>
        get() = mapOf(
            "canCreatePolls" to binding.switchCreatePolls,
            "canEditTimetable" to binding.switchTimetable,
            "canPostNotices" to binding.switchNotices,
            "canSendClassCancel" to binding.switchCancel,
            "canUploadPDF" to binding.switchPDF,
            "canUploadLibrary" to binding.switchLibrary,
            "canUploadResult" to binding.switchResult,
            "canUploadSeatPlan" to binding.switchSeatPlan,
            "canManageUsers" to binding.switchManageUsers,
            "canManageAdmins" to binding.switchManageAdmins
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        binding.btnSave.setOnClickListener { saveChanges() }

        loadUsers()
    }

    private fun loadUsers() {
        val currentUid = auth.currentUser?.uid
        val targetUid = intent.getStringExtra(EXTRA_USER_ID)
        if (currentUid.isNullOrBlank() || targetUid.isNullOrBlank()) {
            Toast.makeText(this, "User profile unavailable.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        firestore.collection("users").document(currentUid).get()
            .addOnSuccessListener { currentDoc ->
                currentUser = currentDoc.toObject(User::class.java)?.copy(uid = currentDoc.id) ?: User(uid = currentUid)

                val user = currentUser
                if (user.role != "superadmin" && user.permissions["canManageUsers"] != true) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Access restricted to Super Admins only.", Toast.LENGTH_LONG).show()
                    finish()
                    return@addOnSuccessListener
                }

                firestore.collection("users").document(targetUid).get()
                    .addOnSuccessListener { targetDoc ->
                        binding.progressBar.visibility = View.GONE
                        targetUser = targetDoc.toObject(User::class.java)?.copy(uid = targetDoc.id) ?: User(uid = targetUid)
                        bindUser()
                    }
                    .addOnFailureListener { e ->
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this, "Could not load user: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Could not verify admin: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun bindUser() {
        val displayName = targetUser.fullName.ifBlank { targetUser.name }.ifBlank { "Unnamed user" }
        binding.tvName.text = displayName
        binding.tvEmail.text = targetUser.email.ifBlank { "No email" }
        binding.tvAvatarLetter.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
        bindAvatar(targetUser.photoUrl)
        bindRoleBadge(binding.tvRoleBadge, targetUser.role)

        binding.tvUid.text = field("UID", targetUser.uid)
        binding.tvStudentId.text = field("Student ID", targetUser.studentId)
        binding.tvDepartment.text = field("Department", targetUser.department)
        binding.tvPhone.text = field("Phone", targetUser.phone)
        binding.tvBloodGroup.text = field("Blood Group", targetUser.bloodGroup)
        binding.tvHomeDistrict.text = field("Home District", targetUser.homeDistrict)
        binding.tvAddress.text = field("Address", targetUser.address)
        binding.tvAuthProvider.text = field("Auth Provider", targetUser.authProvider)

        when (targetUser.role) {
            "superadmin" -> binding.radioSuperadmin.isChecked = true
            "admin" -> binding.radioAdmin.isChecked = true
            else -> binding.radioStudent.isChecked = true
        }

        val isSuperadmin = currentUser.role == "superadmin"
        binding.tvRoleLabel.visibility = if (isSuperadmin) View.VISIBLE else View.GONE
        binding.radioGroupRole.visibility = if (isSuperadmin) View.VISIBLE else View.GONE

        bindPermissionControls(isSuperadmin)
    }

    private fun bindPermissionControls(isSuperadmin: Boolean) {
        var visibleCount = 0
        switchMap.forEach { (key, view) ->
            val canControl = isSuperadmin || currentUser.permissions[key] == true
            view.visibility = if (canControl) View.VISIBLE else View.GONE
            view.text = permissionLabels[key].orEmpty()
            view.isChecked = targetUser.permissions[key] == true
            if (canControl) visibleCount++
        }
        binding.tvPermissionHint.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = isSuperadmin || visibleCount > 0
    }

    private fun saveChanges() {
        val isSuperadmin = currentUser.role == "superadmin"
        val allowedKeys = permissionLabels.keys.filter { isSuperadmin || currentUser.permissions[it] == true }
        val newPermissions = targetUser.permissions.toMutableMap()
        allowedKeys.forEach { key ->
            newPermissions[key] = switchMap.getValue(key).isChecked
        }

        val updates = mutableMapOf<String, Any>("permissions" to newPermissions)
        if (isSuperadmin) {
            val role = selectedRole()
            updates["role"] = role
            // Align permissions automatically with the role to prevent security issues
            val alignedPermissions = when (role) {
                "superadmin" -> User.DEFAULT_PERMISSIONS.mapValues { true }
                "admin" -> User.DEFAULT_PERMISSIONS.mapValues { it.key != "canManageUsers" && it.key != "canManageAdmins" }
                else -> User.DEFAULT_PERMISSIONS.mapValues { false }
            }
            updates["permissions"] = alignedPermissions
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false
        firestore.collection("users").document(targetUser.uid)
            .update(updates)
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                binding.btnSave.isEnabled = true
                Toast.makeText(this, "User access updated", Toast.LENGTH_SHORT).show()
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.btnSave.isEnabled = true
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun selectedRole(): String {
        return when (binding.radioGroupRole.checkedRadioButtonId) {
            R.id.radioSuperadmin -> "superadmin"
            R.id.radioAdmin -> "admin"
            else -> "student"
        }
    }

    private fun bindAvatar(photoUrl: String) {
        if (photoUrl.isBlank()) {
            binding.ivAvatar.visibility = View.GONE
            return
        }
        binding.ivAvatar.visibility = View.VISIBLE
        Glide.with(this)
            .load(photoUrl)
            .circleCrop()
            .placeholder(R.drawable.ic_default_avatar)
            .error(R.drawable.ic_default_avatar)
            .into(binding.ivAvatar)
    }

    private fun bindRoleBadge(textView: TextView, role: String) {
        textView.text = role.ifBlank { "student" }.uppercase()
        textView.setTextColor(ThemeColors.textInverse(this))
        textView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(
                Color.parseColor(
                    when (role.lowercase()) {
                        "superadmin" -> "#FF4D6D"
                        "admin" -> "#FFB347"
                        else -> "#34D399"
                    }
                )
            )
        }
    }

    private fun field(label: String, value: String): String {
        return "$label: ${value.ifBlank { "Not set" }}"
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
    }
}
