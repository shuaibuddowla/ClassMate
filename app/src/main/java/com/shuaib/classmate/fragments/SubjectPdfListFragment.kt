package com.shuaib.classmate.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.activities.PdfUploadActivity
import com.shuaib.classmate.adapters.CourseFileAdapter
import com.shuaib.classmate.databinding.FragmentSubjectPdfListBinding
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.repositories.ArchiveLibraryRepository
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.LibraryPermissions
import com.shuaib.classmate.utils.LibrarySystemBars
import com.shuaib.classmate.utils.PdfDialogHelper
import com.shuaib.classmate.utils.SubjectList
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation

class SubjectPdfListFragment : Fragment() {

    private var _binding: FragmentSubjectPdfListBinding? = null
    private val binding get() = _binding!!

    private val args: SubjectPdfListFragmentArgs by navArgs()
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var courseFileAdapter: CourseFileAdapter
    private var allResources = emptyList<PdfFile>()
    private var matchedCourse: Course? = null
    private var selectedFilter = Filter.All
    private var isAdmin = false
    private var isFavorite = false
    private var favoritePdfIds = emptySet<String>()
    private var previousStatusBarColor: Int? = null

    private enum class Filter(val label: String) {
        All("All"),
        Slides("Slides"),
        Notes("Notes"),
        Questions("Questions"),
        Assignments("Assignments"),
        Lab("Lab"),
        Starred("Starred")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubjectPdfListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupHeader()
        renderFilters()
        setupListeners()
        checkAdminAccess {
            setupRecyclerView()
            fetchPdfs()
        }

        binding.swipeRefresh.setOnRefreshListener { fetchPdfs() }
    }

    override fun onResume() {
        super.onResume()
        previousStatusBarColor = requireActivity().window.statusBarColor
        LibrarySystemBars.apply(requireActivity().window)
        // Refresh when returning (e.g., after an upload)
        if (::courseFileAdapter.isInitialized && !binding.shimmerView.isShimmerStarted) {
            fetchPdfs(showShimmer = false)
        }
    }

    override fun onPause() {
        (activity as? MainActivity)?.setMainPageSwipeEnabled(true)
        previousStatusBarColor?.let { requireActivity().window.statusBarColor = it }
        super.onPause()
    }

    private fun setupHeader() {
        binding.tvSubjectTitle.text = args.subjectName
        binding.tvCourseCodeBadge.text = "${subjectCode()} • THEORY"
        binding.tvResourceStats.text = "Loading resources..."
        binding.layoutInstructor.isVisible = false

        binding.btnBack.applyClickAnimation {
            findNavController().navigateUp()
        }

        checkFavoriteStatus()

        binding.btnFavorite.applyClickAnimation {
            toggleCourseFavorite()
        }
    }

    private fun setupListeners() {
        binding.fabUploadFile.applyClickAnimation {
            openUploadScreen()
        }

        binding.btnEmptyUpload.applyClickAnimation {
            openUploadScreen()
        }
    }

    private fun openUploadScreen() {
        val intent = Intent(requireContext(), PdfUploadActivity::class.java).apply {
            putExtra("subject", args.subjectName)
            putExtra("courseCode", matchedCourse?.code ?: subjectCode())
        }
        startActivity(intent)
    }

    private fun checkFavoriteStatus() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val favorites = doc.get("favoriteSubjects") as? List<String> ?: emptyList()
                isFavorite = favorites.contains(args.subjectName)
                updateFavoriteIcon()

