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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.chat.model.ChatRoom
import com.shuaib.classmate.chat.model.ChatUser
import com.shuaib.classmate.databinding.FragmentChatBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {
    private val viewModel: ChatViewModel by activityViewModels()
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private var adapter: ChatListAdapter? = null
    private var refreshTimeoutJob: Job? = null
    private var allItems: List<ChatListItem> = emptyList()
    private var currentQuery: String = ""
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChatListAdapter { item -> openChat(item) }
        binding.rvChats.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ChatFragment.adapter
            setHasFixedSize(true)
        }
        ItemTouchHelper(archiveSwipeCallback()).attachToRecyclerView(binding.rvChats)

        binding.btnCompose.setOnClickListener { openNewMessage() }
        binding.fabNewMessage.setOnClickListener { openNewMessage() }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnCloseChatSearch.setOnClickListener { closeSearch() }
        
        binding.etSearchChats.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                submitFilteredChats()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.swipeRefresh.setOnRefreshListener {
            refreshChat()
        }

        binding.chipAll.setOnClickListener { /* Filter logic if needed */ }
        binding.chipGroups.setOnClickListener { /* Filter logic if needed */ }

        collectChats()
        collectConnectionState()
        
        // Initial requests on start
        viewModel.refreshRooms()
        viewModel.refreshUsers()
    }

    private fun collectChats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.rooms, viewModel.users, viewModel.onlineUsers) { rooms, users, onlineIds ->
                    buildListItems(rooms, users, onlineIds.toSet())
                }.collect { items ->
                    allItems = items
                    submitFilteredChats()
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.roomsLoaded.collect {
                    refreshTimeoutJob?.cancel()
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }
    
    private fun collectConnectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionError.collect {
                    refreshTimeoutJob?.cancel()
                    binding.swipeRefresh.isRefreshing = false
                    Snackbar.make(binding.root, "Chat server is unavailable. Pull down to retry.", Snackbar.LENGTH_LONG)
                        .setAction("Retry") { refreshChat() }
                        .show()
                }
            }
        }
    }

    private fun refreshChat() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(REFRESH_TIMEOUT_MS)
            if (_binding != null) binding.swipeRefresh.isRefreshing = false
        }
        viewModel.refreshRooms()
        viewModel.refreshUsers()
    }

    private fun openSearch() {
        binding.chatSearchBar.isVisible = true
        binding.etSearchChats.requestFocus()
        submitFilteredChats()
    }

    private fun closeSearch() {
        currentQuery = ""
        binding.etSearchChats.setText("")
        binding.chatSearchBar.isVisible = false
        submitFilteredChats()
    }

    private fun submitFilteredChats() {
        val query = currentQuery.trim()
        val items = if (query.isBlank()) {
            allItems
        } else {
            val matches = allItems.filterIsInstance<ChatListItem.Room>()
                .filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.subtitle.contains(query, ignoreCase = true)
                }
            if (matches.isEmpty()) emptyList() else listOf(ChatListItem.Header("Search results")) + matches
        }
        adapter?.submitList(items)
        binding.tvEmptyChats.isVisible = items.isEmpty() && allItems.isNotEmpty()
        // If allItems is empty, we might still be loading or truly have no chats
        if (allItems.isEmpty()) {
            binding.tvEmptyChats.text = "No conversations yet"
            binding.tvEmptyChats.isVisible = true
        } else {
            binding.tvEmptyChats.text = "No chats found"
        }
    }

    private fun buildListItems(
        rooms: List<ChatRoom>,
        users: List<ChatUser>,
        onlineIds: Set<String>
    ): List<ChatListItem> {
        val result = mutableListOf<ChatListItem>()
        
        // Pinned Section
        result.add(ChatListItem.Header("Pinned"))
        
        val groupRoom = rooms.firstOrNull { it.id == GROUP_ROOM_ID }
            ?: ChatRoom(id = GROUP_ROOM_ID, type = "group", name = "Class Group", member1Id = "", member2Id = "")
        
        result.add(ChatListItem.Room(
            room = groupRoom.copy(name = "Class Group"),
            title = "Class Group",
            subtitle = groupRoom.lastMessage.ifBlank { "No messages yet" },
            isGroup = true
        ))

        // Recent Section
        val dms = rooms
            .filter { it.type == "dm" }
            .sortedByDescending { it.lastMessageTime }
            .map { room ->
                val otherId = room.otherUserId.ifBlank {
                    listOf(room.member1Id, room.member2Id).firstOrNull { it.isNotBlank() && it != currentUserId }.orEmpty()
                }
                val user = users.firstOrNull { it.id == otherId }
                val title = room.otherUserName.ifBlank {
                    user?.name ?: room.name.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() && it != currentUserName() } ?: "Direct message"
                }
                ChatListItem.Room(
                    room = room,
                    title = title,
                    subtitle = room.lastMessage.ifBlank { "Start a conversation" },
                    avatarUrl = room.otherUserAvatar.ifBlank { user?.avatarUrl ?: room.avatarUrl },
                    isOnline = otherId in onlineIds,
                    otherUserId = otherId
                )
            }
            
        if (dms.isNotEmpty()) {
            result.add(ChatListItem.Header("Recent"))
            result.addAll(dms)
        }
        
        return result
    }

    private fun currentUserName(): String {
        val user = FirebaseAuth.getInstance().currentUser
        return user?.displayName ?: user?.email?.substringBefore("@") ?: ""
    }

    private fun openChat(item: ChatListItem) {
        if (currentUserId.isBlank()) {
            Toast.makeText(requireContext(), "Please sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        if (item is ChatListItem.Room) {
            if (item.isGroup) {
                safeNavigate(R.id.fragment_group_chat)
                return
            }
            safeNavigate(
                R.id.fragment_dm_chat,
                bundleOf(
                    "roomId" to item.room.id,
                    "otherUserName" to item.title,
                    "otherUserId" to item.otherUserId
                )
            )
        }
    }

    private fun openNewMessage() {
        if (currentUserId.isBlank()) {
            Toast.makeText(requireContext(), "Please sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        safeNavigate(R.id.fragment_dm_list)
    }

    private fun safeNavigate(destinationId: Int, args: Bundle? = null) {
        runCatching {
            (activity as? MainActivity)?.openChildDestination(R.id.nav_chat, destinationId, args)
                ?: error("MainActivity navigation host unavailable")
        }.onFailure {
            Toast.makeText(requireContext(), "Navigation failed. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun archiveSwipeCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val item = adapter?.currentList?.getOrNull(viewHolder.bindingAdapterPosition)
                return if (item is ChatListItem.Header || (item as? ChatListItem.Room)?.isGroup == true) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter?.notifyItemChanged(viewHolder.bindingAdapterPosition)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        _binding = null
        adapter = null
    }

    companion object {
        private const val GROUP_ROOM_ID = "group_main"
        private const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
