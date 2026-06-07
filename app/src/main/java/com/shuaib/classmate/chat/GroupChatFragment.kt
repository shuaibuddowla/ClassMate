package com.shuaib.classmate.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.model.ChatMessage
import com.shuaib.classmate.databinding.FragmentGroupChatBinding
import com.shuaib.classmate.utils.CloudinaryUploader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GroupChatFragment : Fragment() {
    private val viewModel: ChatViewModel by activityViewModels()
    private var _binding: FragmentGroupChatBinding? = null
    private val binding get() = _binding!!

    private var adapter: ChatAdapter? = null
    private var isAdmin = false

    private var replyMessage: ChatMessage? = null
    private var editingMessage: ChatMessage? = null
    private var typingHideJob: Job? = null
    private var refreshTimeoutJob: Job? = null
    private var lastTypingSentAt = 0L
    private var latestPinned: ChatMessage? = null
    private var activeSearchQuery: String = ""
    private var emptySearchToastShownFor: String? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { showImagePreview(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivToolbarAvatar.setImageDrawable(null)
        binding.ivToolbarAvatar.background = AvatarUtils.circle(GROUP_ROOM_ID)

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        adapter = ChatAdapter(
            currentUserId = currentUserId,
            isAdminProvider = { isAdmin },
            onDeleteClick = { message -> viewModel.deleteMessage(message.id, isAdmin) },
            onReplyClick = { startReply(it) },
            onReactClick = { message, emoji -> viewModel.reactMessage(message.id, emoji) },
            onEditClick = { startEdit(it) },
            onForwardClick = { message -> chooseForwardTarget(message) },
            onPinClick = { message, pin -> viewModel.pinMessage(message.id, pin) }
        )
        
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
                reverseLayout = false
            }
            adapter = this@GroupChatFragment.adapter
        }
        
        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        val searchAdapter = ChatSearchAdapter { message ->
            val index = adapter?.getAdapterPositionForMessageId(message.id) ?: -1
            if (index >= 0) binding.rvMessages.scrollToPosition(index)
        }
        binding.rvSearchResults.adapter = searchAdapter
        
        ItemTouchHelper(replySwipeCallback()).attachToRecyclerView(binding.rvMessages)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSend.setOnClickListener { sendCurrentMessage() }
        binding.btnAttach.setOnClickListener { imagePicker.launch("image/*") }
        binding.btnEmoji.setOnClickListener { showEmojiInputPicker() }
        binding.btnSayHello.setOnClickListener {
            binding.etMessage.setText("Hello!")
            binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
            sendCurrentMessage()
        }
        binding.btnCancelMode.setOnClickListener { clearModes() }
        
        binding.btnSearch.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.etSearchMessages.requestFocus()
        }
        
        binding.btnCloseSearch.setOnClickListener {
            binding.searchBar.visibility = View.GONE
            binding.rvSearchResults.visibility = View.GONE
            activeSearchQuery = ""
            emptySearchToastShownFor = null
        }
        
        binding.etSearchMessages.setOnEditorActionListener { _, _, _ ->
            performMessageSearch()
            true
        }
        
        binding.pinnedBanner.setOnClickListener {
            latestPinned?.let { pinned ->
                val index = adapter?.getAdapterPositionForMessageId(pinned.id) ?: -1
                if (index >= 0) binding.rvMessages.scrollToPosition(index)
            }
        }
        
        binding.btnUnpin.setOnClickListener { latestPinned?.let { viewModel.pinMessage(it.id, false) } }
        
        binding.etMessage.addTextChangedListener(typingWatcher(GROUP_ROOM_ID))
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            refreshTimeoutJob?.cancel()
            refreshTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(REFRESH_TIMEOUT_MS)
                if (_binding != null) binding.swipeRefresh.isRefreshing = false
            }
            viewModel.refreshCurrentRoom()
        }

        checkAdmin(currentUserId)
        viewModel.openRoom(GROUP_ROOM_ID)
        collectChatState()
    }

    override fun onResume() {
        super.onResume()
        ChatRepository.enterRoom(GROUP_ROOM_ID)
    }

    override fun onPause() {
        super.onPause()
        ChatRepository.leaveRoom(GROUP_ROOM_ID)
    }

    private fun collectChatState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        adapter?.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                val lastRow = (adapter?.itemCount ?: 0) - 1
                                if (lastRow >= 0) binding.rvMessages.smoothScrollToPosition(lastRow)
                            }
                        }
                        binding.emptyState.isVisible = messages.isEmpty()
                    }
                }
                launch {
                    viewModel.historyLoaded.collect { roomId ->
                        if (roomId == GROUP_ROOM_ID) {
                            refreshTimeoutJob?.cancel()
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }
                launch {
                    viewModel.rooms.collect { rooms ->
                        val count = rooms.firstOrNull { it.id == GROUP_ROOM_ID }?.memberCount ?: 24
                        binding.tvOnlineStatus.text = "$count members"
                    }
                }
                launch {
                    viewModel.connectionState.collect { state ->
                        val connected = state == ConnectionState.CONNECTED
                        binding.viewConnectionDot.background = statusDot(if (connected) "#34D399" else "#FF4D6D")
                        binding.toolbarProgress.isVisible = state == ConnectionState.CONNECTING
                        updateConnectionBanner(state)
                        if (state == ConnectionState.CONNECTED) {
                            refreshTimeoutJob?.cancel()
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }
                launch {
                    viewModel.typingEvent.collect { event ->
                        if (event.roomId == GROUP_ROOM_ID && event.userId != FirebaseAuth.getInstance().currentUser?.uid) {
                            showTyping("${event.userName} is typing...")
                        }
                    }
                }
                launch {
                    viewModel.pinnedMessages.collect { pinned ->
                        latestPinned = pinned.lastOrNull()
                        binding.pinnedBanner.isVisible = latestPinned != null
                        binding.tvPinnedMessage.text = latestPinned?.text.orEmpty()
                        binding.btnUnpin.isVisible = isAdmin
                    }
                }
                launch {
                    viewModel.searchResults.collect { results ->
                        (binding.rvSearchResults.adapter as? ChatSearchAdapter)?.submitList(results)
                        binding.rvSearchResults.isVisible = results.isNotEmpty()
                        if (binding.searchBar.isVisible == true &&
                            activeSearchQuery.isNotBlank() &&
                            results.isEmpty() &&
                            emptySearchToastShownFor != activeSearchQuery
                        ) {
                            emptySearchToastShownFor = activeSearchQuery
                            Toast.makeText(requireContext(), "No messages found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    viewModel.connectionError.collect {
                        refreshTimeoutJob?.cancel()
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun sendCurrentMessage() {
        val text = binding.etMessage.text?.toString().orEmpty()
        val editing = editingMessage
        if (editing != null) {
            if (text.isBlank()) return
            viewModel.editMessage(editing.id, text)
            clearModes()
        } else {
            if (text.isBlank()) return
            viewModel.sendMessage(text, replyMessage?.id, replyMessage?.text, replyMessage?.senderName)
            replyMessage = null
            clearModes()
        }
        binding.etMessage.text?.clear()
    }

    private fun performMessageSearch() {
        val query = binding.etSearchMessages.text?.toString()?.trim().orEmpty()
        activeSearchQuery = query
        emptySearchToastShownFor = null
        if (query.isBlank()) {
            binding.rvSearchResults.isVisible = false
            Toast.makeText(requireContext(), "Enter search text", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.searchMessages(query)
        view?.postDelayed({
            if (activeSearchQuery == query &&
                binding.searchBar.isVisible == true &&
                ((binding.rvSearchResults.adapter as? ChatSearchAdapter)?.itemCount ?: 0) == 0 &&
                emptySearchToastShownFor != query
            ) {
                emptySearchToastShownFor = query
                Toast.makeText(requireContext(), "No messages found", Toast.LENGTH_SHORT).show()
            }
        }, SEARCH_EMPTY_FEEDBACK_DELAY_MS)
    }

    private fun checkAdmin(currentUserId: String) {
        if (currentUserId.isBlank()) return
        FirebaseFirestore.getInstance().collection("users").document(currentUserId).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                val canPost = doc.getBoolean("permissions.canPostNotices") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canPost
            }
    }

    private fun statusDot(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    private fun startReply(message: ChatMessage) {
        replyMessage = message
        editingMessage = null
        binding.tvModeTitle.text = "Replying to ${message.senderName}"
        binding.tvModeText.text = message.text
        binding.replyEditBar.visibility = View.VISIBLE
    }

    private fun startEdit(message: ChatMessage) {
        editingMessage = message
        replyMessage = null
        binding.etMessage.setText(message.text)
        binding.etMessage.setSelection(message.text.length)
        binding.tvModeTitle.text = "Editing message"
        binding.tvModeText.text = message.text
        binding.replyEditBar.visibility = View.VISIBLE
    }

    private fun clearModes() {
        replyMessage = null
        editingMessage = null
        binding.replyEditBar.visibility = View.GONE
    }

    private fun chooseForwardTarget(message: ChatMessage) {
        val rooms = viewModel.rooms.value
        if (rooms.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setItems(rooms.map { it.name.ifBlank { it.id } }.toTypedArray()) { _, which ->
                viewModel.forwardMessage(message.id, rooms[which].id)
            }
            .show()
    }

    private fun showEmojiInputPicker() {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        QUICK_EMOJIS.forEach { emoji ->
            val item = TextView(requireContext()).apply {
                text = emoji
                textSize = 28f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                setOnClickListener {
                    val editable = binding.etMessage.text ?: return@setOnClickListener
                    val cursor = binding.etMessage.selectionStart.coerceAtLeast(0)
                    editable.insert(cursor.coerceAtMost(editable.length), emoji)
                }
            }
            row.addView(item)
        }
        AlertDialog.Builder(requireContext())
            .setView(row)
            .show()
    }

    private fun showImagePreview(uri: Uri) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.dialog_image_send_preview, null)
        val preview = sheet.findViewById<android.widget.ImageView>(R.id.ivPreviewImage)
        val caption = sheet.findViewById<EditText>(R.id.etCaption)
        val progress = sheet.findViewById<View>(R.id.progressUpload)
        val send = sheet.findViewById<View>(R.id.btnSendImage)
        val cancel = sheet.findViewById<View>(R.id.btnCancel)
        Glide.with(this).load(uri).centerCrop().into(preview)
        cancel.setOnClickListener { dialog.dismiss() }
        send.setOnClickListener {
            send.isEnabled = false
            cancel.isEnabled = false
            progress.isVisible = true
            uploadImage(uri, caption.text?.toString().orEmpty(), dialog, progress)
        }
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun uploadImage(uri: Uri, caption: String, dialog: BottomSheetDialog, progress: View) {
        binding.imageUploadOverlay.visibility = View.VISIBLE
        CloudinaryUploader.uploadImage(
            requireContext(),
            uri,
            "classmate/chat",
            onSuccess = { url, _ ->
                if (_binding != null) binding.imageUploadOverlay.visibility = View.GONE
                progress.isVisible = false
                dialog.dismiss()
                viewModel.sendImage(GROUP_ROOM_ID, url, caption)
            },
            onFailure = {
                if (_binding != null) binding.imageUploadOverlay.visibility = View.GONE
                progress.isVisible = false
                dialog.dismiss()
                view?.let { root -> Snackbar.make(root, "Failed to send image, try again", Snackbar.LENGTH_LONG).show() }
            }
        )
    }

    private fun replySwipeCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter?.getMessageAtAdapterPosition(viewHolder.bindingAdapterPosition)?.let { startReply(it) }
                adapter?.notifyItemChanged(viewHolder.bindingAdapterPosition)
            }
        }
    }

    private fun typingWatcher(roomId: String): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                binding.btnSend.isVisible = hasText
                binding.btnMic.isVisible = !hasText
                if (s.isNullOrBlank()) return
                val now = System.currentTimeMillis()
                if (now - lastTypingSentAt >= TYPING_DEBOUNCE_MS) {
                    lastTypingSentAt = now
                    viewModel.sendTyping(roomId)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
    }

    private fun showTyping(text: String) {
        binding.tvTypingIndicator.text = text
        binding.tvTypingIndicator.visibility = View.VISIBLE
        typingHideJob?.cancel()
        typingHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(TYPING_VISIBLE_MS)
            binding.tvTypingIndicator.visibility = View.GONE
        }
    }

    private fun updateConnectionBanner(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> binding.tvConnectionBanner.visibility = View.GONE
            ConnectionState.CONNECTING -> {
                binding.tvConnectionBanner.text = "Connecting..."
                binding.tvConnectionBanner.setBackgroundColor(Color.parseColor("#FBBF24"))
                binding.tvConnectionBanner.visibility = View.VISIBLE
            }
            ConnectionState.DISCONNECTED -> {
                binding.tvConnectionBanner.text = "Reconnecting..."
                binding.tvConnectionBanner.setBackgroundColor(Color.parseColor("#FF4D6D"))
                binding.tvConnectionBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        typingHideJob?.cancel()
        refreshTimeoutJob?.cancel()
        typingHideJob = null
        refreshTimeoutJob = null
        _binding = null
        adapter = null
    }

    companion object {
        private const val GROUP_ROOM_ID = "group_main"
        private const val TYPING_DEBOUNCE_MS = 2_000L
        private const val TYPING_VISIBLE_MS = 3_000L
        private const val SEARCH_EMPTY_FEEDBACK_DELAY_MS = 1_200L
        private const val REFRESH_TIMEOUT_MS = 8_000L
        private val QUICK_EMOJIS = arrayOf(
            "\uD83D\uDC4D",
            "\u2764\uFE0F",
            "\uD83D\uDE02",
            "\uD83D\uDE2E",
            "\uD83D\uDE22",
            "\uD83D\uDD25"
        )
    }
}
