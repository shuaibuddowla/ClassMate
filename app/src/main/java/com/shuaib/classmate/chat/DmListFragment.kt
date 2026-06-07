package com.shuaib.classmate.chat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.FragmentDmListBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DmListFragment : Fragment() {
    private val viewModel: ChatViewModel by activityViewModels()
    private val repository = ChatRepository
    private var _binding: FragmentDmListBinding? = null
    private val binding get() = _binding!!

    private var adapter: DmUsersAdapter? = null
    private var onlineUserIds: Set<String> = emptySet()
    private var allUsers: List<com.shuaib.classmate.chat.model.ChatUser> = emptyList()
    private var pendingOtherUserName: String? = null
    private var pendingOtherUserId: String? = null
    private var userLoadFinished = false
    private var navigatedToPendingDm = false
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDmListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        
        adapter = DmUsersAdapter(
            onlineIdsProvider = { onlineUserIds },
            onUserClick = { user ->
                openDm(user)
            }
        )

        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DmListFragment.adapter
        }

        binding.etSearchUsers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                submitFilteredUsers(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        collectUsers()
        collectDmCreated()
        collectLoadState()
        repository.getUsers()
    }

    private fun collectUsers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.users, repository.onlineUsers) { users, onlineIds ->
                    onlineUserIds = onlineIds.toSet()
                    val uid = currentUserId
                    if (uid.isBlank()) {
                        emptyList()
                    } else {
                        users
                            .filterNot { it.id == uid }
                            .filter { it.id.isNotBlank() }
                            .map { user -> user.copy(isOnline = onlineIds.contains(user.id)) }
                            .sortedWith(compareByDescending<com.shuaib.classmate.chat.model.ChatUser> { it.isOnline }.thenBy { it.name.lowercase() })
                    }
                }.collectLatest { users ->
                    allUsers = users
                    submitFilteredUsers(binding.etSearchUsers.text?.toString().orEmpty())
                }
            }
        }
    }

    private fun submitFilteredUsers(query: String) {
        val filtered = if (query.isBlank()) {
            allUsers
        } else {
            allUsers.filter { it.name.contains(query, ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
            showEmptyState(
                when {
                    query.isNotBlank() -> "No contacts found matching \"$query\""
                    !userLoadFinished -> "Loading classmates..."
                    else -> "No classmates available"
                }
            )
        } else {
            hideEmptyState()
            adapter?.submitList(filtered)
        }
    }

    private fun showEmptyState(message: String) {
        binding.tvEmptyUsersMessage.text = message
        binding.tvEmptyUsers.isVisible = true
        binding.rvUsers.isVisible = false
        adapter?.submitList(emptyList())
    }

    private fun hideEmptyState() {
        binding.tvEmptyUsers.isVisible = false
        binding.rvUsers.isVisible = true
    }

    private fun collectDmCreated() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dmCreated.collect { room ->
                    if (navigatedToPendingDm) return@collect
                    val otherUserName = pendingOtherUserName ?: return@collect
                    val otherUserId = pendingOtherUserId
                    navigatedToPendingDm = true
                    pendingOtherUserName = null
                    pendingOtherUserId = null
                    safeNavigateToDm(room.id, otherUserName, otherUserId)
                }
            }
        }
    }

    private fun collectLoadState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.usersLoaded.collect {
                        userLoadFinished = true
                        submitFilteredUsers(binding.etSearchUsers.text?.toString().orEmpty())
                    }
                }
                launch {
                    viewModel.connectionError.collect {
                        userLoadFinished = true
                        submitFilteredUsers(binding.etSearchUsers.text?.toString().orEmpty())
                    }
                }
            }
        }
    }

    private fun openDm(user: com.shuaib.classmate.chat.model.ChatUser) {
        val uid = currentUserId
        if (uid.isBlank()) {
            Toast.makeText(requireContext(), "Please sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        if (user.id.isBlank() || user.id == uid) {
            Toast.makeText(requireContext(), "This contact cannot be opened.", Toast.LENGTH_SHORT).show()
            return
        }
        val safeName = user.name.ifBlank { "Chat" }
        val roomId = listOf(uid, user.id).sorted().joinToString(separator = "_", prefix = "dm_")
        
        pendingOtherUserName = safeName
        pendingOtherUserId = user.id
        navigatedToPendingDm = true
        viewModel.startDm(user.id, safeName)
        // Optimization: navigate immediately with the computed roomId
        safeNavigateToDm(roomId, safeName, user.id)
    }

    private fun safeNavigateToDm(roomId: String, otherUserName: String, otherUserId: String?) {
        runCatching {
            findNavController().navigate(
                R.id.fragment_dm_chat,
                bundleOf(
                    "roomId" to roomId,
                    "otherUserName" to otherUserName,
                    "otherUserId" to otherUserId
                )
            )
        }.onFailure {
            Toast.makeText(requireContext(), "Could not open chat. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        adapter = null
        pendingOtherUserName = null
        pendingOtherUserId = null
        navigatedToPendingDm = false
    }
}
