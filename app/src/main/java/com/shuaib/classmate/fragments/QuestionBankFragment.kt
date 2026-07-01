package com.shuaib.classmate.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.MainActivity
import com.shuaib.classmate.adapters.QuestionBankAdapter
import com.shuaib.classmate.databinding.FragmentQuestionBankBinding
import com.shuaib.classmate.models.QuestionPaper
import com.shuaib.classmate.utils.LibrarySystemBars
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation

class QuestionBankFragment : Fragment() {

    private var _binding: FragmentQuestionBankBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: QuestionBankAdapter

    private var allPapers = emptyList<QuestionPaper>()
    private var isAdmin = false

    // Active filter state
    private var filterExamType = "All"
    private var filterYear = "All"
    private var filterCourse = "All"

    // Derived chip labels (populated from loaded data)
    private val examTypeOptions = mutableListOf("All")
    private val yearOptions = mutableListOf("All")
    private val courseOptions = mutableListOf("All")

    private var previousStatusBarColor: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuestionBankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.btnBack.applyClickAnimation { findNavController().navigateUp() }
        binding.btnUploadQuestion.applyClickAnimation { openUploadActivity() }

        setupRecyclerView()
        checkAdminAccess()

        binding.swipeRefresh.setOnRefreshListener { loadData() }
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

