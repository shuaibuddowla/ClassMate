package com.shuaib.classmate.notices

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.NoticeCommentAdapter
import com.shuaib.classmate.adapters.NoticeCommentThread
import com.shuaib.classmate.databinding.DialogNoticeCommentsBinding
import com.shuaib.classmate.utils.HapticHelper
import com.shuaib.classmate.utils.ThemeColors

class NoticeCommentsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: DialogNoticeCommentsBinding? = null
    private val binding get() = _binding!!

    private lateinit var commentAdapter: NoticeCommentAdapter
    private val db = FirebaseFirestore.getInstance()
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    private var noticeId = ""
    private var isAdmin = false
    private var currentUserName = "Classmate"
    private var currentUserAvatar = ""
    private var allComments: List<NoticeComment> = emptyList()
    private var likedCommentIds: Set<String> = emptySet()
    private var replyingTo: NoticeComment? = null

    private var commentsListener: ListenerRegistration? = null
    private var likedCommentsListener: ListenerRegistration? = null
    
    private var lastBottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure the dialog styles matches our transparent backgrounds and behaves correctly with keyboard adjustment
        setStyle(STYLE_NORMAL, R.style.Theme_ClassMate_BottomSheetDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogNoticeCommentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        noticeId = arguments?.getString(ARG_NOTICE_ID).orEmpty()
        if (noticeId.isBlank()) {
            dismiss()
            return
        }

        setupComments()
        setupComposer()
        setupKeyboardInsets()
        setupActions()
        loadCurrentUser()
        listenToEngagement()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun setupActions() {
        binding.btnClose.setOnClickListener { dismiss() }
    }

    private fun setupComments() {
        commentAdapter = NoticeCommentAdapter(
            currentUserId = currentUserId,
            isAdminProvider = { isAdmin },
            onLikeClick = { toggleCommentLike(it) },
            onReplyClick = { startReply(it) },
            onDeleteClick = { confirmDeleteComment(it) }
        )
        binding.rvComments.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = commentAdapter
        }
    }

    private fun setupComposer() {
        binding.btnSendComment.setOnClickListener {
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(60).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                submitComment()
            }.start()
        }
        binding.btnCancelReply.setOnClickListener { clearReply() }
        binding.etComment.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitComment()
                true
            } else {
                false
            }
        }
        binding.tvComposerAvatar.text = "C"
    }

    private fun loadCurrentUser() {
        val user = FirebaseAuth.getInstance().currentUser
        currentUserName = user?.displayName ?: user?.email?.substringBefore("@") ?: "Classmate"
        currentUserAvatar = user?.photoUrl?.toString().orEmpty()
        binding.tvComposerAvatar.text = currentUserName.firstOrNull()?.uppercaseChar()?.toString() ?: "C"
        if (currentUserId.isBlank()) return
        db.collection("users").document(currentUserId).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                currentUserName = doc.getString("name") ?: currentUserName
                currentUserAvatar = doc.getString("avatarUrl") ?: doc.getString("photoUrl") ?: currentUserAvatar
                val role = doc.getString("role") ?: "student"
                val canPost = doc.getBoolean("permissions.canPostNotices") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canPost
                binding.tvComposerAvatar.text = currentUserName.firstOrNull()?.uppercaseChar()?.toString() ?: "C"
            }
    }

    private fun listenToEngagement() {
        commentsListener = db.collection("notice_comments")
            .whereEqualTo("noticeId", noticeId)
            .limit(160)
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null) return@addSnapshotListener
                allComments = snapshot?.documents?.map { NoticeComment.from(it) }.orEmpty()
                renderComments()
            }
        likedCommentsListener = db.collection("comment_likes")
            .whereEqualTo("userId", currentUserId)
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null) return@addSnapshotListener
                likedCommentIds = snapshot?.documents?.mapNotNull { it.getString("commentId") }?.toSet().orEmpty()
                commentAdapter.setLikedCommentIds(likedCommentIds)
            }
    }

    private fun renderComments() {
        if (_binding == null) return
        val validComments = allComments.filter { !it.isDeleted || replyCountFor(it.commentId) > 0 }
        
        val roots = allComments
            .filter { it.parentCommentId.isNullOrBlank() }
            .sortedWith(
                compareByDescending<NoticeComment> { it.likeCount + (replyCountFor(it.commentId) * 2) }
                    .thenByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
            )
        val threads = roots.map { root ->
            NoticeCommentThread(root, descendantsFor(root.commentId))
        }
        commentAdapter.submitList(threads)
        commentAdapter.setLikedCommentIds(likedCommentIds)
        binding.tvEmptyComments.isVisible = threads.isEmpty()
        binding.rvComments.isVisible = threads.isNotEmpty()
        
        val count = allComments.count { !it.isDeleted }
        binding.commentsHeader.text = if (count > 0) "Discussion ($count)" else "Discussion"
    }

    private fun descendantsFor(parentId: String): List<NoticeComment> {
        val direct = allComments
            .filter { it.parentCommentId == parentId }
            .sortedBy { it.createdAt?.toDate()?.time ?: 0L }
        return direct.flatMap { listOf(it) + descendantsFor(it.commentId) }
    }

    private fun replyCountFor(commentId: String): Int {
        return allComments.count { it.parentCommentId == commentId } +
            allComments.filter { it.parentCommentId == commentId }.sumOf { replyCountFor(it.commentId) }
    }

    private fun submitComment() {
        val content = binding.etComment.text?.toString().orEmpty().trim()
        if (content.isBlank()) {
            Toast.makeText(requireContext(), "Comment cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = signedInUidOrPrompt() ?: return
        val parentId = replyingTo?.commentId
        
        val tempId = "temp_${System.currentTimeMillis()}"
        val tempComment = NoticeComment(
            commentId = tempId,
            noticeId = noticeId,
            userId = uid,
            userName = currentUserName,
            userAvatar = currentUserAvatar,
            content = content,
            parentCommentId = parentId,
            createdAt = Timestamp.now()
        )
        
        allComments = (allComments + tempComment).sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
        renderComments()
        
        HapticHelper.success(requireContext())
        binding.etComment.text?.clear()
        val wasReplying = replyingTo != null
        clearReply()
        if (!wasReplying) scrollToComments()

        NoticeCommentManager.addComment(
            noticeId = noticeId,
            userId = uid,
            userName = currentUserName,
            userAvatar = currentUserAvatar,
            content = content,
            parentCommentId = parentId
        ) { success, error ->
            if (!success) {
                allComments = allComments.filter { it.commentId != tempId }
                renderComments()
                Toast.makeText(requireContext(), "Comment failed: ${actionErrorMessage(error)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startReply(comment: NoticeComment) {
        replyingTo = comment
        binding.replyContextBar.isVisible = true
        binding.tvReplyContext.text = "Replying to ${comment.userName}"
        binding.etComment.hint = "Reply to ${comment.userName}..."
        binding.commentComposer.post { updateComposerSpacing() }
        focusCommentInput()
    }

    private fun clearReply() {
        replyingTo = null
        binding.replyContextBar.isVisible = false
        binding.etComment.hint = "Write a comment..."
        binding.commentComposer.post { updateComposerSpacing() }
    }

    private fun toggleCommentLike(comment: NoticeComment) {
        val uid = signedInUidOrPrompt() ?: return
        HapticHelper.lightPop(binding.rvComments)
        val targetLiked = comment.commentId !in likedCommentIds
        likedCommentIds = if (targetLiked) likedCommentIds + comment.commentId else likedCommentIds - comment.commentId
        commentAdapter.setLikedCommentIds(likedCommentIds)
        NoticeCommentManager.setCommentLiked(uid, comment.commentId, targetLiked, comment.noticeId) { success, error ->
            if (!success) {
                likedCommentIds = if (targetLiked) likedCommentIds - comment.commentId else likedCommentIds + comment.commentId
                commentAdapter.setLikedCommentIds(likedCommentIds)
                Toast.makeText(requireContext(), "Comment like failed: ${actionErrorMessage(error)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteComment(comment: NoticeComment) {
        AlertDialog.Builder(requireContext(), R.style.Theme_ClassMate_Dialog)
            .setTitle("Delete comment")
            .setMessage("The comment text will be removed, but replies will stay visible.")
            .setPositiveButton("Delete") { _, _ ->
                NoticeCommentManager.softDeleteComment(comment) { success ->
                    if (!success) Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupKeyboardInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            updateComposerSpacing(maxOf(imeBottom, navBottom))
            insets
        }
        binding.commentComposer.post { updateComposerSpacing() }
    }

    private fun updateComposerSpacing(bottomInset: Int = lastBottomInset) {
        if (_binding == null) return
        lastBottomInset = bottomInset
        binding.commentComposer.translationY = -bottomInset.toFloat()
        binding.replyContextBar.translationY = -bottomInset.toFloat()
        val composerHeight = binding.commentComposer.height.takeIf { it > 0 } ?: dp(72)
        val replyHeight = if (binding.replyContextBar.isVisible) {
            binding.replyContextBar.height.takeIf { it > 0 } ?: dp(42)
        } else {
            0
        }
        binding.rvComments.updatePadding(
            bottom = composerHeight + replyHeight + bottomInset + dp(16)
        )
    }

    private fun focusCommentInput() {
        if (_binding == null) return
        binding.rvComments.post {
            scrollToComments()
            binding.etComment.postDelayed({
                if (_binding == null) return@postDelayed
                binding.etComment.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.etComment, InputMethodManager.SHOW_IMPLICIT)
            }, 180)
        }
    }

    private fun scrollToComments() {
        if (_binding == null) return
        val count = commentAdapter.itemCount
        if (count > 0) {
            binding.rvComments.smoothScrollToPosition(count - 1)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun signedInUidOrPrompt(): String? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Please sign in again.", Toast.LENGTH_SHORT).show()
            return null
        }
        return uid
    }

    private fun actionErrorMessage(error: Exception?): String {
        return error?.message ?: "Unknown error"
    }

    override fun onDestroyView() {
        commentsListener?.remove()
        likedCommentsListener?.remove()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "NoticeCommentsBottomSheet"
        private const val ARG_NOTICE_ID = "noticeId"

        fun newInstance(noticeId: String): NoticeCommentsBottomSheetFragment {
            return NoticeCommentsBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTICE_ID, noticeId)
                }
            }
        }
    }
}
