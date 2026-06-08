// com/shuaib/classmate/fragments/SubjectPdfListFragment.kt
package com.shuaib.classmate.fragments

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
import com.shuaib.classmate.activities.MainActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.PdfAdapter
import com.shuaib.classmate.databinding.FragmentSubjectPdfListBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryUrlOpener
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
    private lateinit var pdfAdapter: PdfAdapter
    private var allResources = emptyList<PdfFile>()
    private var selectedFilter = Filter.All
    private var isAdmin = false
    private var isFavorite = false
    private var favoritePdfIds = emptySet<String>()
    private var previousStatusBarColor: Int? = null

    private enum class Filter(val label: String) {
        All("All"),
        Slides("Slides"),
        Notes("Notes"),
        Assignments("Assignments"),
        Questions("Questions"),
        Lab("Lab")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
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
    }

    override fun onPause() {
        (activity as? MainActivity)?.setMainPageSwipeEnabled(true)
        previousStatusBarColor?.let { requireActivity().window.statusBarColor = it }
        super.onPause()
    }

    private fun setupHeader() {
        binding.tvSubjectTitle.text = args.subjectName
        binding.tvSubjectCode.text = "${subjectCode()} - Loading resources"
        binding.btnBack.applyClickAnimation {
            findNavController().navigateUp()
        }

        checkFavoriteStatus()

        binding.btnFavorite.applyClickAnimation {
            toggleFavorite()
        }
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
                if (::pdfAdapter.isInitialized) {
                    pdfAdapter.updateList(allResources.filter { matchesFilter(it) }, favoritePdfIds)
                }
            }
    }

    private fun toggleFavorite() {
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
        binding.btnFavorite.getChildAt(0).let { iv ->
            if (iv is android.widget.ImageView) {
                iv.setImageResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            }
        }
    }

    private fun renderFilters() {
        if (_binding == null) return
        binding.resourceChipContainer.removeAllViews()
        lockParentSwipeWhileTouching(binding.resourceChipContainer.parent as View)
        Filter.values().forEach { filter ->
            val chip = TextView(requireContext()).apply {
                text = filter.label
                textSize = 12f
                setTextColor(if (filter == selectedFilter) ThemeColors.onPrimary(requireContext()) else ThemeColors.textMuted(requireContext()))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (filter == selectedFilter) R.drawable.bg_library_chip_selected else R.drawable.bg_library_chip_unselected
                )
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                applyClickAnimation {
                    selectedFilter = filter
                    renderFilters()
                    applyResourceFilter()
                }
            }
            binding.resourceChipContainer.addView(chip)
        }
    }

    private fun checkAdminAccess(onComplete: () -> Unit) {
        val uid = auth.currentUser?.uid ?: run {
            onComplete()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val role = doc.getString("role") ?: "student"
                val canUploadPdf = doc.getBoolean("permissions.canUploadPDF") ?: false
                val canUploadLibrary = doc.getBoolean("permissions.canUploadLibrary") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canUploadPdf || canUploadLibrary
                onComplete()
            }
            .addOnFailureListener { onComplete() }
    }

    private fun setupRecyclerView() {
        pdfAdapter = PdfAdapter(emptyList(), isAdmin, favoritePdfIds)
        pdfAdapter.onItemClick = { pdf -> handleResourceAction(pdf) }
        pdfAdapter.onDeleteClick = { pdf ->
            showDeleteConfirmation(pdf)
        }
        pdfAdapter.onFavoriteClick = { pdf ->
            togglePdfFavorite(pdf)
        }
        binding.rvSubjectPdfs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pdfAdapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
        }
    }

    private fun fetchPdfs() {
        val isSwipeRefreshing = binding.swipeRefresh.isRefreshing
        if (!isSwipeRefreshing) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvSubjectPdfs.isVisible = false
        }
        binding.swipeRefresh.isRefreshing = true
        binding.tvEmptyState.isVisible = false

        db.collection("library_files")
            .whereEqualTo("subject", args.subjectName)
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvSubjectPdfs.isVisible = true
                binding.swipeRefresh.isRefreshing = false

                allResources = snapshot.documents.map { doc -> doc.toPdfFile() }
                    .filterNot { it.isDeleted }
                    .sortedByDescending { it.timestamp ?: it.createdAt }

                binding.tvSubjectCode.text = "${subjectCode()} - ${allResources.size} resources"
                applyResourceFilter()
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvSubjectPdfs.isVisible = true
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun applyResourceFilter() {
        if (_binding == null || !::pdfAdapter.isInitialized) return
        val filtered = allResources.filter { matchesFilter(it) }
        pdfAdapter.updateList(filtered, favoritePdfIds)
        binding.rvSubjectPdfs.scheduleLayoutAnimation()
        binding.tvEmptyState.isVisible = filtered.isEmpty()
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
                    pdfAdapter.updateList(allResources.filter { matchesFilter(it) }, favoritePdfIds)
                    Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            favoritePdfIds = favoritePdfIds - pdf.id
            db.collection("users").document(uid)
                .update("favoritePdfIds", com.google.firebase.firestore.FieldValue.arrayRemove(pdf.id))
                .addOnFailureListener {
                    favoritePdfIds = wasFavoritePdfIds
                    pdfAdapter.updateList(allResources.filter { matchesFilter(it) }, favoritePdfIds)
                    Toast.makeText(context, "Remove failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }

        pdfAdapter.updateList(allResources.filter { matchesFilter(it) }, favoritePdfIds)
    }

    private fun matchesFilter(pdf: PdfFile): Boolean {
        val text = "${pdf.fileType} ${pdf.mimeType} ${pdf.title} ${pdf.description}".lowercase()
        return when (selectedFilter) {
            Filter.All -> true
            Filter.Slides -> text.contains("ppt") || text.contains("slide")
            Filter.Notes -> text.contains("note") || text.contains("doc")
            Filter.Assignments -> text.contains("assignment")
            Filter.Questions -> text.contains("question") || text.contains("cq") || text.contains("mid") || text.contains("final")
            Filter.Lab -> pdf.courseType.equals("lab", true) || text.contains("lab")
        }
    }

    private fun showDeleteConfirmation(pdf: PdfFile) {
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
        binding.progressBar.visibility = View.VISIBLE
        db.collection("library_files").document(pdf.id)
            .update(
                mapOf(
                    "isDeleted" to true,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                if (_binding == null) return@addOnSuccessListener
                binding.progressBar.visibility = View.GONE
                Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
                fetchPdfs()
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                binding.progressBar.visibility = View.GONE
                Toast.makeText(context, "Failed to delete", Toast.LENGTH_SHORT).show()
            }
    }

    private fun handleResourceAction(pdf: PdfFile) {
        PdfDialogHelper.showPdfOptions(requireActivity(), requireContext(), pdf)
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toPdfFile(): PdfFile {
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

    private fun subjectCode(): String = SubjectList.codeFor(args.subjectName).ifBlank { "LIB0000" }

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
        super.onDestroyView()
        _binding = null
    }
}
