package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.databinding.ActivityClassmateCourseFilesBinding
import com.shuaib.classmate.databinding.ItemCourseFileBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.applyClickAnimation
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Dedicated, locally searchable and shimmer-animated view of every file in one course. */
class ClassMateCourseFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClassmateCourseFilesBinding
    private val courseId by lazy { intent.getStringExtra("course_id").orEmpty() }
    private val batchId by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private val courseName by lazy { intent.getStringExtra("course_name").orEmpty() }
    private val canEdit by lazy { intent.getStringExtra("role") == "admin" || intent.getBooleanExtra("is_cr", false) }
    private val canManage by lazy { intent.getBooleanExtra("can_manage", false) }
    private val canUpload by lazy {
        canManage || canEdit || intent.getStringExtra("role") in setOf("admin", "teacher") || intent.getBooleanExtra("is_cr", false)
    }

    private val editLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            loadFiles(showShimmer = false)
        }
    }

    private val files = mutableListOf<JSONObject>()
    private var filtered = emptyList<JSONObject>()
    private var search = ""
    private lateinit var adapter: FileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClassMateAuthApi.attach(applicationContext)

        if (courseId.isBlank() && batchId.isBlank()) {
            finish()
            return
        }

        binding = ActivityClassmateCourseFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        setupHeader()
        setupSearch()
        setupRecyclerView()

        binding.swipeRefresh.setOnRefreshListener {
            loadFiles(showShimmer = false)
        }

        loadFiles(showShimmer = true)
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupHeader() {
        binding.tvCourseTitle.text = courseName.ifBlank { "Course Materials" }
        binding.tvFileCount.text = "Loading files…"

        binding.btnAddFile.isVisible = canUpload
        binding.btnAddFile.applyClickAnimation {
            openUploadFile()
        }

        binding.btnEmptyAddFile.applyClickAnimation {
            openUploadFile()
        }

        binding.btnBack.applyClickAnimation {
            finish()
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s?.toString()?.trim().orEmpty()
                binding.ivClearSearch.isVisible = search.isNotBlank()
                if (::adapter.isInitialized) {
                    showFiles()
                }
            }
        })

        binding.ivClearSearch.applyClickAnimation {
            binding.etSearch.setText("")
        }
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter()
        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@ClassMateCourseFilesActivity)
            adapter = this@ClassMateCourseFilesActivity.adapter
        }
    }

    private fun loadFiles(showShimmer: Boolean) = lifecycleScope.launch {
        if (showShimmer && files.isEmpty()) {
            binding.shimmerView.isVisible = true
            binding.shimmerView.startShimmer()
            binding.rvFiles.isVisible = false
            binding.layoutEmptyState.isVisible = false
        } else {
            binding.swipeRefresh.isRefreshing = true
        }

        runCatching {
            val loaded = mutableListOf<JSONObject>()
            var offset = 0
            do {
                val page = ClassMateAuthApi.rows(
                    "file_metadata",
                    "select=id,title,category,size_bytes,created_at,semester_course_id,batch_id,file_type&" +
                        (if (courseId.isBlank()) "batch_id=eq.$batchId" else "semester_course_id=eq.$courseId") +
                        "&status=eq.active&order=created_at.desc&limit=500&offset=$offset"
                )
                for (i in 0 until page.length()) {
                    loaded.add(page.getJSONObject(i))
                }
                offset += page.length()
            } while (page.length() == 500)
            loaded
        }.onSuccess { loadedFiles ->
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.swipeRefresh.isRefreshing = false

            files.clear()
            files.addAll(loadedFiles)
            showFiles()
        }.onFailure { error ->
            binding.shimmerView.stopShimmer()
            binding.shimmerView.isVisible = false
            binding.swipeRefresh.isRefreshing = false
            binding.tvFileCount.text = "Could not load files"
            Toast.makeText(this@ClassMateCourseFilesActivity, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showFiles() {
        filtered = files.filter {
            search.isBlank() || it.optString("title").contains(search, true)
        }

        val total = filtered.size
        binding.tvFileCount.text = if (total == 0) "No files" else "$total ${if (total == 1) "file" else "files"}"

        val isEmpty = filtered.isEmpty()
        binding.rvFiles.isVisible = !isEmpty
        binding.layoutEmptyState.isVisible = isEmpty

        if (isEmpty) {
            if (files.isEmpty()) {
                binding.tvEmptyTitle.text = "No files uploaded yet"
                binding.tvEmptySubtitle.text = "Materials, slides, and notes for this course will appear here."
                binding.btnEmptyAddFile.isVisible = canUpload
            } else {
                binding.tvEmptyTitle.text = "No matching files"
                binding.tvEmptySubtitle.text = "No files matching \"$search\" were found in this course."
                binding.btnEmptyAddFile.isVisible = false
            }
        }

        adapter.notifyDataSetChanged()
    }

    private fun openUploadFile() {
        editLauncher.launch(
            Intent(this, ClassMatePublishActivity::class.java)
                .putExtra("batch_id", batchId)
                .putExtra("upload", true)
                .putExtra("role", intent.getStringExtra("role"))
                .putExtra("profile_id", intent.getStringExtra("profile_id"))
                .putExtra("course_id", courseId)
        )
    }

    private fun openFile(file: JSONObject) = lifecycleScope.launch {
        runCatching { ClassMateAuthApi.signedResource(file.getString("id")) }
            .onSuccess { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            .onFailure {
                Toast.makeText(this@ClassMateCourseFilesActivity, it.message, Toast.LENGTH_LONG).show()
            }
    }

    private fun deleteFile(file: JSONObject) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Permanently delete ${file.optString("title")}?")
            .setMessage("This removes the protected file, library entry, and linked notice. This cannot be undone.")
            .setPositiveButton("Delete permanently") { _, _ ->
                lifecycleScope.launch {
                    runCatching { ClassMateAuthApi.deleteResource(file.getString("id")) }
                        .onSuccess {
                            ClassMateAcademicCache.removeResourceNotice(
                                this@ClassMateCourseFilesActivity,
                                intent.getStringExtra("profile_id").orEmpty(),
                                batchId,
                                file.getString("id")
                            )
                            files.removeAll { it.optString("id") == file.optString("id") }
                            showFiles()
                            setResult(RESULT_OK)
                        }.onFailure {
                            Toast.makeText(this@ClassMateCourseFilesActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {

        inner class Holder(val itemBinding: ItemCourseFileBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemCourseFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount() = filtered.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = filtered[position]
            val b = holder.itemBinding

            val title = file.optString("title")
            b.tvTitle.text = ClassMateNoticeText.styled(b.tvTitle, title, search)

            // Category tag
            val category = file.optString("category").trim()
            if (category.isNotBlank()) {
                b.tvMaterialTag.isVisible = true
                b.tvMaterialTag.text = category.uppercase()
            } else {
                b.tvMaterialTag.isVisible = false
            }

            // Visuals using FileVisuals
            val tempPdf = PdfFile(
                id = file.optString("id"),
                title = title,
                fileType = file.optString("file_type").ifBlank { "pdf" },
                materialType = category,
                sizeBytes = file.optLong("size_bytes")
            )
            val visuals = FileVisuals.getVisuals(tempPdf)
            b.iconContainer.background = ContextCompat.getDrawable(this@ClassMateCourseFilesActivity, visuals.backgroundRes)
            b.ivFileIcon.setImageResource(visuals.iconRes)
            b.ivFileIcon.setColorFilter(visuals.tint)

            b.tvLabTag.isVisible = FileVisuals.isLabResource(tempPdf)

            // Metadata: Size & Relative Date
            val metaParts = mutableListOf<String>()
            val formattedBytes = formatBytes(file.optLong("size_bytes"))
            if (formattedBytes.isNotBlank()) metaParts.add(formattedBytes)
            val formattedTime = formatTime(file.optString("created_at"))
            if (formattedTime.isNotBlank()) metaParts.add(formattedTime)
            b.tvFileMeta.text = metaParts.joinToString(" • ")

            b.tvUploadedBy.isVisible = false
            b.btnFavorite.isVisible = false

            // Options / Delete
            b.btnOptions.isVisible = canManage
            b.btnOptions.applyClickAnimation {
                if (canEdit) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMateCourseFilesActivity)
                        .setTitle("Manage file")
                        .setItems(arrayOf("Edit file details", "Delete permanently")) { _, choice ->
                            if (choice == 1) {
                                deleteFile(file)
                            } else {
                                editLauncher.launch(
                                    Intent(this@ClassMateCourseFilesActivity, ClassMatePublishActivity::class.java)
                                        .putExtra("batch_id", batchId)
                                        .putExtra("upload", true)
                                        .putExtra("role", intent.getStringExtra("role"))
                                        .putExtra("resource", file.toString())
                                )
                            }
                        }.show()
                } else {
                    deleteFile(file)
                }
            }

            b.root.applyClickAnimation {
                openFile(file)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb)
    }

    private fun formatTime(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return runCatching {
            val epoch = Instant.parse(dateStr).toEpochMilli()
            DateUtils.getRelativeTimeSpanString(
                epoch,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }.getOrDefault("")
    }

    override fun onDestroy() {
        binding.shimmerView.stopShimmer()
        super.onDestroy()
    }
}
