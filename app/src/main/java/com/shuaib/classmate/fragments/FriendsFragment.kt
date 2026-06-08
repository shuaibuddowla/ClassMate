// com/shuaib/classmate/fragments/FriendsFragment.kt
package com.shuaib.classmate.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.FriendsAdapter
import com.shuaib.classmate.databinding.FragmentFriendsBinding
import com.shuaib.classmate.models.User

class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: FirebaseFirestore
    private lateinit var friendsAdapter: FriendsAdapter

    private var usersListener: ListenerRegistration? = null
    private var configListener: ListenerRegistration? = null

    private var allUsersList = listOf<User>()
    private var isFriendsPublic = false
    private var currentUserRole = "student"
    private var accessChecked = false   // becomes true once both config + role are fetched

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupSearch()

        // First, fetch the current user's role, then kick off access checks
        fetchCurrentUserRole()
    }

    /**
     * Step 1 – Fetch the current user's role from Firestore.
     * After loading, begin listening to the friends config so the screen
     * reacts in real-time if an admin toggles the flag while it is open.
     */
    private fun fetchCurrentUserRole() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            showLockedState()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                currentUserRole = doc.getString("role") ?: "student"
                listenToFriendsConfig()      // Step 2
            }
            .addOnFailureListener {
                if (_binding == null || !isAdded) return@addOnFailureListener
                // Couldn't load role – fall back to config-only check
                listenToFriendsConfig()
            }
    }

    /**
     * Step 2 – Listen to config/friends in real-time.
     * This ensures the list appears / disappears immediately when
     * the admin toggles "Make Friends List Public".
     */
    private fun listenToFriendsConfig() {
        configListener?.remove()
        configListener = db.collection("config").document("friends")
            .addSnapshotListener { snapshot, error ->
                if (_binding == null || !isAdded) return@addSnapshotListener
                if (error != null) {
                    android.util.Log.e("FriendsFragment", "Config listen error", error)
                    // Default to locked on error
                    isFriendsPublic = false
                } else {
                    isFriendsPublic = snapshot?.getBoolean("isPublic") ?: false
                }

                // (Re-)evaluate access every time the flag changes
                applyAccessDecision()
            }
    }

    /**
     * Core access gate – called each time either the role or the flag changes.
     * Admins and superadmins always have access regardless of the toggle.
     */
    private fun applyAccessDecision() {
        val hasAccess = isFriendsPublic ||
                currentUserRole == "superadmin" ||
                currentUserRole == "admin"

        if (hasAccess) {
            showNormalState()
            // Start (or restart) listening for users only when access is granted
            if (usersListener == null) listenForUsers()
        } else {
            showLockedState()
            // Stop the real-time users listener to avoid unnecessary reads
            usersListener?.remove()
            usersListener = null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // UI state helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun showNormalState() {
        if (_binding == null) return
        binding.tilSearch.isVisible = true
        binding.rvFriends.isVisible = true
        binding.emptySearchState.isVisible = false
        binding.lockedState.isVisible = false
    }

    private fun showLockedState() {
        if (_binding == null) return
        binding.tilSearch.isVisible = false
        binding.rvFriends.isVisible = false
        binding.emptySearchState.isVisible = false
        binding.lockedState.isVisible = true
    }

    // ──────────────────────────────────────────────────────────────────────
    // RecyclerView & Search
    // ──────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        friendsAdapter = FriendsAdapter(
            friends = emptyList(),
            onFriendClick = { user ->
                val bundle = Bundle().apply { putString("userId", user.uid) }
                findNavController().navigate(R.id.action_friends_to_detail, bundle)
            },
            onCallClick = { user ->
                if (user.phone.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${user.phone}")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(context, "Phone number not available", Toast.LENGTH_SHORT).show()
                }
            },
            onWhatsappClick = { user ->
                if (user.phone.isNotEmpty()) {
                    val cleanPhone = user.phone.filter { it.isDigit() || it == '+' }
                    val url = "https://api.whatsapp.com/send?phone=$cleanPhone"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(url)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Phone number not available", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvFriends.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = friendsAdapter
        }
    }

    private fun listenForUsers() {
        usersListener?.remove()
        usersListener = db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null) {
                    // Permission denied means the Firestore rule blocked the read.
                    // Surface a clean message instead of a raw exception.
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Toast.makeText(
                            context,
                            "Friends list is not available right now.",
                            Toast.LENGTH_SHORT
                        ).show()
                        showLockedState()
                    } else {
                        Toast.makeText(context, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    allUsersList = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(User::class.java)?.copy(uid = doc.id)
                    }.sortedBy { it.name }

                    val query = binding.etSearch.text.toString()
                    if (query.isEmpty()) {
                        updateListWithEmptyCheck(allUsersList)
                    } else {
                        filterList(query)
                    }
                }
            }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterList(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            updateListWithEmptyCheck(allUsersList)
            return
        }

        val filtered = allUsersList.filter { user ->
            user.name.contains(trimmedQuery, ignoreCase = true) ||
            user.studentId.contains(trimmedQuery, ignoreCase = true) ||
            user.bloodGroup.contains(trimmedQuery, ignoreCase = true) ||
            user.homeDistrict.contains(trimmedQuery, ignoreCase = true) ||
            user.address.contains(trimmedQuery, ignoreCase = true) ||
            user.phone.contains(trimmedQuery)
        }
        updateListWithEmptyCheck(filtered)
    }

    private fun updateListWithEmptyCheck(list: List<User>) {
        friendsAdapter.updateList(list)
        binding.emptySearchState.isVisible = list.isEmpty()
        binding.rvFriends.isVisible = list.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        usersListener?.remove()
        configListener?.remove()
        _binding = null
    }
}
