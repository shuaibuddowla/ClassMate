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
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import android.view.animation.AnimationUtils
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
import com.shuaib.classmate.utils.applyClickAnimation
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
    private var currentUserName = ""
    private var currentUserStudentId = ""
    private val readNoticeCache = mutableSetOf<String>()
    private var selectedFilter = NoticeFilter.ALL
    private var lastFilter: NoticeFilter? = null
    private var searchQuery = ""
    private var allNotices: List<Notice> = emptyList()
    private var allPolls: List<Poll> = emptyList()
    private val fetchedEngagementNoticeIds = mutableSetOf<String>()
    private val likeCountByNotice = mutableMapOf<String, Int>()
    private val shareCountByNotice = mutableMapOf<String, Int>()
    private var likedNoticeIds: Set<String> = emptySet()
    private val pendingPinnedState = mutableMapOf<String, Boolean>()
    private var currentRenderedItems: List<Any> = emptyList()
    private var todayReminderShown = false

    private var isSummaryCollapsed = true
    private var isAiSummaryHiddenByScroll = false
    private var todayNoticesToSummarize: List<Notice> = emptyList()
    private var lastProcessedHash: String? = null
    private var statusBarHeight = 0
    private var totalScrollY = 0

    private enum class NoticeFilter(val label: String, val icon: String) {
        ALL("All", "✨"),
        NOTICES("Notices", "📢"),
        DEADLINES("Deadlines", "⏰"),
        POLLS("Polls", "📊"),
        RESOURCES("Resources", "📚"),
        EXAMS("Exams", "📝")
    }

    private val homeFilters = listOf(
        NoticeFilter.ALL,
        NoticeFilter.NOTICES,
        NoticeFilter.DEADLINES,
        NoticeFilter.POLLS,
        NoticeFilter.RESOURCES,
        NoticeFilter.EXAMS
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
        if (currentUserId.isNotBlank()) {
            db.collection("users").document(currentUserId).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        currentUserName = doc.getString("name") ?: ""
                        currentUserStudentId = doc.getString("studentId") ?: ""
                    }
                }
        }

        val noticeIdArg = arguments?.getString("noticeId")
        if (!noticeIdArg.isNullOrBlank()) {
            com.shuaib.classmate.utils.NotificationRouter.pendingNoticeId = noticeIdArg
        }

        setupRecyclerView()
        setupTopBar()
        setupSearch()
        setupAiSummaryCard()

        ViewCompat.setOnApplyWindowInsetsListener(binding.noticeHeaderPanel) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            this.statusBarHeight = statusBarHeight
            adjustHeaderOnScroll(totalScrollY)
            updateAiSummaryUiState()
            insets
        }

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
            onNoticeLongClick = { notice -> showNoticeActionsDialog(notice) },
            onCopyClick = { notice -> copyNoticeToClipboard(notice) },
            onPollVote = { pollId, option -> castVote(pollId, option) },
            onPollMultiVote = { poll, option -> toggleMultiVote(poll, option) },
            onPollDelete = { pollId -> deletePoll(pollId) },
            onNoticeViewed = { notice -> markNoticeAsRead(notice) },
            onReadReceiptsClick = { notice -> showReadReceipts(notice) }
        )
        binding.rvNotices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = noticeAdapter
            itemAnimator?.changeDuration = 0
            
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    totalScrollY += dy
                    if (!recyclerView.canScrollVertically(-1)) {
                        totalScrollY = 0
                    }
                    adjustHeaderOnScroll(totalScrollY)

                    if (!recyclerView.canScrollVertically(-1)) {
                        setAiSummaryHiddenByScroll(false)
                    } else if (dy > 12) {
                        setAiSummaryHiddenByScroll(true)
                    } else if (dy < -12) {
                        setAiSummaryHiddenByScroll(false)
                    }
                }
            })
        }
    }

    private fun setupTopBar() {
        binding.btnPostNotice.applyClickAnimation { startActivity(Intent(requireContext(), PostNoticeActivity::class.java)) }
        binding.btnEmptyPost.applyClickAnimation { startActivity(Intent(requireContext(), PostNoticeActivity::class.java)) }
        binding.btnEmptyReset.applyClickAnimation { resetFiltersAndSearch() }
    }

    private fun resetFiltersAndSearch() {
        selectedFilter = NoticeFilter.ALL
        searchQuery = ""
        binding.etNoticeSearch.text?.clear()
        noticeAdapter.setSearchQuery("")
        setSearchMode(false)
        renderFeed()
    }

    private fun setupSearch() {
        binding.btnSearch.applyClickAnimation { setSearchMode(true) }
        binding.btnCloseSearch.applyClickAnimation {
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
        fetchedEngagementNoticeIds.clear()
        if (!binding.swipeRefresh.isRefreshing && allNotices.isEmpty()) {
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

                        fetchEngagementState(notices.map { it.id }.filter { it.isNotBlank() }.toSet())
                        renderFeed()
                        processAiSummary(notices)

                        // Show today's notices reminder banner (once per session)
                        if (!todayReminderShown) {
                            val todayStart = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            val todayCount = notices.count { notice ->
                                val ts = notice.timestamp?.toDate()?.time ?: 0L
                                ts >= todayStart
                            }
                            if (todayCount > 0) {
                                showTodayReminderBanner(todayCount)
                            }
                        }
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
            val wasEmpty = currentRenderedItems.isEmpty()
            val filterChanged = lastFilter != selectedFilter
            lastFilter = selectedFilter

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

                    if (wasEmpty || filterChanged) {
                        binding.rvNotices.scheduleLayoutAnimation()
                    }
                }
            } else {
                noticeAdapter.setEngagementState(buildEngagementState())
            }
            updateEmptyState(filteredItems.isEmpty())
        }
    }

    private fun showTodayReminderBanner(count: Int) {
        if (_binding == null || todayReminderShown) return
        todayReminderShown = true
        val banner = binding.layoutReminderBanner
        val label = if (count == 1) "notice" else "notices"
        binding.tvReminderText.text = "You have $count $label today"

        banner.visibility = android.view.View.VISIBLE
        val slideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down_in)
        banner.startAnimation(slideDown)

        val dismissAction = Runnable { dismissReminderBanner() }
        banner.tag = dismissAction
        banner.postDelayed(dismissAction, 4000L)

        binding.btnDismissReminder.setOnClickListener {
            banner.removeCallbacks(dismissAction)
            dismissReminderBanner()
        }
    }

    private fun dismissReminderBanner() {
        val banner = binding.layoutReminderBanner
        if (banner.visibility != android.view.View.VISIBLE) return
        val slideUp = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_out)
        slideUp.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) = Unit
            override fun onAnimationRepeat(a: android.view.animation.Animation?) = Unit
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                if (_binding != null) banner.visibility = android.view.View.GONE
            }
        })
        banner.startAnimation(slideUp)
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
        if (isAdmin) {
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
    }

    private fun copyNoticeToClipboard(notice: Notice) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val textToCopy = "Title: ${notice.title}\n\n${notice.content}"
        val clip = android.content.ClipData.newPlainText("Notice", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Notice copied to clipboard", Toast.LENGTH_SHORT).show()
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
                val user = doc.toObject(com.shuaib.classmate.models.User::class.java)
                isAdmin = user?.canPostNotices() ?: false
                binding.btnPostNotice.isVisible = isAdmin
                binding.btnEmptyPost.isVisible = isAdmin && allNotices.isEmpty() && allPolls.isEmpty()
            }
    }

    override fun onDestroyView() {
        fetchedEngagementNoticeIds.clear()
        binding.rvNotices.adapter = null
        currentRenderedItems = emptyList()
        _binding = null
        super.onDestroyView()
    }

    private fun fetchEngagementState(noticeIds: Set<String>) {
        if (_binding == null) return
        val toFetch = noticeIds - fetchedEngagementNoticeIds
        if (toFetch.isEmpty()) {
            noticeAdapter.setEngagementState(buildEngagementState())
            return
        }

        fetchedEngagementNoticeIds.addAll(toFetch)

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            toFetch.chunked(30).forEach { chunk ->
                try {
                    val likesTask = db.collection("notice_likes")
                        .whereIn("noticeId", chunk)
                        .get()
                    val sharesTask = db.collection("notice_shares")
                        .whereIn("noticeId", chunk)
                        .get()

                    val likesSnapshot = Tasks.await(likesTask)
                    val sharesSnapshot = Tasks.await(sharesTask)

                    if (_binding == null) return@forEach

                    val likeDocs = likesSnapshot.documents
                    val likeCounts = likeDocs.groupingBy { it.getString("noticeId").orEmpty() }.eachCount()
                    val userLikedIds = likeDocs.filter { it.getString("userId") == currentUserId }.mapNotNull { it.getString("noticeId") }.toSet()

                    val shareDocs = sharesSnapshot.documents
                    val shareCounts = shareDocs.groupingBy { it.getString("noticeId").orEmpty() }.eachCount()

                    launch(kotlinx.coroutines.Dispatchers.Main) {
                        if (_binding == null) return@launch
                        chunk.forEach { id ->
                            likeCountByNotice[id] = likeCounts[id] ?: 0
                            shareCountByNotice[id] = shareCounts[id] ?: 0
                        }
                        likedNoticeIds = likedNoticeIds + userLikedIds
                        noticeAdapter.setEngagementState(buildEngagementState())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NoticeFragment", "Failed to fetch engagement for chunk", e)
                    launch(kotlinx.coroutines.Dispatchers.Main) {
                        fetchedEngagementNoticeIds.removeAll(chunk.toSet())
                    }
                }
            }
        }
    }

    private fun buildEngagementState(): Map<String, NoticeEngagement> {
        return allNotices.associate { notice ->
            notice.id to NoticeEngagement(
                noticeId = notice.id,
                likeCount = likeCountByNotice[notice.id] ?: 0,
                commentCount = notice.discussionCount,
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

    private fun setupAiSummaryCard() {
        if (_binding == null) return
        val card = binding.aiSummaryCard
        
        // Make the entire card background clickable for a premium "one-click" experience
        card.cardAiSummary.setOnClickListener {
            toggleAiSummaryExpansion()
        }
        card.layoutAiHeader.setOnClickListener {
            toggleAiSummaryExpansion()
        }
        card.btnAiDismiss.setOnClickListener {
            dismissAiSummary()
        }
        card.btnAiRetry.setOnClickListener {
            generateAiSummary()
        }
        updateAiSummaryUiState()
    }

    private fun toggleAiSummaryExpansion() {
        if (_binding == null) return
        isSummaryCollapsed = !isSummaryCollapsed
        
        // Premium layout transitions for card height expansion/collapse
        val card = binding.aiSummaryCard.cardAiSummary
        val parent = card.parent as? ViewGroup
        if (parent != null) {
            androidx.transition.TransitionManager.beginDelayedTransition(parent)
        }
        
        updateAiSummaryUiState()
        if (!isSummaryCollapsed) {
            val hash = getTodayNoticesHash()
            val prefs = requireContext().getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)
            val cached = prefs.getString("summary_$hash", null)
            if (cached.isNullOrBlank()) {
                generateAiSummary()
            }
        }
    }

    private fun dismissAiSummary() {
        val hash = getTodayNoticesHash()
        if (hash.isNotEmpty()) {
            val prefs = requireContext().getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("dismissed_$hash", true).apply()
        }
        updateAiSummaryUiState()
    }

    private fun getTodayNoticesHash(): String {
        if (todayNoticesToSummarize.isEmpty()) return ""
        val latestTimestamp = todayNoticesToSummarize.maxOfOrNull { itemTimestampMillis(it) } ?: 0L
        return "${todayNoticesToSummarize.size}_$latestTimestamp"
    }

    private fun generateAiSummary() {
        if (todayNoticesToSummarize.isEmpty()) return
        val hash = getTodayNoticesHash()
        if (hash.isEmpty()) return
        val context = context ?: return
        
        val card = binding.aiSummaryCard
        card.layoutAiLoading.visibility = View.VISIBLE
        card.layoutAiError.visibility = View.GONE
        card.tvAiSummaryText.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val concatenatedText = todayNoticesToSummarize.joinToString("\n\n") { notice ->
                    "Title: ${notice.title}\nContent: ${notice.body}"
                }
                val result = com.shuaib.classmate.services.AIService.summarizeNotice(
                    title = "Today's Updates",
                    content = concatenatedText,
                    type = "General",
                    subject = "Notice Feed Summary"
                )
                if (_binding == null) return@launch
                if (result.isSuccess) {
                    val summary = result.getOrThrow()
                    val prefs = context.getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("summary_$hash", summary).apply()
                    updateAiSummaryUiState()
                } else {
                    val exception = result.exceptionOrNull()
                    val errorMsg = exception?.message ?: "Failed to generate summary."
                    showAiSummaryError(errorMsg)
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                showAiSummaryError(e.message ?: "Unknown error occurred.")
            }
        }
    }

    private fun showAiSummaryError(errorMsg: String) {
        if (_binding == null) return
        val card = binding.aiSummaryCard
        card.layoutAiLoading.visibility = View.GONE
        card.layoutAiError.visibility = View.VISIBLE
        card.tvAiErrorMsg.text = errorMsg
        card.tvAiSummaryText.visibility = View.GONE
    }

    private fun processAiSummary(notices: List<Notice>) {
        val today = notices.filter { NoticeUi.isToday(itemTimestampMillis(it)) }
        todayNoticesToSummarize = today
        updateAiSummaryUiState()
    }

    private fun updateAiSummaryUiState() {
        if (_binding == null) return
        val card = binding.aiSummaryCard
        val hash = getTodayNoticesHash()
        
        // Reset error/loading state if hash changes so we don't get stuck in a stale error state
        if (hash.isNotEmpty() && hash != lastProcessedHash) {
            lastProcessedHash = hash
            card.layoutAiLoading.visibility = View.GONE
            card.layoutAiError.visibility = View.GONE
            card.tvAiSummaryText.visibility = View.VISIBLE
        }

        val isCardVisible = todayNoticesToSummarize.isNotEmpty() && run {
            val prefs = requireContext().getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)
            !prefs.getBoolean("dismissed_$hash", false)
        }

        // Dynamically adjust RecyclerView top padding to avoid empty space when card is hidden/dismissed
        val density = resources.displayMetrics.density
        val topPadding = if (isCardVisible) (128 * density).toInt() else (72 * density).toInt()
        binding.rvNotices.setPadding(
            binding.rvNotices.paddingLeft,
            topPadding + statusBarHeight,
            binding.rvNotices.paddingRight,
            binding.rvNotices.paddingBottom
        )

        // Dynamically adjust reminder banner topMargin to float below the transparent status bar + header
        val bannerParams = binding.layoutReminderBanner.layoutParams as? ViewGroup.MarginLayoutParams
        if (bannerParams != null) {
            bannerParams.topMargin = (72 * density).toInt() + statusBarHeight
            binding.layoutReminderBanner.layoutParams = bannerParams
        }

        // Dynamically adjust shimmer and empty state top padding
        binding.shimmerView.setPadding(
            binding.shimmerView.paddingLeft,
            (72 * density).toInt() + statusBarHeight,
            binding.shimmerView.paddingRight,
            binding.shimmerView.paddingBottom
        )
        binding.emptyNoticeState.setPadding(
            binding.emptyNoticeState.paddingLeft,
            (72 * density).toInt() + statusBarHeight,
            binding.emptyNoticeState.paddingRight,
            binding.emptyNoticeState.paddingBottom
        )

        if (!isCardVisible) {
            card.cardAiSummary.visibility = View.GONE
            return
        }

        card.cardAiSummary.visibility = View.VISIBLE
        
        // Dynamically adjust AI summary card topMargin to float below the transparent status bar + header
        val params = card.cardAiSummary.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            params.topMargin = (72 * density).toInt() + statusBarHeight
            card.cardAiSummary.layoutParams = params
        }
        
        // Restore correct translation and alpha based on scroll state
        if (isAiSummaryHiddenByScroll) {
            card.cardAiSummary.alpha = 0f
            card.cardAiSummary.post {
                val topMargin = (card.cardAiSummary.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                card.cardAiSummary.translationY = -(card.cardAiSummary.height + topMargin).toFloat()
            }
        } else {
            card.cardAiSummary.alpha = 1f
            card.cardAiSummary.translationY = 0f
        }

        val prefs = requireContext().getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)

        if (isSummaryCollapsed) {
            card.layoutAiContent.visibility = View.GONE
            card.ivAiChevron.rotation = 0f
            card.btnAiActionText.text = "Expand"
            card.btnAiActionText.visibility = View.VISIBLE
        } else {
            card.layoutAiContent.visibility = View.VISIBLE
            card.ivAiChevron.rotation = 90f
            card.btnAiActionText.text = "Collapse"
            card.btnAiActionText.visibility = View.VISIBLE
            val cachedSummary = prefs.getString("summary_$hash", null)
            if (cachedSummary != null) {
                card.layoutAiLoading.visibility = View.GONE
                card.layoutAiError.visibility = View.GONE
                card.tvAiSummaryText.visibility = View.VISIBLE
                val context = context
                if (context != null) {
                    val formatted = com.shuaib.classmate.notices.NoticeTextFormatter.format(context, cachedSummary)
                    val spannable = android.text.SpannableStringBuilder(formatted)
                    android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                    card.tvAiSummaryText.text = spannable
                    card.tvAiSummaryText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                } else {
                    val spannable = android.text.SpannableStringBuilder(cachedSummary)
                    android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
                    card.tvAiSummaryText.text = spannable
                    card.tvAiSummaryText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            } else {
                if (card.layoutAiLoading.visibility != View.VISIBLE && card.layoutAiError.visibility != View.VISIBLE) {
                    card.layoutAiLoading.visibility = View.GONE
                    card.layoutAiError.visibility = View.GONE
                    card.tvAiSummaryText.visibility = View.VISIBLE
                    generateAiSummary()
                }
            }
        }
    }

    private fun setAiSummaryHiddenByScroll(hidden: Boolean) {
        if (_binding == null || isAiSummaryHiddenByScroll == hidden) return
        
        val card = binding.aiSummaryCard.cardAiSummary
        if (todayNoticesToSummarize.isEmpty()) return
        val hash = getTodayNoticesHash()
        val prefs = requireContext().getSharedPreferences("ai_summary_prefs", Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean("dismissed_$hash", false)
        if (isDismissed) return

        isAiSummaryHiddenByScroll = hidden

        val topMargin = (card.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val offset = if (hidden) -(card.height + topMargin).toFloat() else 0f
        
        card.animate()
            .translationY(offset)
            .alpha(if (hidden) 0f else 1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
    }

    private fun adjustHeaderOnScroll(scrollOffset: Int) {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val scrollLimit = 80 * density
        val fraction = (scrollOffset.toFloat() / scrollLimit).coerceIn(0f, 1f)

        // Title scaling
        val scale = 1.0f - (0.2f * fraction)
        binding.tvNoticeTitle.pivotX = 0f
        if (binding.tvNoticeTitle.height > 0) {
            binding.tvNoticeTitle.pivotY = binding.tvNoticeTitle.height.toFloat() / 2f
        }
        binding.tvNoticeTitle.scaleX = scale
        binding.tvNoticeTitle.scaleY = scale
        binding.tvNoticeTitle.translationY = (6 * density) * fraction

        // Subtitle collapsing & fade
        val subtitleAlpha = 1.0f - fraction
        binding.tvNoticeSubtitle.pivotY = 0f
        binding.tvNoticeSubtitle.alpha = subtitleAlpha
        binding.tvNoticeSubtitle.scaleY = subtitleAlpha
        binding.tvNoticeSubtitle.translationY = -(6 * density) * fraction

        // Buttons and search container scaling
        val buttonScale = 1.0f - (0.15f * fraction)
        if (binding.btnSearch.width > 0) {
            binding.btnSearch.pivotX = binding.btnSearch.width.toFloat() / 2f
            binding.btnSearch.pivotY = binding.btnSearch.height.toFloat() / 2f
        }
        binding.btnSearch.scaleX = buttonScale
        binding.btnSearch.scaleY = buttonScale

        if (binding.btnPostNotice.width > 0) {
            binding.btnPostNotice.pivotX = binding.btnPostNotice.width.toFloat()
            binding.btnPostNotice.pivotY = binding.btnPostNotice.height.toFloat() / 2f
        }
        binding.btnPostNotice.scaleX = buttonScale
        binding.btnPostNotice.scaleY = buttonScale

        if (binding.searchContainer.width > 0) {
            binding.searchContainer.pivotX = binding.searchContainer.width.toFloat()
            binding.searchContainer.pivotY = binding.searchContainer.height.toFloat() / 2f
        }
        binding.searchContainer.scaleX = buttonScale
        binding.searchContainer.scaleY = buttonScale

        // Dynamic padding adjustment
        val baseTopPadding = (12 * density).toInt()
        val targetTopPadding = (4 * density).toInt()
        val currentTopPadding = baseTopPadding - ((baseTopPadding - targetTopPadding) * fraction).toInt()

        val baseBottomPadding = (6 * density).toInt()
        val targetBottomPadding = (2 * density).toInt()
        val currentBottomPadding = baseBottomPadding - ((baseBottomPadding - targetBottomPadding) * fraction).toInt()

        binding.noticeHeaderPanel.setPadding(
            binding.noticeHeaderPanel.paddingLeft,
            statusBarHeight + currentTopPadding,
            binding.noticeHeaderPanel.paddingRight,
            currentBottomPadding
        )
    }

    private fun markNoticeAsRead(notice: Notice) {
        if (notice.id.isBlank() || currentUserId.isBlank()) return
        if (readNoticeCache.contains(notice.id)) return

        val context = context ?: return
        val prefs = context.getSharedPreferences("classmate_reads", Context.MODE_PRIVATE)
        val readSet = prefs.getStringSet("read_notices", emptySet()) ?: emptySet()
        if (readSet.contains(notice.id)) {
            readNoticeCache.add(notice.id)
            return
        }

        readNoticeCache.add(notice.id)
        val newReadSet = readSet.toMutableSet()
        newReadSet.add(notice.id)
        prefs.edit().putStringSet("read_notices", newReadSet).apply()

        val receiptRef = db.collection("notices").document(notice.id)
            .collection("read_receipts").document(currentUserId)

        val receiptData = hashMapOf(
            "userId" to currentUserId,
            "userName" to currentUserName.ifBlank { "Student" },
            "studentId" to currentUserStudentId.ifBlank { "N/A" },
            "readAt" to com.google.firebase.Timestamp.now()
        )

        val noticeRef = db.collection("notices").document(notice.id)

        db.runBatch { batch ->
            batch.set(receiptRef, receiptData)
            batch.update(noticeRef, "readCount", FieldValue.increment(1))
        }.addOnFailureListener { e ->
            Log.e("NoticeFragment", "Failed to log read receipt", e)
        }
    }

    private fun showReadReceipts(notice: Notice) {
        val context = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val dialogView = layoutInflater.inflate(R.layout.dialog_read_receipts, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val pbLoading = dialogView.findViewById<ProgressBar>(R.id.pbLoading)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmpty)
        val rvReaders = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvReaders)

        tvTitle.text = "Read Receipts (${notice.readCount})"
        rvReaders.layoutManager = LinearLayoutManager(context)

        db.collection("notices").document(notice.id)
            .collection("read_receipts")
            .orderBy("readAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                pbLoading.visibility = View.GONE
                val documents = snapshot.documents
                if (documents.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvReaders.visibility = View.VISIBLE
                    rvReaders.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ReaderViewHolder>() {
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReaderViewHolder {
                            val v = layoutInflater.inflate(R.layout.item_read_receipt, parent, false)
                            return ReaderViewHolder(v)
                        }

                        override fun onBindViewHolder(holder: ReaderViewHolder, position: Int) {
                            val doc = documents[position]
                            val name = doc.getString("userName").orEmpty()
                            val studentId = doc.getString("studentId").orEmpty()
                            val readAt = doc.getTimestamp("readAt")

                            holder.tvName.text = name
                            holder.tvInfo.text = if (studentId.isNotBlank() && studentId != "N/A") "ID: $studentId" else "Student"
                            
                            if (readAt != null) {
                                val sdf = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault())
                                holder.tvTime.text = sdf.format(readAt.toDate())
                            } else {
                                holder.tvTime.text = "Just now"
                            }
                        }

                        override fun getItemCount(): Int = documents.size
                    }
                }
            }
            .addOnFailureListener { e ->
                pbLoading.visibility = View.GONE
                tvEmpty.text = "Failed to load read receipts."
                tvEmpty.visibility = View.VISIBLE
                Log.e("NoticeFragment", "Failed to fetch read receipts", e)
            }

        dialog.show()
    }
}

class ReaderViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
    val tvName: TextView = view.findViewById(R.id.tvReaderName)
    val tvInfo: TextView = view.findViewById(R.id.tvReaderInfo)
    val tvTime: TextView = view.findViewById(R.id.tvReadTime)
}