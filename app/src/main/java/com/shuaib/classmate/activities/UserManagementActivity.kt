/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/activities/UserManagementActivity.kt
 */
package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.UserAdapter
import com.shuaib.classmate.databinding.ActivityUserManagementBinding
import com.shuaib.classmate.models.User

class UserManagementActivity : AppCompatActivity() {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var userAdapter: UserAdapter
    private lateinit var binding: ActivityUserManagementBinding
    private val userList = mutableListOf<User>()
    private var currentUser: User? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        firestore = FirebaseFirestore.getInstance()
        setupRecyclerView()
        fetchCurrentUser()
    }

    private fun fetchCurrentUser() {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        firestore.collection("users").document(currentUid).get()
            .addOnSuccessListener { doc ->
                binding.progressBar.visibility = View.GONE
                val user = doc.toObject(User::class.java)?.copy(uid = doc.id)
                currentUser = user
                if (user != null && user.canManageUsers()) {
                    fetchAllUsers()
                } else {
                    Toast.makeText(this, "Access restricted to Super Admins only.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e("UserManagement", "Error fetching current user info", e)
                Toast.makeText(this, "Error verifying authorization.", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun setupRecyclerView() {
        val rootDecorView = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        userAdapter = UserAdapter(userList, rootDecorView) { user ->
            startActivity(
                Intent(this, UserDetailActivity::class.java)
                    .putExtra(UserDetailActivity.EXTRA_USER_ID, user.uid)
            )
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        userAdapter.onUserLongClick = { targetUser ->
            showUserManagementOptions(targetUser)
        }

        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(this@UserManagementActivity)
            adapter = userAdapter
        }
    }

    private fun showUserManagementOptions(targetUser: User) {
        val currUser = currentUser
        if (currUser == null) {
            Toast.makeText(this, "Still loading your admin profile. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        if (currUser.uid == targetUser.uid) {
            Toast.makeText(this, "You cannot modify or delete your own account.", Toast.LENGTH_SHORT).show()
            return
        }

        if (targetUser.role == "superadmin" && currUser.role != "superadmin") {
            Toast.makeText(this, "Only Super Admins can manage other Super Admins.", Toast.LENGTH_SHORT).show()
            return
        }

        val optionsList = mutableListOf<String>()
        val isSuperadmin = currUser.role == "superadmin"

        if (isSuperadmin || currUser.canManageAdmins()) {
            optionsList.add("Change Role")
        }

        val canDelete = isSuperadmin || (currUser.canManageUsers() && targetUser.role != "admin" && targetUser.role != "superadmin")
        if (canDelete) {
            optionsList.add("Delete User")
        }

        if (optionsList.isEmpty()) {
            Toast.makeText(this, "You do not have permission to manage this user.", Toast.LENGTH_SHORT).show()
            return
        }

        val options = optionsList.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.Theme_ClassMate_Dialog)
            .setTitle("Manage ${targetUser.fullName.ifBlank { targetUser.name }}")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Change Role" -> showChangeRoleDialog(targetUser)
                    "Delete User" -> showDeleteUserConfirmation(targetUser)
                }
            }
            .show()
    }

    private fun showChangeRoleDialog(targetUser: User) {
        val currUser = currentUser ?: return
        val isSuperadmin = currUser.role == "superadmin"

        if ((targetUser.role == "admin" || targetUser.role == "superadmin") && !isSuperadmin) {
            Toast.makeText(this, "Only Super Admins can change roles for Admins and Super Admins.", Toast.LENGTH_SHORT).show()
            return
        }

        val roles: Array<CharSequence>
        val roleValues: Array<String>

        if (isSuperadmin) {
            roles = arrayOf("Student", "Admin", "Super Admin")
            roleValues = arrayOf("student", "admin", "superadmin")
        } else {
            roles = arrayOf("Student", "Admin")
            roleValues = arrayOf("student", "admin")
        }

        val currentRoleIndex = roleValues.indexOf(targetUser.role.lowercase()).let { if (it == -1) 0 else it }

        MaterialAlertDialogBuilder(this, R.style.Theme_ClassMate_Dialog)
            .setTitle("Change Role for ${targetUser.fullName.ifBlank { targetUser.name }}")
            .setSingleChoiceItems(roles, currentRoleIndex) { dialog, which ->
                dialog.dismiss()
                val newRole = roleValues[which]
                updateUserRole(targetUser, newRole)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUserRole(targetUser: User, newRole: String) {
        binding.progressBar.visibility = View.VISIBLE

        val newPermissions = when (newRole) {
            "superadmin" -> User.DEFAULT_PERMISSIONS.mapValues { true }.toMutableMap()
            "admin" -> User.DEFAULT_PERMISSIONS.mapValues { it.key != "canManageUsers" && it.key != "canManageAdmins" }.toMutableMap()
            else -> User.DEFAULT_PERMISSIONS.mapValues { false }.toMutableMap()
        }

        val updates = mapOf(
            "role" to newRole,
            "permissions" to newPermissions
        )

        firestore.collection("users").document(targetUser.uid)
            .update(updates)
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Role updated to ${newRole.uppercase()} and permissions updated", Toast.LENGTH_SHORT).show()
                val idx = userList.indexOfFirst { it.uid == targetUser.uid }
                if (idx != -1) {
                    userList[idx] = userList[idx].copy(role = newRole, permissions = newPermissions)
                    userAdapter.notifyItemChanged(idx)
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to update role: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showDeleteUserConfirmation(targetUser: User) {
        MaterialAlertDialogBuilder(this, R.style.Theme_ClassMate_Dialog)
            .setTitle("Delete User")
            .setMessage("Are you sure you want to delete ${targetUser.fullName.ifBlank { targetUser.name }}? This will permanently remove them from the database and friends list.")
            .setPositiveButton("Delete") { _, _ ->
                deleteUser(targetUser)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteUser(targetUser: User) {
        binding.progressBar.visibility = View.VISIBLE

        firestore.collection("users").document(targetUser.uid)
            .delete()
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "User deleted successfully", Toast.LENGTH_SHORT).show()
                val idx = userList.indexOfFirst { it.uid == targetUser.uid }
                if (idx != -1) {
                    userList.removeAt(idx)
                    userAdapter.notifyItemRemoved(idx)
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to delete user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun fetchAllUsers() {
        binding.progressBar.visibility = View.VISIBLE

        // Fetching entire collection. If only 1 shows up, check Firestore Security Rules.
        firestore.collection("users")
            .get()
            .addOnSuccessListener { documents ->
                binding.progressBar.visibility = View.GONE
                val tempUserList = mutableListOf<User>()

                Log.d("UserManagement", "Total documents fetched: ${documents.size()}")

                for (document in documents) {
                    try {
                        val user = document.toObject(User::class.java)?.copy(uid = document.id)
                        if (user != null) {
                            tempUserList.add(user)
                        }
                    } catch (e: Exception) {
                        Log.e("UserManagement", "Error parsing user ${document.id}: ${e.message}")
                        // Fallback: manually map critical fields if toObject fails
                        try {
                            val manualUser = User(
                                uid = document.id,
                                fullName = document.getString("fullName") ?: document.getString("name") ?: "Unknown",
                                email = document.getString("email") ?: "",
                                role = document.getString("role") ?: "student",
                                studentId = document.getString("studentId") ?: ""
                            )
                            tempUserList.add(manualUser)
                        } catch (e2: Exception) {
                            Log.e("UserManagement", "Manual fallback failed for ${document.id}")
                        }
                    }
                }

                // Sort in memory: Newest first, then alphabetical
                tempUserList.sortWith { a, b ->
                    val timeA = a.createdAt
                    val timeB = b.createdAt

                    val res = when {
                        timeA != null && timeB != null -> timeB.compareTo(timeA)
                        timeA != null -> -1
                        timeB != null -> 1
                        else -> 0
                    }

                    if (res != 0) res
                    else a.fullName.lowercase().compareTo(b.fullName.lowercase())
                }

                userList.clear()
                userList.addAll(tempUserList)
                userAdapter.updateList(userList)

                if (userList.isEmpty()) {
                    Toast.makeText(this, "No users found in database", Toast.LENGTH_LONG).show()
                } else if (userList.size == 1) {
                    Log.w("UserManagement", "Only 1 user found. Check Firestore Rules if more are expected.")
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Fetch failed: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("UserManagement", "Firestore fetch error", e)
            }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
