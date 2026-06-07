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
    private var allUsersList = listOf<User>()

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
        listenForUsers()
        setupSearch()
    }

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
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Toast.makeText(context, "Access restricted to superadmin only", Toast.LENGTH_LONG).show()
                        try {
                            findNavController().popBackStack()
                        } catch (_: Exception) {}
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
        _binding = null
    }
}
