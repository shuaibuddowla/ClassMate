package com.shuaib.classmate.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.adapters.LibrarySearchAdapter
import com.shuaib.classmate.databinding.FragmentLibrarySearchBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryUrlOpener
import com.shuaib.classmate.utils.LibrarySystemBars
import com.shuaib.classmate.utils.PdfDialogHelper
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation

class LibrarySearchFragment : Fragment() {

    private var _binding: FragmentLibrarySearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var searchAdapter: LibrarySearchAdapter
    private var allFiles = emptyList<PdfFile>()
    private var currentQuery = ""
    private var currentFilter = Filter.All
    private var filtersVisible = false
    private var previousStatusBarColor: Int? = null

    private enum class Filter(val label: String) {
        All("All"),
        Pdfs("PDFs"),
        Slides("Slides"),
        Notes("Notes"),
        Lab("Lab"),
        Other("Other")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibrarySearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()

        setupResults()
        setupSearch()
        setupFilters()
        animateSearchEntry()
        loadFiles()
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

    private fun setupResults() {
        searchAdapter = LibrarySearchAdapter(emptyList()) { file ->
            PdfDialogHelper.showPdfOptions(requireActivity(), requireContext(), file)
        }
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchAdapter
        }
    }

    private fun setupSearch() {
        binding.btnBack.applyClickAnimation {
            findNavController().navigateUp()
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty().trim()
                updateResults()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupFilters() {
        binding.btnSearchFilter.setOnClickListener {
            filtersVisible = !filtersVisible
            setFiltersVisible(filtersVisible)
            binding.btnSearchFilter.setColorFilter(
                if (filtersVisible) ThemeColors.primary(requireContext()) else ThemeColors.textSecondary(requireContext())
            )
        }
        renderFilters()
    }

    private fun animateSearchEntry() {
        binding.searchContainer.pivotX = 0f
        binding.searchContainer.scaleX = 0.18f
        binding.searchContainer.alpha = 0.4f
        binding.searchContainer.animate()
            .scaleX(1f)
            .alpha(1f)
            .setDuration(220)
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.etSearch.requestFocus()
                binding.etSearch.postDelayed({
                    if (_binding == null) return@postDelayed
                    requireContext().getSystemService<InputMethodManager>()
                        ?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
                }, 80)
            }
            .start()
    }

    private fun setFiltersVisible(visible: Boolean) {
        if (visible) {
            binding.filterScroll.isVisible = true
            binding.filterScroll.alpha = 0f
            binding.filterScroll.translationY = -dp(10).toFloat()
            binding.filterScroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(170)
                .start()
        } else {
            binding.filterScroll.animate()
                .alpha(0f)
                .translationY(-dp(10).toFloat())
                .setDuration(150)
                .withEndAction {
                    if (_binding != null) binding.filterScroll.isVisible = false
                }
                .start()
        }
    }

    private fun renderFilters() {
        binding.filterChipContainer.removeAllViews()
        lockParentSwipeWhileTouching(binding.filterScroll)
        Filter.values().forEach { filter ->
            val selected = filter == currentFilter
            val chip = TextView(requireContext()).apply {
                text = filter.label
                textSize = 13f
                setTextColor(
                    if (selected) ThemeColors.onPrimary(requireContext())
                    else ThemeColors.textSecondary(requireContext())
                )
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (selected) R.drawable.bg_library_chip_selected else R.drawable.bg_library_chip_unselected
                )
                minHeight = dp(40)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(10)
                }
                applyClickAnimation {
                    currentFilter = filter
                    renderFilters()
                    updateResults()
                }
            }
            binding.filterChipContainer.addView(chip)
        }
    }

    private fun loadFiles() {
        binding.shimmerView.isVisible = true
        binding.shimmerView.startShimmer()
        db.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                allFiles = snapshot.documents.map { doc -> doc.toPdfFile() }
                    .filterNot { it.isDeleted }
                    .sortedByDescending { it.timestamp ?: it.createdAt }
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                updateResults()
            }
            .addOnFailureListener { error ->
                if (_binding == null) return@addOnFailureListener
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                Toast.makeText(context, "Failed to load search: ${error.message}", Toast.LENGTH_SHORT).show()
                updateEmptyState(noResults = false)
            }
    }

    private fun updateResults() {
        if (_binding == null) return
        if (currentQuery.isBlank()) {
            searchAdapter.updateList(emptyList())
            binding.rvSearchResults.isVisible = false
            updateEmptyState(noResults = false)
            return
        }

        val results = allFiles.filter { file -> matchesFilter(file) && matchesQuery(file) }
        searchAdapter.updateList(results)
        binding.rvSearchResults.isVisible = results.isNotEmpty()
        binding.emptyState.isVisible = results.isEmpty()
        if (results.isEmpty()) {
            updateEmptyState(noResults = true)
        }
    }

    private fun updateEmptyState(noResults: Boolean) {
        binding.emptyState.isVisible = true
        binding.tvEmptyTitle.text = if (noResults) "No matching resources found" else "Search your library"
        binding.tvEmptySubtitle.text = if (noResults) {
            "Try a title, subject, course code, file type, or uploader name."
        } else {
            "Find PDFs, slides, notes, subjects, and lab files."
        }
    }

    private fun matchesQuery(file: PdfFile): Boolean {
        val query = currentQuery.lowercase()
        return listOf(
            file.title,
            file.subject,
            file.courseCode,
            file.courseType,
            file.fileType,
            file.uploadedBy
        ).any { value -> value.lowercase().contains(query) }
    }

    private fun matchesFilter(file: PdfFile): Boolean {
        val text = "${file.fileType} ${file.mimeType} ${file.title} ${file.subject} ${file.courseType}".lowercase()
        return when (currentFilter) {
            Filter.All -> true
            Filter.Pdfs -> file.fileType.equals("pdf", true) || text.contains("pdf")
            Filter.Slides -> text.contains("ppt") || text.contains("slide")
            Filter.Notes -> text.contains("note") || text.contains("doc") || text.contains("hand")
            Filter.Lab -> file.courseType.equals("lab", true) || text.contains("lab")
            Filter.Other -> !text.contains("pdf") &&
                !text.contains("ppt") &&
                !text.contains("slide") &&
                !text.contains("note") &&
                !text.contains("doc") &&
                !text.contains("lab")
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