                favoritePdfIds = (doc.get("favoritePdfIds") as? List<String> ?: emptyList()).toSet()
                if (::courseFileAdapter.isInitialized) {
                    val filtered = allResources.filter { matchesFilter(it) }
                    courseFileAdapter.updateList(filtered, favoritePdfIds)
                }
            }
    }

    private fun toggleCourseFavorite() {
        val uid = auth.currentUser?.uid ?: return
        val wasFavorite = isFavorite
        isFavorite = !isFavorite
        updateFavoriteIcon()

        val task = if (isFavorite) {
            db.collection("users").document(uid)
                .update("favoriteSubjects", com.google.firebase.firestore.FieldValue.arrayUnion(args.subjectName))
        } else {
            db.collection("users").document(uid)
                .update("favoriteSubjects", com.google.firebase.firestore.FieldValue.arrayRemove(args.subjectName))
        }

        task.addOnFailureListener {
            isFavorite = wasFavorite
            updateFavoriteIcon()
            Toast.makeText(context, "Update failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavoriteIcon() {
        binding.ivFavoriteIcon.setImageResource(
            if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun renderFilters() {
        if (_binding == null) return
        binding.resourceChipContainer.removeAllViews()
        lockParentSwipeWhileTouching(binding.resourceChipContainer.parent as View)

        Filter.values().forEach { filter ->
            val isSelected = filter == selectedFilter
            val chip = TextView(requireContext()).apply {
                text = filter.label
                textSize = 12.5f
                setTextColor(
                    if (isSelected) ThemeColors.get(requireContext(), R.color.cm_text_inverse)
                    else ThemeColors.get(requireContext(), R.color.cm_text_primary)
                )
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (isSelected) R.drawable.bg_library_chip_html_selected
                    else R.drawable.bg_library_chip_html
                )
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                applyClickAnimation {
                    if (selectedFilter != filter) {
                        selectedFilter = filter
                        renderFilters()
                        applyResourceFilter()
                    }
                }
            }
            binding.resourceChipContainer.addView(chip)
        }
    }

    private fun checkAdminAccess(onComplete: () -> Unit) {
        if (ArchiveLibraryRepository.usesSupabaseCatalog) {
            val context = AppContextManager.appContextFlow.value
            isAdmin = context.v2SessionActive &&
                (context.isAdmin() || context.role == "teacher" || context.role == "cr")
            updateAdminUi()
            onComplete()
            return
        }
        val uid = auth.currentUser?.uid ?: run {
            updateAdminUi()
            onComplete()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                val canUploadPdf = doc.getBoolean("permissions.canUploadPDF") ?: false
                val canUploadLibrary = doc.getBoolean("permissions.canUploadLibrary") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canUploadPdf || canUploadLibrary
                updateAdminUi()
                onComplete()
            }
            .addOnFailureListener {
                updateAdminUi()
                onComplete()
            }
    }

    private fun updateAdminUi() {
        if (_binding == null) return
        binding.fabUploadFile.isVisible = isAdmin
        binding.btnEmptyUpload.isVisible = isAdmin
    }

    private fun setupRecyclerView() {
        courseFileAdapter = CourseFileAdapter(emptyList(), isAdmin, favoritePdfIds)
        courseFileAdapter.onItemClick = { pdf -> handleResourceAction(pdf) }
        courseFileAdapter.onOptionsClick = { pdf -> handleResourceAction(pdf) }
        courseFileAdapter.onFavoriteClick = { pdf -> togglePdfFavorite(pdf) }

        binding.rvSubjectPdfs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = courseFileAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
        }
    }

    private fun fetchPdfs(showShimmer: Boolean = true) {
        val isSwipeRefreshing = binding.swipeRefresh.isRefreshing
        if (!isSwipeRefreshing && showShimmer && allResources.isEmpty()) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvSubjectPdfs.isVisible = false
            binding.layoutEmptyState.isVisible = false
        } else {
            binding.swipeRefresh.isRefreshing = true
        }

        val batchId = AppContextManager.getBatchId()
        val activeSem = AppContextManager.getSemesterId()

        ArchiveLibraryRepository.load(batchId, activeSem, { courses, resources ->
            if (_binding == null) return@load
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.rvSubjectPdfs.isVisible = true
            binding.swipeRefresh.isRefreshing = false

            // Match course details
            matchedCourse = courses.firstOrNull { it.name.equals(args.subjectName, ignoreCase = true) }
            updateHeroBanner(courses)

            allResources = resources
                .filter { it.subject.equals(args.subjectName, ignoreCase = true) }
                .sortedByDescending { (it.timestamp ?: it.createdAt)?.toDate()?.time ?: 0L }

            val count = allResources.size
            binding.tvResourceStats.text = "$count ${if (count == 1) "resource" else "resources"} available"
            applyResourceFilter()
        }, { e ->
            if (_binding == null) return@load
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.rvSubjectPdfs.isVisible = true
            binding.swipeRefresh.isRefreshing = false
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        })
    }

    private fun updateHeroBanner(courses: List<Course>) {
        val course = matchedCourse ?: courses.firstOrNull { it.name.equals(args.subjectName, ignoreCase = true) }
        val code = course?.code?.ifBlank { subjectCode() } ?: subjectCode()
        val typeLabel = when (course?.type?.lowercase()) {
            "lab" -> "LAB"
            "syllabus" -> "SYLLABUS"
            else -> "THEORY"
        }
        binding.tvCourseCodeBadge.text = "$code • $typeLabel"
        binding.tvSubjectTitle.text = args.subjectName

        val teacher = course?.teacherName.orEmpty().trim()
        if (teacher.isNotBlank()) {
            binding.layoutInstructor.isVisible = true
            binding.tvInstructorName.text = teacher
        } else {
            binding.layoutInstructor.isVisible = false
        }
    }

    private fun applyResourceFilter() {
        if (_binding == null || !::courseFileAdapter.isInitialized) return
        val filtered = allResources.filter { matchesFilter(it) }
        courseFileAdapter.updateList(filtered, favoritePdfIds)
        binding.rvSubjectPdfs.scheduleLayoutAnimation()

        val isEmpty = filtered.isEmpty()
        binding.rvSubjectPdfs.isVisible = !isEmpty
        binding.layoutEmptyState.isVisible = isEmpty

        if (isEmpty) {
            if (allResources.isEmpty()) {
                binding.tvEmptyTitle.text = "No resources uploaded yet"
                binding.tvEmptySubtitle.text = "Be the first to share notes, slides, or questions for this course."
            } else {
                binding.tvEmptyTitle.text = "No ${selectedFilter.label.lowercase()} found"
                binding.tvEmptySubtitle.text = "Try selecting another category above to view available materials."
            }
            binding.btnEmptyUpload.isVisible = isAdmin
        }
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
                    val filtered = allResources.filter { matchesFilter(it) }
                    courseFileAdapter.updateList(filtered, favoritePdfIds)
                    Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            favoritePdfIds = favoritePdfIds - pdf.id
            db.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayRemove(pdf.id))
                .addOnFailureListener {
                    favoritePdfIds = wasFavoritePdfIds
                    val filtered = allResources.filter { matchesFilter(it) }
                    courseFileAdapter.updateList(filtered, favoritePdfIds)
                    Toast.makeText(context, "Remove failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }

        val filtered = allResources.filter { matchesFilter(it) }
        courseFileAdapter.updateList(filtered, favoritePdfIds)
    }

    private fun matchesFilter(pdf: PdfFile): Boolean {
        val text = "${pdf.fileType} ${pdf.mimeType} ${pdf.title} ${pdf.description} ${pdf.materialType}".lowercase()
        return when (selectedFilter) {
            Filter.All -> true
            Filter.Slides -> text.contains("ppt") || text.contains("slide") || text.contains("presentation")
            Filter.Notes -> text.contains("note") || text.contains("doc") || text.contains("handout")
            Filter.Assignments -> text.contains("assignment") || text.contains("task") || text.contains("hw")
            Filter.Questions -> text.contains("question") || text.contains("cq") || text.contains("mid") || text.contains("final") || text.contains("ct ")
            Filter.Lab -> pdf.courseType.equals("lab", true) || FileVisuals.isLabResource(pdf)
            Filter.Starred -> favoritePdfIds.contains(pdf.id)
        }
    }

    private fun handleResourceAction(pdf: PdfFile) {
        PdfDialogHelper.showPdfOptions(
            requireActivity(),
            requireContext(),
            pdf,
            onOfflineStatusChanged = { fetchPdfs(showShimmer = false) },
            onManage = { if (LibraryPermissions.canDelete(pdf)) showDeleteConfirmation(pdf) }
        )
    }

    private fun showDeleteConfirmation(pdf: PdfFile) {
        if (!LibraryPermissions.canDelete(pdf)) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Resource")
            .setMessage("Are you sure you want to delete '${pdf.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                deletePdf(pdf)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePdf(pdf: PdfFile) {
        if (!LibraryPermissions.canDelete(pdf)) return
        binding.progressBar.visibility = View.VISIBLE
        ArchiveLibraryRepository.deleteResource(pdf.id, {
            if (_binding == null) return@deleteResource
            binding.progressBar.visibility = View.GONE
            Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
            allResources = allResources.filterNot { it.id == pdf.id }
            applyResourceFilter()
            fetchPdfs(showShimmer = false)
        }, {
            if (_binding == null) return@deleteResource
            binding.progressBar.visibility = View.GONE
            Toast.makeText(context, "Failed to delete: ${it.message}", Toast.LENGTH_SHORT).show()
        }, provider = pdf.provider)
    }

    private fun subjectCode(): String = SubjectList.codeFor(args.subjectName).ifBlank { "COURSE" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun lockParentSwipeWhileTouching(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    (activity as? MainActivity)?.setMainPageSwipeEnabled(false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    (activity as? MainActivity)?.setMainPageSwipeEnabled(true)
                }
            }
            false
        }
    }

    override fun onDestroyView() {
        _binding?.shimmerView?.stopShimmer()
        super.onDestroyView()
        _binding = null
    }
}
