package com.shuaib.classmate.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemPdfBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Locale

class PdfAdapter(
    private var pdfs: List<PdfFile>,
    private val isAdmin: Boolean = false,
    private var favoritePdfIds: Set<String> = emptySet()
) : RecyclerView.Adapter<PdfAdapter.PdfViewHolder>() {

    var onItemClick: ((PdfFile) -> Unit)? = null
    var onDeleteClick: ((PdfFile) -> Unit)? = null
    var onFavoriteClick: ((PdfFile) -> Unit)? = null

    inner class PdfViewHolder(val binding: ItemPdfBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
        val binding = ItemPdfBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PdfViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
        val pdf = pdfs[position]
        holder.binding.apply {
            tvTitle.text = pdf.title
            tvSubject.text = pdf.subject
            tvUploadedBy.text = if (pdf.uploadedBy.isBlank()) "ClassMate Library" else "By ${pdf.uploadedBy}"
            tvFileMeta.text = listOf(
                formatBytes(pdf.sizeBytes),
                "uploaded ${formatTime(pdf)}"
            ).filter { it.isNotBlank() }.joinToString(" - ")

            if (pdf.description.isNotBlank()) {
                tvDescription.visibility = View.VISIBLE
                tvDescription.text = pdf.description
            } else {
                tvDescription.visibility = View.GONE
            }

            // Enhanced visuals using FileVisuals helper
            val visuals = FileVisuals.getVisuals(pdf)
            tvFileBadge.text = visuals.label
            iconContainer.background = ContextCompat.getDrawable(root.context, visuals.backgroundRes)
            ivFileIcon.setImageResource(visuals.iconRes)
            ivFileIcon.setColorFilter(visuals.tint)

            // LAB secondary tag logic
            tvLabTag.isVisible = FileVisuals.isLabResource(pdf)

            progressDownload.isVisible = false
            btnDownloadView.setOnClickListener {
                onItemClick?.invoke(pdf)
            }

            btnDelete.isVisible = isAdmin
            btnDelete.setOnClickListener {
                onDeleteClick?.invoke(pdf)
            }

            val isFavorite = favoritePdfIds.contains(pdf.id)
            btnFavorite.setImageResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            btnFavorite.setColorFilter(if (isFavorite) ContextCompat.getColor(root.context, R.color.cm_accent) else ContextCompat.getColor(root.context, R.color.cm_text_secondary))

            btnFavorite.setOnClickListener {
                onFavoriteClick?.invoke(pdf)
            }

            root.applyClickAnimation {
                onItemClick?.invoke(pdf)
            }
        }
    }

    override fun getItemCount(): Int = pdfs.size

    fun updateList(newList: List<PdfFile>, newFavorites: Set<String>? = null) {
        val old = pdfs
        val oldFavs = favoritePdfIds
        pdfs = newList
        if (newFavorites != null) favoritePdfIds = newFavorites

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return old[oldItemPosition].id == newList[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = old[oldItemPosition]
                val newItem = newList[newItemPosition]
                val favsChanged = oldFavs.contains(oldItem.id) != favoritePdfIds.contains(newItem.id)
                return oldItem == newItem && !favsChanged
            }
        }).dispatchUpdatesTo(this)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb)
    }

    private fun formatTime(pdf: PdfFile): String {
        val timestamp = pdf.timestamp ?: pdf.createdAt ?: return "just now"
        return DateUtils.getRelativeTimeSpanString(
            timestamp.toDate().time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
}
