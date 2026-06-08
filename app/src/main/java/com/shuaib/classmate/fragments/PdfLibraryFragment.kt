package com.shuaib.classmate.fragments

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.activities.PdfUploadActivity
import com.shuaib.classmate.adapters.RecentPdfAdapter
import com.shuaib.classmate.adapters.OfflinePdfAdapter
import com.shuaib.classmate.adapters.SubjectAdapter
import com.shuaib.classmate.databinding.DialogLibraryUploadNotificationsBinding
import com.shuaib.classmate.databinding.DialogPdfOptionsBinding
import com.shuaib.classmate.databinding.FragmentPdfLibraryBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryDownloadManager
import com.shuaib.classmate.storage.LibraryUrlOpener
import com.shuaib.classmate.utils.NetworkMonitor
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.LibrarySystemBars
import com.shuaib.classmate.utils.PdfDialogHelper
import com.shuaib.classmate.utils.Subject
import com.shuaib.classmate.utils.SubjectList
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Locale

class PdfLibraryFragment : Fragment() {

    private var _binding: FragmentPdfLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var regularAdapter: SubjectAdapter
    private lateinit var labAdapter: SubjectAdapter
    private lateinit var otherAdapter: SubjectAdapter
    private lateinit var recentAdapter: RecentPdfAdapter
    private lateinit var offlineAdapter: OfflinePdfAdapter
    private var offlinePdfs = emptyList<PdfFile>()
    private var allSubjects = emptyList<Subject>()
    private var regularSubjects = emptyList<Subject>()
    private var labSubjects = emptyList<Subject>()
    private var otherSubjects = emptyList<Subject>()
    private var allPdfs = emptyList<PdfFile>()
    private var recentPdfs = emptyList<PdfFile>()
    private var favoritePdfIds = emptySet<String>()
    private var pdfCounts = emptyMap<String, Int>()
    private var isAdmin = false
    private var previousStatusBarColor: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPdfLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupSections()
        setupHeaderActions()
        checkAdminAccess()

        binding.swipeRefresh.setOnRefreshListener {
            loadLibraryData()
        }