    private fun setupRecyclerView() {
        adapter = QuestionBankAdapter(emptyList(), isAdmin)
        adapter.onDeleteClick = { qp -> confirmDelete(qp) }
        binding.rvQuestions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@QuestionBankFragment.adapter
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_library_list)
        }
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid
        if (uid == null) { loadData(); return }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val user = doc.toObject(com.shuaib.classmate.models.User::class.java)
                isAdmin = user?.let { it.canUploadPDF() || it.canUploadLibrary() } ?: false
                binding.btnUploadQuestion.isVisible = isAdmin
                loadData()
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                loadData()
            }
    }

    private fun loadData() {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = true
        binding.shimmerView.isVisible = true
        binding.shimmerView.startShimmer()
        binding.rvQuestions.isVisible = false

        db.collection("question_bank")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                allPapers = snapshot.documents.mapNotNull { doc ->
                    try {
                        QuestionPaper(
                            id = doc.id,
                            title = doc.getString("title") ?: "",
                            courseCode = doc.getString("courseCode") ?: "",
                            subject = doc.getString("subject") ?: "",
                            examType = doc.getString("examType") ?: "",
                            semester = doc.getString("semester") ?: "",
                            year = doc.getString("year") ?: "",
                            uploadedBy = doc.getString("uploadedByName") ?: doc.getString("uploadedBy") ?: "",
                            uploadedByUid = doc.getString("uploadedByUid") ?: "",
                            telegramUrl = doc.getString("telegramUrl") ?: "",
                            driveUrl = doc.getString("driveUrl") ?: "",
                            downloadUrl = doc.getString("downloadUrl") ?: "",
                            provider = doc.getString("provider") ?: "",
                            sizeBytes = doc.getLong("sizeBytes") ?: 0L,
                            downloadCount = doc.getLong("downloadCount") ?: 0L,
                            timestamp = doc.getTimestamp("timestamp") ?: doc.getTimestamp("createdAt"),
                            isDeleted = doc.getBoolean("isDeleted") ?: false
                        )
                    } catch (_: Exception) { null }
                }
                    .filterNot { it.isDeleted }
                    .sortedWith(compareByDescending<QuestionPaper> { it.year }.thenByDescending { it.timestamp })

                rebuildChipOptions()
                applyFilters()

                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvQuestions.isVisible = true
                binding.swipeRefresh.isRefreshing = false
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvQuestions.isVisible = true
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(context, "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun rebuildChipOptions() {
        examTypeOptions.apply {
            clear()
            add("All")
            addAll(allPapers.map { it.examType }.filter { it.isNotBlank() }.distinct().sorted())
        }
        yearOptions.apply {
            clear()
            add("All")
            addAll(allPapers.map { it.year }.filter { it.isNotBlank() }.distinct().sortedDescending())
        }
        courseOptions.apply {
            clear()
            add("All")
            val codes = allPapers.map { it.courseCode.ifBlank { it.subject } }
                .filter { it.isNotBlank() }.distinct().sorted()
            addAll(codes)
        }

        // Validate current selections still exist
        if (filterExamType != "All" && filterExamType !in examTypeOptions) filterExamType = "All"
        if (filterYear != "All" && filterYear !in yearOptions) filterYear = "All"
        if (filterCourse != "All" && filterCourse !in courseOptions) filterCourse = "All"

        renderChips()
    }

    private fun renderChips() {
        renderChipRow(
            binding.chipContainerExamType, examTypeOptions, filterExamType
        ) { selected -> filterExamType = selected; applyFilters() }

        renderChipRow(
            binding.chipContainerYear, yearOptions, filterYear
        ) { selected -> filterYear = selected; applyFilters() }

        renderChipRow(
            binding.chipContainerCourse, courseOptions, filterCourse
        ) { selected -> filterCourse = selected; applyFilters() }
    }

    private fun renderChipRow(
        container: ViewGroup,
        options: List<String>,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        container.removeAllViews()
        // Hide if only "All" is available (no data yet)
        container.isVisible = options.size > 1
        options.forEach { option ->
            val isSelected = option == selected
            val chip = TextView(requireContext()).apply {
                text = option
                textSize = 12f
                setTextColor(
                    if (isSelected) ThemeColors.onPrimary(requireContext())
                    else ThemeColors.textMuted(requireContext())
                )
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (isSelected) R.drawable.bg_library_chip_selected else R.drawable.bg_library_chip_unselected
                )
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                applyClickAnimation {
                    onSelect(option)
                    renderChips()
                }
            }
            container.addView(chip)
        }
    }

    private fun applyFilters() {
        if (_binding == null) return
        val filtered = allPapers.filter { qp ->
            (filterExamType == "All" || qp.examType.equals(filterExamType, ignoreCase = true)) &&
                (filterYear == "All" || qp.year == filterYear) &&
                (filterCourse == "All" || qp.courseCode == filterCourse || qp.subject == filterCourse)
        }

        adapter.updateList(filtered, isAdmin)
        binding.rvQuestions.scheduleLayoutAnimation()
        binding.layoutEmpty.isVisible = filtered.isEmpty()
        binding.rvQuestions.isVisible = filtered.isNotEmpty()

        val total = allPapers.size
        binding.tvQbMeta.text = if (total == 0) "No papers uploaded yet" else
            "$total paper${if (total == 1) "" else "s"} · Finals, CTs &amp; Mids"
    }

    private fun confirmDelete(qp: QuestionPaper) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Question Paper")
            .setMessage("Delete \"${buildDisplayTitle(qp)}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deletePaper(qp) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePaper(qp: QuestionPaper) {
        db.collection("question_bank").document(qp.id)
            .update(
                mapOf(
                    "isDeleted" to true,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun buildDisplayTitle(qp: QuestionPaper): String {
        if (qp.title.isNotBlank()) return qp.title
        val parts = listOfNotNull(
            qp.examType.takeIf { it.isNotBlank() },
            qp.year.takeIf { it.isNotBlank() },
            qp.courseCode.ifBlank { qp.subject }.takeIf { it.isNotBlank() }?.let { "— $it" }
        )
        return parts.joinToString(" ").ifBlank { "Question Paper" }
    }

    private fun openUploadActivity() {
        // Reuse the PdfUploadActivity but could be specialised later
        try {
            val intent = Intent(requireContext(), com.shuaib.classmate.activities.PdfUploadActivity::class.java)
            intent.putExtra("uploadMode", "question_bank")
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Upload screen coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
