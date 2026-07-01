package com.shuaib.classmate.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.adapters.PdfAdapter
import com.shuaib.classmate.databinding.FragmentLibraryHomeBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.storage.LibraryUrlOpener
import com.shuaib.classmate.utils.PdfDialogHelper
import com.shuaib.classmate.utils.applyClickAnimation

class LibraryAllFilesFragment : Fragment() {

    private var _binding: FragmentLibraryHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var pdfAdapter: PdfAdapter
    private var isAdmin = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupRecyclerView()
        setupListeners()
        checkAdminStatus()
    }

    private fun setupRecyclerView() {
        pdfAdapter = PdfAdapter(emptyList(), isAdmin)
        pdfAdapter.onItemClick = { pdf ->
            PdfDialogHelper.showPdfOptions(requireActivity(), requireContext(), pdf)
        }
        pdfAdapter.onDeleteClick = { pdf ->
            showDeleteConfirmation(pdf)
        }
        
        binding.rvAllFiles.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pdfAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.applyClickAnimation {
            findNavController().navigateUp()
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadAllFiles()
        }
    }

    private fun checkAdminStatus() {
        val uid = auth.currentUser?.uid ?: run {
            loadAllFiles()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                val canUploadPdf = doc.getBoolean("permissions.canUploadPDF") ?: false
                val canUploadLibrary = doc.getBoolean("permissions.canUploadLibrary") ?: false
                isAdmin = role == "superadmin" || role == "admin" || canUploadPdf || canUploadLibrary
                
                pdfAdapter = PdfAdapter(emptyList(), isAdmin)
                pdfAdapter.onItemClick = { pdf -> PdfDialogHelper.showPdfOptions(requireActivity(), requireContext(), pdf) }
                pdfAdapter.onDeleteClick = { pdf -> showDeleteConfirmation(pdf) }
                binding.rvAllFiles.adapter = pdfAdapter
                loadAllFiles()
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                loadAllFiles()
            }
    }

    private fun loadAllFiles() {
        if (!binding.swipeRefresh.isRefreshing) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvAllFiles.isVisible = false
        }
        
        db.collection("library_files")
            .whereEqualTo("isDeleted", false)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                val files = snapshot.documents.map { doc -> doc.toPdfFile() }
                    .filter { !it.isDeleted }
                    .sortedByDescending { it.timestamp ?: it.createdAt }
                
                pdfAdapter.updateList(files)
                
                binding.tvAllFilesMeta.text = "${resourceCountText(files.size)} uploaded"
                binding.tvEmptyState.isVisible = files.isEmpty()
                binding.swipeRefresh.isRefreshing = false
                
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvAllFiles.isVisible = true
            }
            .addOnFailureListener { e ->
                if (_binding == null) return@addOnFailureListener
                binding.swipeRefresh.isRefreshing = false
                
                binding.shimmerView.stopShimmer()
                binding.shimmerView.isVisible = false
                binding.rvAllFiles.isVisible = true

                Toast.makeText(context, "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
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
                loadAllFiles()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
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

    private fun resourceCountText(count: Int): String {
        return "$count ${if (count == 1) "resource" else "resources"}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