        binding.btnUploadPdf.setOnClickListener {
            startActivity(Intent(requireContext(), PdfUploadActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        previousStatusBarColor = requireActivity().window.statusBarColor
        LibrarySystemBars.apply(requireActivity().window)
        checkAdminAccess()
    }

    override fun onPause() {
        (activity as? MainActivity)?.setMainPageSwipeEnabled(true)
        previousStatusBarColor?.let { requireActivity().window.statusBarColor = it }
        super.onPause()
    }

    private fun setupSections() {
        allSubjects = SubjectList.subjects
        labSubjects = allSubjects.filter { it.name.trim().lowercase().endsWith("lab") }
        otherSubjects = allSubjects.filter {
            val name = it.name.trim().lowercase()
            name.contains("other") || name.contains("viva")
        }
        regularSubjects = allSubjects.filter { subject ->
            subject !in labSubjects && subject !in otherSubjects
        }

        recentAdapter = RecentPdfAdapter(recentPdfs, isAdmin, favoritePdfIds) { pdf -> handleResourceAction(pdf) }
        recentAdapter.onDeleteClick = { pdf -> showDeleteConfirmation(pdf) }
        recentAdapter.onFavoriteClick = { pdf -> togglePdfFavorite(pdf) }

        offlineAdapter = OfflinePdfAdapter(
            emptyList(),
            onItemClick = { pdf -> handleResourceAction(pdf) },
            onDeleteClick = { pdf -> showDeleteCacheConfirmation(pdf) }
        )

        binding.rvRecent.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
            lockParentSwipeWhileTouching(this)
        }
        binding.rvOffline.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = offlineAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
        }

        regularAdapter = SubjectAdapter(regularSubjects, pdfCounts) { subject -> navigateToPdfs(subject) }
        labAdapter = SubjectAdapter(labSubjects, pdfCounts) { subject -> navigateToPdfs(subject) }
        otherAdapter = SubjectAdapter(otherSubjects, pdfCounts) { subject -> navigateToPdfs(subject) }

        binding.rvRegular.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = regularAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
            lockParentSwipeWhileTouching(this)
        }
        binding.rvLab.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = labAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
            lockParentSwipeWhileTouching(this)
        }
        binding.rvOther.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = otherAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
            lockParentSwipeWhileTouching(this)
        }

        binding.headerRegular.applyClickAnimation {
            toggleSection(binding.rvRegular, binding.ivArrowRegular)
        }
        binding.headerLab.applyClickAnimation {
            toggleSection(binding.rvLab, binding.ivArrowLab)
        }
        binding.headerOther.applyClickAnimation {
            toggleSection(binding.rvOther, binding.ivArrowOther)
        }
        binding.tvCollapseAll.applyClickAnimation {
            onGlobalExpandCollapseClicked()
        }
        binding.tvViewAll.applyClickAnimation {
            (activity as? MainActivity)?.openChildDestination(R.id.nav_pdf, R.id.fragment_library_all_files)
        }
        updateLibraryView()
    }

    private fun setupHeaderActions() {
        binding.btnLibrarySearch.applyClickAnimation {
            (activity as? MainActivity)?.openChildDestination(R.id.nav_pdf, R.id.fragment_library_search)
        }
        binding.btnLibraryNotifications.applyClickAnimation {
            showUploadNotifications()
        }
    }

    private fun loadLibraryData() {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = true

        fetchFavoritePdfIds()

        db.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener

                allPdfs = snapshot.documents.map { doc -> doc.toPdfFile() }
                    .filterNot { it.isDeleted }
                    .sortedByDescending { it.timestamp ?: it.createdAt }

                updateLibraryView()
                binding.swipeRefresh.isRefreshing = false
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                binding.swipeRefresh.isRefreshing = false
                binding.tvRecentEmpty.isVisible = true
                binding.rvRecent.isVisible = false
                Toast.makeText(context, "Failed to load library: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateLibraryView() {
        if (_binding == null || !::regularAdapter.isInitialized || !::labAdapter.isInitialized || !::otherAdapter.isInitialized) return
        refreshOfflineSection()

        val recentThreshold = System.currentTimeMillis() - (48 * 60 * 60 * 1000)
        val hasRecentUploads = allPdfs.any { pdf ->
            val uploadTime = (pdf.timestamp ?: pdf.createdAt)?.toDate()?.time ?: 0L
            uploadTime > recentThreshold
        }
        binding.libraryNotificationDot.isVisible = hasRecentUploads

        val filteredPdfs = allPdfs
        pdfCounts = filteredPdfs.groupingBy { it.subject }.eachCount()
        val knownSubjectNames = allSubjects.map { it.name }.toSet()
        val dynamicOtherSubjects = allPdfs
            .filter { it.subject.isNotBlank() && it.subject !in knownSubjectNames }
            .distinctBy { it.subject }
            .map { Subject(it.subject, it.courseCode.ifBlank { "LIB0000" }) }
        otherSubjects = (allSubjects.filter {
            val name = it.name.trim().lowercase()
            name.contains("other") || name.contains("viva")
        } + dynamicOtherSubjects).distinctBy { it.name }

        recentPdfs = filteredPdfs.take(8)
        recentAdapter.updateList(recentPdfs, isAdmin, favoritePdfIds)
        binding.rvRecent.isVisible = recentPdfs.isNotEmpty()
        binding.tvRecentEmpty.isVisible = recentPdfs.isEmpty()

        val filteredRegular = regularSubjects
        val filteredLab = labSubjects
        val filteredOther = otherSubjects

        regularAdapter.updateList(filteredRegular, pdfCounts)
        labAdapter.updateList(filteredLab, pdfCounts)
        otherAdapter.updateList(filteredOther, pdfCounts)
        binding.rvRegular.scheduleLayoutAnimation()
        binding.rvLab.scheduleLayoutAnimation()
        binding.rvOther.scheduleLayoutAnimation()

        binding.tvRegularCount.text = filteredRegular.size.toString()
        binding.tvLabCount.text = filteredLab.size.toString()
        binding.tvOtherCount.text = filteredOther.size.toString()
        binding.tvRegularMeta.text = "${subjectCountText(filteredRegular.size)} - ${resourceCountText(resourceCountFor(filteredRegular, filteredPdfs))}"
        binding.tvLabMeta.text = "${subjectCountText(filteredLab.size)} - ${resourceCountText(resourceCountFor(filteredLab, filteredPdfs))}"
        binding.tvOtherMeta.text = "${subjectCountText(filteredOther.size)} - ${resourceCountText(resourceCountFor(filteredOther, filteredPdfs))}"
        binding.tvLibrarySummary.text = "${resourceCountText(filteredPdfs.size)} across ${subjectCountText(pdfCounts.size)}"

        val hasRegularResults = filteredRegular.isNotEmpty()
        val hasLabResults = filteredLab.isNotEmpty()
        val hasOtherResults = filteredOther.isNotEmpty()
        binding.headerRegular.isVisible = hasRegularResults
        binding.headerLab.isVisible = hasLabResults
        binding.headerOther.isVisible = hasOtherResults
        binding.tvNoSubjectResults.isVisible = !hasRegularResults && !hasLabResults && !hasOtherResults

        updateGlobalExpandButton()
    }

    private fun resourceCountFor(subjects: List<Subject>, pdfs: List<PdfFile>): Int {
        val names = subjects.map { it.name }.toSet()
        return pdfs.count { it.subject in names }
    }

    private fun subjectCountText(count: Int): String {
        return "$count ${if (count == 1) "subject" else "subjects"}"
    }

    private fun resourceCountText(count: Int): String {
        return "$count ${if (count == 1) "resource" else "resources"}"
    }

    private fun toggleSection(recyclerView: View, arrow: View) {
        val isExpanding = !recyclerView.isVisible
        setSectionExpanded(recyclerView, arrow, isExpanding)
        updateGlobalExpandButton()
    }

    private fun setSectionExpanded(recyclerView: View, arrow: View, expanded: Boolean) {
        if (recyclerView.isVisible == expanded) {
            arrow.rotation = if (expanded) -90f else 0f
            return
        }
        recyclerView.isVisible = expanded
        if (expanded) {
            recyclerView.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_slide_in))
        }
        arrow.animate().rotation(if (expanded) -90f else 0f).setDuration(180).start()
    }

    private fun updateGlobalExpandButton() {
        if (_binding == null) return

        val sections = mutableListOf<View>()
        if (binding.headerRegular.isVisible) sections.add(binding.rvRegular)
        if (binding.headerLab.isVisible) sections.add(binding.rvLab)
        if (binding.headerOther.isVisible) sections.add(binding.rvOther)

        if (sections.isEmpty()) {
            binding.tvCollapseAll.isVisible = false
            return
        }

        binding.tvCollapseAll.isVisible = true
        val allExpanded = sections.all { it.isVisible }
        binding.tvCollapseAll.text = if (allExpanded) "Collapse All" else "Expand All"
    }

    private fun onGlobalExpandCollapseClicked() {
        val sections = mutableListOf<Pair<View, View>>()
        if (binding.headerRegular.isVisible) sections.add(binding.rvRegular to binding.ivArrowRegular)
        if (binding.headerLab.isVisible) sections.add(binding.rvLab to binding.ivArrowLab)
        if (binding.headerOther.isVisible) sections.add(binding.rvOther to binding.ivArrowOther)

        if (sections.isEmpty()) return

        val allExpanded = sections.all { it.first.isVisible }
        val shouldExpand = !allExpanded

        sections.forEach { (rv, arrow) ->
            setSectionExpanded(rv, arrow, shouldExpand)
        }
        updateGlobalExpandButton()
    }

    private fun navigateToPdfs(subject: Subject) {
        (activity as? MainActivity)?.openChildDestination(
            R.id.nav_pdf,
            R.id.fragment_subject_pdf_list,
            Bundle().apply { putString("subjectName", subject.name) }
        )
    }

    private fun refreshOfflineSection() {
        if (_binding == null || !::offlineAdapter.isInitialized) return
        offlinePdfs = LibraryDownloadManager.getDownloadedFiles(requireContext())
        offlineAdapter.updateList(offlinePdfs)
        val shouldShow = offlinePdfs.isNotEmpty()
        binding.offlineHeader.isVisible = shouldShow
        binding.rvOffline.isVisible = shouldShow
        if (shouldShow) binding.rvOffline.scheduleLayoutAnimation()
    }

    private fun handleResourceAction(pdf: PdfFile) {
        if (LibraryDownloadManager.isDownloaded(requireContext(), pdf.id) && !NetworkMonitor.isOnline(requireContext())) {
            LibraryDownloadManager.openLocalFile(requireContext(), pdf)
            return
        }
        showPdfOptions(pdf)
    }

    private fun showPdfOptions(pdf: PdfFile) {
        PdfDialogHelper.showPdfOptions(requireActivity(), requireContext(), pdf) {
            refreshOfflineSection()
        }
    }

    private fun showDeleteCacheConfirmation(pdf: PdfFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Offline Copy")
            .setMessage("Are you sure you want to remove \"${pdf.title}\" from your device? You will need internet connection to view it again.")
            .setPositiveButton("Remove") { _, _ ->
                LibraryDownloadManager.deleteDownload(requireContext(), pdf.id)
                Toast.makeText(requireContext(), "Offline copy removed", Toast.LENGTH_SHORT).show()
                refreshOfflineSection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUploadNotifications() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogLibraryUploadNotificationsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.show()

        db.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                val files = snapshot.documents.map { it.toPdfFile() }
                    .filterNot { it.isDeleted }
                    .sortedByDescending { it.timestamp ?: it.createdAt }
                    .take(20)
                updateNotificationSheet(dialogBinding, files, dialog)
            }
            .addOnFailureListener { e ->
                handleNotificationError(e, dialogBinding)
            }
    }

    private fun updateNotificationSheet(
        dialogBinding: DialogLibraryUploadNotificationsBinding,
        files: List<PdfFile>,
        dialog: BottomSheetDialog
    ) {
        dialogBinding.progressNotifications.isVisible = false
        dialogBinding.tvUploadNotificationMeta.text = "${files.size} recent upload ${if (files.size == 1) "update" else "updates"}"
        dialogBinding.tvEmptyNotifications.isVisible = files.isEmpty()
        dialogBinding.uploadNotificationContainer.removeAllViews()
        files.forEach { file ->
            dialogBinding.uploadNotificationContainer.addView(createUploadFileRow(file, dialog))
        }
    }

    private fun handleNotificationError(e: Exception, dialogBinding: DialogLibraryUploadNotificationsBinding) {
        dialogBinding.progressNotifications.isVisible = false
        dialogBinding.tvEmptyNotifications.isVisible = true
        dialogBinding.tvEmptyNotifications.text = "Upload notifications are being prepared. Please try again later."
        Log.e("LibraryNotificationsDebug", "Notification error: ${e.message}", e)
    }

    private fun createUploadFileRow(file: PdfFile, dialog: BottomSheetDialog): View {
        val context = requireContext()
        val visuals = FileVisuals.getVisuals(file)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_library_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val iconConstraint = androidx.constraintlayout.widget.ConstraintLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(12)
            }
        }

        val iconFrame = FrameLayout(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            background = ContextCompat.getDrawable(context, visuals.backgroundRes)
        }
        val icon = ImageView(context).apply {
            setImageResource(visuals.iconRes)
            setColorFilter(visuals.tint)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        }
        iconFrame.addView(icon)
        iconConstraint.addView(iconFrame)

        val badge = TextView(context).apply {
            text = visuals.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ThemeColors.textPrimary(context))
            background = roundedDrawable(visuals.tint, 6f)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomToBottom = iconFrame.id
            params.endToEnd = iconFrame.id
            layoutParams = params
        }
        iconConstraint.addView(badge)

        val uploadTime = (file.timestamp ?: file.createdAt)?.toDate()?.time ?: 0L
        val isNew = uploadTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        if (isNew) {
            val dot = View(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_library_notification_dot)
                val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(dp(10), dp(10))
                params.topToTop = iconFrame.id
                params.endToEnd = iconFrame.id
                params.topMargin = dp(2)
                params.marginEnd = dp(2)
                layoutParams = params
            }
            iconConstraint.addView(dot)
        }

        row.addView(iconConstraint)

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = file.title.ifBlank { "New library upload" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ThemeColors.onPrimary(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (FileVisuals.isLabResource(file)) {
            titleRow.addView(TextView(context).apply {
                text = "LAB"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ThemeColors.success(context))
                background = ContextCompat.getDrawable(context, R.drawable.bg_library_badge_green)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                }
            })
        }
        textColumn.addView(titleRow)

        textColumn.addView(TextView(context).apply {
            val subject = file.subject.ifBlank { "Library" }
            val uploader = file.uploadedBy.ifBlank { "Admin" }
            text = "$subject - $uploader - ${formatFileTime(file)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ThemeColors.textMuted(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textColumn)

        row.applyClickAnimation {
            dialog.dismiss()
            LibraryUrlOpener.open(requireContext(), file)
        }
        return row
    }

    private fun togglePdfFavorite(pdf: PdfFile) {
        val uid = auth.currentUser?.uid ?: return
        val isNowFavorite = !favoritePdfIds.contains(pdf.id)
        val wasFavoritePdfIds = favoritePdfIds

        if (isNowFavorite) {
            favoritePdfIds = favoritePdfIds + pdf.id
            db.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayUnion(pdf.id))
                .addOnFailureListener {
                    favoritePdfIds = wasFavoritePdfIds
                    recentAdapter.updateList(recentPdfs, isAdmin, favoritePdfIds)
                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                }
        } else {
            favoritePdfIds = favoritePdfIds - pdf.id
            db.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayRemove(pdf.id))
                .addOnFailureListener {
                    favoritePdfIds = wasFavoritePdfIds
                    recentAdapter.updateList(recentPdfs, isAdmin, favoritePdfIds)
                    Toast.makeText(context, "Remove failed", Toast.LENGTH_SHORT).show()
                }
        }
        recentAdapter.updateList(recentPdfs, isAdmin, favoritePdfIds)
    }

    private fun fetchFavoritePdfIds() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                favoritePdfIds = (doc.get("favoritePdfIds") as? List<String> ?: emptyList()).toSet()
                if (_binding != null) {
                    recentAdapter.updateList(recentPdfs, isAdmin, favoritePdfIds)
                }
            }
    }

    private fun formatFileTime(file: PdfFile): String {
        val timestamp = file.timestamp ?: file.createdAt ?: return "just now"
        return DateUtils.getRelativeTimeSpanString(
            timestamp.toDate().time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return if (unit == 0) "${bytes}B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun lockParentSwipeWhileTouching(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        recyclerView.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        (activity as? MainActivity)?.setMainPageSwipeEnabled(false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                        (activity as? MainActivity)?.setMainPageSwipeEnabled(true)
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid ?: run {
            isAdmin = false
            binding.btnUploadPdf.isVisible = false
            loadLibraryData()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                val canUploadPdf = doc.getBoolean("permissions.canUploadPDF") ?: false
                val canUploadLibrary = doc.getBoolean("permissions.canUploadLibrary") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canUploadPdf || canUploadLibrary
                binding.btnUploadPdf.isVisible = isAdmin
                loadLibraryData()
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                isAdmin = false
                binding.btnUploadPdf.isVisible = false
                loadLibraryData()
            }
    }

    private fun showDeleteConfirmation(pdf: PdfFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Material")
            .setMessage("Are you sure you want to delete \"${pdf.title}\"?")
            .setPositiveButton("Delete") { _, _ -> deletePdf(pdf) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePdf(pdf: PdfFile) {
        db.collection("library_files").document(pdf.id)
            .update(
                mapOf(
                    "isDeleted" to true,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
                loadLibraryData()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun DocumentSnapshot.toPdfFile(): PdfFile {
        return PdfFile(
            id = id,
            title = getString("title") ?: "No Title",
            subject = getString("subject") ?: "",
            description = getString("description") ?: "",
            uploadedBy = getString("uploadedByName") ?: getString("uploadedBy") ?: "",
            telegramUrl = getString("telegramUrl") ?: "",
            driveUrl = getString("driveUrl") ?: "",
            fileId = getString("fileId") ?: "",
            timestamp = getTimestamp("timestamp") ?: getTimestamp("createdAt"),
            courseCode = getString("courseCode") ?: "",
            courseType = getString("courseType") ?: "",
            fileType = getString("fileType") ?: "other",
            mimeType = getString("mimeType") ?: "application/octet-stream",
            sizeBytes = getLong("sizeBytes") ?: 0L,
            provider = getString("provider") ?: "",
            downloadUrl = getString("downloadUrl") ?: "",
            githubAssetId = getLong("githubAssetId") ?: 0L,
            githubAssetName = getString("githubAssetName") ?: getString("title") ?: "",
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt"),
            downloadCount = getLong("downloadCount") ?: 0L,
            isDeleted = getBoolean("isDeleted") ?: false
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(color: Int, radiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
