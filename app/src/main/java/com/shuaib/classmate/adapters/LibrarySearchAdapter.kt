package com.shuaib.classmate.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemLibrarySearchResultBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.applyClickAnimation

class LibrarySearchAdapter(
    private var files: List<PdfFile>,
    private val onItemClick: (PdfFile) -> Unit
) : RecyclerView.Adapter<LibrarySearchAdapter.SearchResultViewHolder>() {

    inner class SearchResultViewHolder(val binding: ItemLibrarySearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val binding = ItemLibrarySearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        val file = files[position]
        val visuals = FileVisuals.getVisuals(file)
        holder.binding.apply {
            tvTitle.text = file.title.ifBlank { "Untitled resource" }
            tvSubject.text = listOf(file.subject, file.courseCode)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
                .ifBlank { "Library resource" }
            tvMeta.text = listOf(
                visuals.label,
                "uploaded ${formatTime(file)}",
                file.uploadedBy.ifBlank { "ClassMate Library" }
            ).joinToString(" - ")

            iconContainer.background = ContextCompat.getDrawable(root.context, visuals.backgroundRes)
            ivFileIcon.setImageResource(visuals.iconRes)
            ivFileIcon.setColorFilter(visuals.tint)

            root.applyClickAnimation { onItemClick(file) }
            ivOpenExternal.setOnClickListener { onItemClick(file) }
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateList(newFiles: List<PdfFile>) {
        val oldFiles = files
        files = newFiles
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldFiles.size

            override fun getNewListSize(): Int = newFiles.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldFiles[oldItemPosition].id == newFiles[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldFiles[oldItemPosition] == newFiles[newItemPosition]
            }
        }).dispatchUpdatesTo(this)
    }

    private fun formatTime(file: PdfFile): String {
        val timestamp = file.timestamp ?: file.createdAt ?: return "just now"
        return DateUtils.getRelativeTimeSpanString(
            timestamp.toDate().time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
}
