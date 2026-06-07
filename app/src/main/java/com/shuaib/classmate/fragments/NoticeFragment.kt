// com/shuaib/classmate/fragments/NoticeFragment.kt
package com.shuaib.classmate.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.activities.PostNoticeActivity
import com.shuaib.classmate.adapters.NoticeAdapter
import com.shuaib.classmate.adapters.NoticeGroupHeader
import com.shuaib.classmate.databinding.FragmentNoticeBinding
import com.shuaib.classmate.databinding.ItemNoticeFilterChipBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.models.Poll
import com.shuaib.classmate.notices.NoticeEngagement
import com.shuaib.classmate.notices.NoticeCommentsBottomSheetFragment
import com.shuaib.classmate.notices.NoticeLikeManager
import com.shuaib.classmate.notices.NoticeReminderManager
import com.shuaib.classmate.notices.NoticeUi
import com.shuaib.classmate.notices.ShareForwardNoticeBottomSheet
import com.shuaib.classmate.utils.NetworkMonitor
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.viewmodels.NoticeViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class NoticeFragment : Fragment() {

    private var _binding: FragmentNoticeBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var noticeAdapter: NoticeAdapter
    private val noticeViewModel: NoticeViewModel by viewModels()

    private var isAdmin = false
    private var currentUserId = ""
    private var selectedFilter = NoticeFilter.ALL
    private var searchQuery = ""
    private var allNotices: List<Notice> = emptyList()
    private var allPolls: List<Poll> = emptyList()
    private val engagementListeners = mutableListOf<ListenerRegistration>()
    private var engagementNoticeIds: Set<String> = emptySet()
    private val likeCountByNotice = mutableMapOf<String, Int>()
    private val commentCountByNotice = mutableMapOf<String, Int>()
    private val shareCountByNotice = mutableMapOf<String, Int>()
    private var likedNoticeIds: Set<String> = emptySet()
    private val pendingPinnedState = mutableMapOf<String, Boolean>()
    private var currentRenderedItems: List<Any> = emptyList()

    private enum class NoticeFilter(val label: String, val icon: String) {
        ALL("All", "A"),
        NOTICES("Notices", "N"),
        DEADLINES("Deadlines", "!"),
        POLLS("Polls", "%"),
        RESOURCES("Resources", "R"),
        EXAMS("Exams", "EX")
    }

    private val homeFilters = listOf(
        NoticeFilter.ALL,
        NoticeFilter.NOTICES,
        NoticeFilter.DEADLINES
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNoticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        currentUserId = auth.currentUser?.uid.orEmpty()

        val noticeIdArg = arguments?.getString("noticeId")
        if (!noticeIdArg.isNullOrBlank()) {
            com.shuaib.classmate.utils.NotificationRouter.pendingNoticeId = noticeIdArg
        }

        setupRecyclerView()
        setupTopBar()
        setupSearch()
        renderFilterChips()
        observeCachedNotices()
        checkAdminAccess()
        fetchData()

        binding.swipeRefresh.setOnRefreshListener { fetchData() }
        val context = context ?: return
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(ThemeColors.surface(context))
        binding.swipeRefresh.setColorSchemeColors(
            ThemeColors.primarySoft(context),
            ThemeColors.info(context),
            ThemeColors.textSecondary(context)
        )
    }

    override fun onResume() {
        super.onResume()
        // Force a refresh when returning to this fragment to ensure data is up-to-date
        if (allNotices.isNotEmpty()) {
            noticeViewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        currentRenderedItems = emptyList()
        noticeAdapter = NoticeAdapter(
            currentUserId = currentUserId,
            isAdminProvider = { isAdmin },
            onNoticeClick = { /* Handled inline via expansion toggle */ },
            onLikeClick = { notice -> toggleNoticeLike(notice) },
            onCommentClick = { notice -> showCommentsBottomSheet(notice.id) },
            onPinClick = { notice -> togglePin(notice) },
            onForwardClick = { notice -> openForwardSheet(notice.id) },
            onReminderClick = { notice -> NoticeReminderManager.showReminderOptions(requireContext(), notice) },
            onNoticeLongClick = { notice -> if (isAdmin) showNoticeActionsDialog(notice) },
            onPollVote = { pollId, option -> castVote(pollId, option) },
            onPollMultiVote = { poll, option -> toggleMultiVote(poll, option) },
            onPollDelete = { pollId -> deletePoll(pollId) }
        )
        binding.rvNotices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = noticeAdapter
            itemAnimator?.changeDuration = 0
        }
    }

    private fun setupTopBar() {
        binding.btnPostNotice.setOnClickListener { startActivity(Intent(requireContext(), PostNoticeActivity::class.java)) }
        binding.btnEmptyPost.setOnClickListener { startActivity(Intent(requireContext(), PostNoticeActivity::class.java)) }
        binding.btnEmptyReset.setOnClickListener { resetFiltersAndSearch() }
        binding.btnMenu.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            binding.rvNotices.stopScroll()
            binding.rvNotices.smoothScrollToPosition(0)
        }
    }

    private fun resetFiltersAndSearch() {
        selectedFilter = NoticeFilter.ALL
        searchQuery = ""
        binding.etNoticeSearch.text?.clear()
        noticeAdapter.setSearchQuery("")
        setSearchMode(false)
        renderFilterChips()
        renderFeed()
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener { setSearchMode(true) }
        binding.btnCloseSearch.setOnClickListener {
            binding.etNoticeSearch.text?.clear()
            setSearchMode(false)
        }
        binding.etNoticeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim()
                noticeAdapter.setSearchQuery(searchQuery)
                renderFeed()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setSearchMode(enabled: Boolean) {
        binding.headerTitleBlock.isVisible = !enabled
        binding.searchContainer.isVisible = enabled
        binding.btnSearch.isVisible = !enabled
        if (enabled) {
            binding.etNoticeSearch.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etNoticeSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun fetchData() {
        if (_binding == null) return
        if (!binding.swipeRefresh.isRefreshing) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvNotices.isVisible = false
        }
        binding.emptyNoticeState.isVisible = false
        binding.swipeRefresh.isRefreshing = true
        binding.tvNoticeSubtitle.text = getString(R.string.notice_home_loading)

        if (::noticeAdapter.isInitialized) {
            noticeAdapter.clearAnimationRegistry()
        }
        noticeViewModel.refresh()

        if (NetworkMonitor.isOnline(requireContext())) {
            fetchPolls()
        } else {
            binding.swipeRefresh.isRefreshing = false
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.rvNotices.isVisible = true
            renderFilterChips()
            renderFeed()
        }
    }

    private fun observeCachedNotices() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    noticeViewModel.notices.collect { notices ->
                        if (_binding == null) return@collect
                        allNotices = notices
                        reconcilePendingPinState(notices)

                        binding.shimmerView.stopShimmer()
                        binding.shimmerView.isVisible = false
                        binding.rvNotices.isVisible = true

                        restartEngagementListeners(notices.map { it.id }.filter { it.isNotBlank() }.toSet())
                        renderFilterChips()
                        renderFeed()
                    }
                }
                launch {
                    noticeViewModel.isRefreshing.collect { refreshing ->
                        if (_binding == null) return@collect
                        if (!refreshing) {
                            binding.swipeRefresh.isRefreshing = false
                        }
                    }
                }
            }
        }
    }

    private fun fetchPolls() {
        val pollDocsTask = db.collection("polls").get()
        pollDocsTask.addOnSuccessListener { querySnapshot ->
            if (_binding == null) return@addOnSuccessListener
            val docs = querySnapshot.documents
            if (docs.isEmpty()) {
                showFetchedPolls(emptyList())
                return@addOnSuccessListener
            }

            val tasks = docs.map { it.reference.collection("votes").get() }
            Tasks.whenAllComplete(tasks)
                .addOnSuccessListener {
                    if (_binding == null) return@addOnSuccessListener
                    val polls = mutableListOf<Poll>()
                    docs.forEachIndexed { index, doc ->
                        val voteTask = tasks[index]
                        val votes = if (voteTask.isSuccessful) voteTask.result as? QuerySnapshot else null
                        polls.add(buildPoll(doc, votes))
                    }
                    showFetchedPolls(polls)
                }
                .addOnFailureListener {
                    handlePollRefreshFailure("Could not load poll votes.")
                }
        }.addOnFailureListener {
            handlePollRefreshFailure("Could not load polls.")
        }
    }

    private fun showFetchedPolls(polls: List<Poll>) {
        if (_binding == null) return
        allPolls = polls
        binding.shimmerView.stopShimmer()
        binding.shimmerView.isVisible = false
        binding.rvNotices.isVisible = true
        binding.swipeRefresh.isRefreshing = false
        renderFilterChips()
        renderFeed()
    }

    private fun handlePollRefreshFailure(message: String) {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = false
        binding.shimmerView.stopShimmer()
        binding.shimmerView.isVisible = false
        binding.rvNotices.isVisible = true

        if (allPolls.isEmpty()) {
            showFetchedPolls(emptyList())
        } else {
            renderFilterChips()
            renderFeed()
        }
        if (NetworkMonitor.isOnline(requireContext()) || allNotices.isEmpty()) {
            showNoticeError(message)
        }
    }

    private fun renderFeed(scrollToTop: Boolean = false) {
        if (_binding == null || !::noticeAdapter.isInitialized) return

        viewLifecycleOwner.lifecycleScope.launch {
            val pinnedIds = pinnedNoticeIds()
            val filteredItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                (allNotices + allPolls)
                    .filter { item -> itemMatchesFilter(item) }
                    .filter { item -> itemMatchesSearch(item) }
                    .sortedWith(
                        compareByDescending<Any> { item -> item is Notice && item.id in pinnedIds }
                            .thenByDescending { item -> itemTimestampMillis(item) }
                    )
            }

            if (_binding == null) return@launch
            updateNoticeHomeSubtitle(filteredItems.size)

            val rendered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val pinnedItems = filteredItems.filterIsInstance<Notice>().filter { it.id in pinnedIds }
                val normalItems = filteredItems.filterNot { it is Notice && it.id in pinnedIds }

                val sections = linkedMapOf(
                    "Today" to mutableListOf<Any>(),
                    "Yesterday" to mutableListOf(),
                    "Earlier This Week" to mutableListOf(),
                    "Older" to mutableListOf()
                )

                normalItems.forEach { item ->
                    val section = NoticeUi.sectionFor(itemTimestampMillis(item))
                    sections.getOrPut(section) { mutableListOf() }.add(item)
                }

                val list = mutableListOf<Any>()
                if (pinnedItems.isNotEmpty()) {
                    list.add(NoticeGroupHeader(key = "pinned", title = "Pinned Notices", count = pinnedItems.size))
                    list.addAll(pinnedItems)
                }
                sections.forEach { (section, sectionItems) ->
                    if (sectionItems.isNotEmpty()) {
                        list.add(NoticeGroupHeader(key = section, title = section, count = sectionItems.size))
                        list.addAll(sectionItems)
                    }
                }
                list
            }

            if (_binding == null) return@launch
            if (rendered != currentRenderedItems || noticeAdapter.currentList != rendered) {
                currentRenderedItems = rendered
                noticeAdapter.submitList(rendered) {
                    if (_binding == null) return@submitList

                    // Handle highlighted notice from notification
                    com.shuaib.classmate.utils.NotificationRouter.pendingNoticeId?.let { highlightId ->
                        val pos = noticeAdapter.currentList.indexOfFirst { it is Notice && it.id == highlightId }
                        if (pos != -1) {
                            binding.rvNotices.post {
                                binding.rvNotices.scrollToPosition(pos)
                                binding.rvNotices.postDelayed({
                                    noticeAdapter.setHighlightedNotice(highlightId)
                                    noticeAdapter.expandNotice(highlightId)
                                }, 300)
                            }
                            // Clear it so it doesn't re-animate on every config change/refresh
                            com.shuaib.classmate.utils.NotificationRouter.pendingNoticeId = null
                        }
                    }

                    if (scrollToTop) {
                        val firstNoticePosition = if (noticeAdapter.currentList.getOrNull(0) is NoticeGroupHeader) 1 else 0
                        binding.rvNotices.scrollToPosition(firstNoticePosition)
                    }
                    noticeAdapter.setEngagementState(buildEngagementState())
                }
            } else {
                noticeAdapter.setEngagementState(buildEngagementState())
            }
            updateEmptyState(filteredItems.isEmpty())
        }
    }

    private fun buildPoll(doc: com.google.firebase.firestore.DocumentSnapshot, voteSnapshot: com.google.firebase.firestore.QuerySnapshot?): Poll {
        val votes = voteSnapshot?.documents?.associate { it.id to (it.getString("option") ?: "") }?.filterValues { it.isNotBlank() } ?: emptyMap()
        val multiVotes = voteSnapshot?.documents?.associate { voteDoc ->
            val selected = (voteDoc.get("selectedOptions") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            voteDoc.id to selected
        }?.filterValues { it.isNotEmpty() } ?: emptyMap()

        return Poll(
            id = doc.id,
            question = doc.getString("question") ?: "",
            options = (doc.get("options") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            createdBy = doc.getString("createdBy") ?: "",
            createdAt = doc.getTimestamp("createdAt"),
            expiresAt = doc.getTimestamp("expiresAt"),
            isActive = doc.getBoolean("isActive") ?: true,
            votes = votes,
            allowMultipleAnswers = doc.getBoolean("allowMultipleAnswers") ?: false,
            multiVotes = multiVotes
        )
    }

    private fun updateNoticeHomeSubtitle(visibleCount: Int) {
        if (_binding == null) return
        val total = allNotices.size + allPolls.size
        binding.tvNoticeSubtitle.text = when {
            total == 0 -> "No updates - Pull to refresh"
            searchQuery.isNotBlank() -> "$visibleCount found - Pull to refresh"
            selectedFilter != NoticeFilter.ALL -> "$visibleCount ${selectedFilter.label.lowercase(Locale.getDefault())} - Pull to refresh"
            else -> "$total ${if (total == 1) "update" else "updates"} - Pull to refresh"
        }
    }

    private fun renderFilterChips() {
        val context = context ?: return
        if (_binding == null) return
        if (selectedFilter !in homeFilters) selectedFilter = NoticeFilter.ALL
        val counts = mapOf(
            NoticeFilter.ALL to allNotices.size + allPolls.size,
            NoticeFilter.NOTICES to allNotices.count { it.displayType == "notice" || it.displayType == "cancellation" },
            NoticeFilter.DEADLINES to allNotices.count { it.displayType == "deadline" },
            NoticeFilter.POLLS to allPolls.size,
            NoticeFilter.RESOURCES to allNotices.count { it.displayType == "resource" },
            NoticeFilter.EXAMS to allNotices.count { it.displayType == "exam" }
        )
        binding.filterChipContainer.removeAllViews()
        homeFilters.forEach { filter ->
            val chip = ItemNoticeFilterChipBinding.inflate(layoutInflater, binding.filterChipContainer, false)
            val selected = filter == selectedFilter
            chip.chipRoot.background = context.getDrawable(
                if (selected) R.drawable.bg_notice_chip_selected else R.drawable.bg_notice_chip
            )
            chip.tvChipIcon.text = filter.icon
            chip.tvChipIcon.isVisible = false
            chip.tvChipLabel.text = filter.label
            chip.tvChipCount.text = "${counts[filter] ?: 0}"
            chip.tvChipLabel.setTextColor(
                if (selected) ThemeColors.onPrimary(context) else ThemeColors.textSecondary(context)
            )
            chip.tvChipCount.setTextColor(
                if (selected) ThemeColors.onPrimary(context) else ThemeColors.textSecondary(context)
            )
            chip.chipRoot.setOnClickListener {
                selectedFilter = filter
                it.animate().scaleX(0.98f).scaleY(0.98f).setDuration(55).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                renderFilterChips()
                renderFeed()
            }
            binding.filterChipContainer.addView(chip.root)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyNoticeState.isVisible = isEmpty
        binding.rvNotices.isVisible = !isEmpty && !binding.shimmerView.isVisible
        binding.btnEmptyPost.isVisible = isAdmin && allNotices.isEmpty() && allPolls.isEmpty()
        binding.btnEmptyReset.isVisible =
            isEmpty && (allNotices.isNotEmpty() || allPolls.isNotEmpty())
        binding.tvEmptyTitle.text = when {
            allNotices.isEmpty() && allPolls.isEmpty() -> "No notices yet"
            searchQuery.isNotBlank() -> "No matching notices"
            else -> "Nothing in this filter"
        }
        binding.tvEmptySubtitle.text = when {
            allNotices.isEmpty() && allPolls.isEmpty() -> "Important class updates will appear here."
            searchQuery.isNotBlank() -> "Try a different title, subject, type, or teacher."
            else -> "Switch to All to see every update."
        }
    }

    private fun itemMatchesFilter(item: Any): Boolean {
        return when (selectedFilter) {
            NoticeFilter.ALL -> true
            NoticeFilter.NOTICES -> item is Notice && (item.displayType == "notice" || item.displayType == "cancellation")
            NoticeFilter.DEADLINES -> item is Notice && item.displayType == "deadline"
            NoticeFilter.POLLS -> item is Poll
            NoticeFilter.RESOURCES -> item is Notice && item.displayType == "resource"
            NoticeFilter.EXAMS -> item is Notice && item.displayType == "exam"
        }
    }

    private fun itemMatchesSearch(item: Any): Boolean {
        if (searchQuery.isBlank()) return true
        val query = searchQuery.lowercase(Locale.getDefault())
        return when (item) {
            is Notice -> listOf(
                item.title,
                com.shuaib.classmate.notices.NoticeTextFormatter.stripMarkdown(item.content),
                item.displaySubject,
                item.displayType,
                item.displayAuthor,
                item.topic,
                item.attachmentName
            ).any { it.lowercase(Locale.getDefault()).contains(query) }
            is Poll -> listOf(item.question, item.createdBy, item.options.joinToString(" "))
                .any { it.lowercase(Locale.getDefault()).contains(query) }
            else -> false
        }
    }

    private fun itemTimestampMillis(item: Any): Long {
        return when (item) {
            is Notice -> item.createdAt?.toDate()?.time ?: 0L
            is Poll -> item.createdAt?.toDate()?.time ?: 0L
            else -> 0L
        }
    }



    private fun showCommentsBottomSheet(noticeId: String) {
        NoticeCommentsBottomSheetFragment.newInstance(noticeId)
            .show(childFragmentManager, NoticeCommentsBottomSheetFragment.TAG)
    }

    private fun openForwardSheet(noticeId: String) {
        ShareForwardNoticeBottomSheet.newInstance(noticeId).apply {
            onShareRecorded = { incrementShareCount(noticeId) }
        }.show(childFragmentManager, ShareForwardNoticeBottomSheet.TAG)
    }

    private fun toggleNoticeLike(notice: Notice) {
        val uid = signedInUidOrPrompt() ?: return
        currentUserId = uid
        val currentlyLiked = notice.id in likedNoticeIds
        val targetLiked = !currentlyLiked
        likedNoticeIds = if (targetLiked) likedNoticeIds + notice.id else likedNoticeIds - notice.id
        likeCountByNotice[notice.id] = ((likeCountByNotice[notice.id] ?: 0) + if (targetLiked) 1 else -1).coerceAtLeast(0)
        noticeAdapter.setEngagementState(buildEngagementState())
        NoticeLikeManager.setLiked(uid, notice.id, targetLiked) { success, error ->
            if (!success && _binding != null) {
                likedNoticeIds = if (targetLiked) likedNoticeIds - notice.id else likedNoticeIds + notice.id
                likeCountByNotice[notice.id] = ((likeCountByNotice[notice.id] ?: 0) + if (targetLiked) -1 else 1).coerceAtLeast(0)
                noticeAdapter.setEngagementState(buildEngagementState())
                Toast.makeText(requireContext(), "Like update failed: ${actionErrorMessage(error)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun togglePin(notice: Notice) {
        if (!isAdmin) return
        val nextPinned = !isPinned(notice)
        pendingPinnedState[notice.id] = nextPinned
        renderFeed(scrollToTop = nextPinned) // Scroll to top only if pinning
        db.collection("notices").document(notice.id)
            .update(mapOf("isPinned" to nextPinned, "updatedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                if (_binding == null) return@addOnSuccessListener
                // We don't necessarily need another renderFeed here if the local cache
                // sync is fast, but we remove the pending state when the real data arrives.
                // Reconcile is called in observeCachedNotices.
            }
            .addOnFailureListener {
                pendingPinnedState.remove(notice.id)
                renderFeed()
                Toast.makeText(requireContext(), "Pin update failed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun incrementShareCount(noticeId: String) {
        if (_binding == null) return
        shareCountByNotice[noticeId] = (shareCountByNotice[noticeId] ?: 0) + 1
        noticeAdapter.setEngagementState(buildEngagementState())
    }

    private fun reconcilePendingPinState(notices: List<Notice>) {
        if (pendingPinnedState.isEmpty()) return
        val confirmedIds = pendingPinnedState
            .filter { (noticeId, pendingPinned) ->
                notices.firstOrNull { it.id == noticeId }?.isPinned == pendingPinned
            }
            .keys
        confirmedIds.forEach { pendingPinnedState.remove(it) }
    }

    private fun castVote(pollId: String, option: String) {
        val previousPolls = allPolls
        allPolls = allPolls.map { poll ->
            if (poll.id == pollId) {
                poll.copy(
                    votes = poll.votes + (currentUserId to option),
                    multiVotes = poll.multiVotes + (currentUserId to listOf(option))
                )
            } else {
                poll
            }
        }
        renderFeed()
        db.collection("polls").document(pollId).collection("votes").document(currentUserId)
            .set(
                mapOf(
                    "option" to option,
                    "selectedOptions" to listOf(option),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnFailureListener {
                allPolls = previousPolls
                renderFeed()
                showNoticeError("Vote failed: ${it.message}")
            }
    }

    private fun toggleMultiVote(poll: Poll, option: String) {
        val previousPolls = allPolls
        val selected = poll.selectedOptionsFor(currentUserId).toMutableList()
        if (selected.contains(option)) selected.remove(option) else selected.add(option)
        allPolls = allPolls.map {
            if (it.id == poll.id) {
                it.copy(
                    votes = it.votes + (currentUserId to Poll.encodeMultiVote(selected)),
                    multiVotes = it.multiVotes + (currentUserId to selected)
                )
            } else {
                it
            }
        }
        renderFeed()
        db.collection("polls").document(poll.id).collection("votes").document(currentUserId)
            .set(
                mapOf(
                    "option" to Poll.encodeMultiVote(selected),
                    "selectedOptions" to selected,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnFailureListener {
                allPolls = previousPolls
                renderFeed()
                showNoticeError("Vote failed: ${it.message}")
            }
    }

    private fun deletePoll(pollId: String) {
        db.collection("polls").document(pollId).delete()
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Poll deleted", Toast.LENGTH_SHORT).show()
                fetchData()
            }
    }

    private fun showNoticeActionsDialog(notice: Notice) {
        AlertDialog.Builder(requireContext(), R.style.Theme_ClassMate_Dialog)
            .setTitle("Notice Actions")
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                if (which == 0) {
                    startActivity(
                        Intent(requireContext(), PostNoticeActivity::class.java)
                            .putExtra("NOTICE_ID", notice.id)
                            .putExtra("NOTICE_TITLE", notice.title)
                            .putExtra("NOTICE_BODY", notice.content)
                    )
                } else {
                    confirmDeleteNotice(notice)
                }
            }
            .show()
    }

    private fun confirmDeleteNotice(notice: Notice) {
        AlertDialog.Builder(requireContext(), R.style.Theme_ClassMate_Dialog)
            .setTitle("Delete Notice")
            .setMessage("This notice will be removed from the feed.")
            .setPositiveButton("Delete") { _, _ -> deleteNotice(notice) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteNotice(notice: Notice) {
        db.collection("notices").document(notice.id)
            .update(mapOf("isDeleted" to true, "updatedAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Notice deleted", Toast.LENGTH_SHORT).show()
                fetchData()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Delete failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                val canPost = doc.getBoolean("permissions.canPostNotices") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canPost
                binding.btnPostNotice.isVisible = isAdmin
                binding.btnEmptyPost.isVisible = isAdmin && allNotices.isEmpty() && allPolls.isEmpty()
            }
    }

    override fun onDestroyView() {
        engagementListeners.forEach { it.remove() }
        engagementListeners.clear()
        engagementNoticeIds = emptySet() // Crucial: allow listeners to restart when view is recreated
        binding.rvNotices.adapter = null
        currentRenderedItems = emptyList()
        _binding = null
        super.onDestroyView()
    }

    private fun restartEngagementListeners(noticeIds: Set<String>) {
        if (engagementNoticeIds == noticeIds) {
            noticeAdapter.setEngagementState(buildEngagementState())
            return
        }
        engagementNoticeIds = noticeIds
        engagementListeners.forEach { it.remove() }
        engagementListeners.clear()
        likeCountByNotice.keys.retainAll(noticeIds)
        commentCountByNotice.keys.retainAll(noticeIds)
        shareCountByNotice.keys.retainAll(noticeIds)
        likedNoticeIds = likedNoticeIds.filterTo(mutableSetOf()) { it in noticeIds }
        if (noticeIds.isEmpty()) return
        noticeIds.chunked(30).forEach { chunk ->
            engagementListeners += db.collection("notice_likes")
                .whereIn("noticeId", chunk)
                .addSnapshotListener { snapshot, _ ->
                    val docs = snapshot?.documents.orEmpty()
                    replaceCounts(likeCountByNotice, chunk, docs.groupingBy { it.getString("noticeId").orEmpty() }.eachCount())
                    likedNoticeIds = (likedNoticeIds - chunk.toSet()) +
                            docs.filter { it.getString("userId") == currentUserId }.mapNotNull { it.getString("noticeId") }
                    noticeAdapter.setEngagementState(buildEngagementState())
                }
            engagementListeners += db.collection("notice_comments")
                .whereIn("noticeId", chunk)
                .addSnapshotListener { snapshot, _ ->
                    val docs = snapshot?.documents.orEmpty().filter { it.getBoolean("isDeleted") != true }
                    replaceCounts(commentCountByNotice, chunk, docs.groupingBy { it.getString("noticeId").orEmpty() }.eachCount())
                    noticeAdapter.setEngagementState(buildEngagementState())
                }
            engagementListeners += db.collection("notice_shares")
                .whereIn("noticeId", chunk)
                .addSnapshotListener { snapshot, _ ->
                    val docs = snapshot?.documents.orEmpty()
                    replaceCounts(shareCountByNotice, chunk, docs.groupingBy { it.getString("noticeId").orEmpty() }.eachCount())
                    noticeAdapter.setEngagementState(buildEngagementState())
                }
        }
    }

    private fun replaceCounts(target: MutableMap<String, Int>, chunk: List<String>, replacement: Map<String, Int>) {
        chunk.forEach { id -> target[id] = replacement[id] ?: 0 }
    }

    private fun buildEngagementState(): Map<String, NoticeEngagement> {
        return allNotices.associate { notice ->
            notice.id to NoticeEngagement(
                noticeId = notice.id,
                likeCount = likeCountByNotice[notice.id] ?: 0,
                commentCount = commentCountByNotice[notice.id] ?: 0,
                shareCount = shareCountByNotice[notice.id] ?: 0,
                isLiked = notice.id in likedNoticeIds,
                isPinned = pendingPinnedState[notice.id] ?: notice.isPinned
            )
        }
    }

    private fun isPinned(notice: Notice): Boolean {
        return pendingPinnedState[notice.id] ?: notice.isPinned
    }

    private fun pinnedNoticeIds(): Set<String> {
        return allNotices
            .asSequence()
            .filter { isPinned(it) }
            .map { it.id }
            .toSet()
    }

    private fun showNoticeError(message: String) {
        if (_binding == null) return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("Retry") { fetchData() }
            .show()
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
}