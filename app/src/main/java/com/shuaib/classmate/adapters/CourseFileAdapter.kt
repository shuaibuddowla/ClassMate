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
import com.shuaib.classmate.databinding.ItemCourseFileBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Locale

class CourseFileAdapter(
    private var pdfs: List<PdfFile>,
    private val isAdmin: Boolean = false,
    private var favoritePdfIds: Set<String> = emptySet()
) : RecyclerView.Adapter<CourseFileAdapter.CourseFileViewHolder>() {

    var onItemClick: ((PdfFile) -> Unit)? = null
    var onFavoriteClick: ((PdfFile) -> Unit)? = null
    var onOptionsClick: ((PdfFile) -> Unit)? = null

    inner class CourseFileViewHolder(val binding: ItemCourseFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseFileViewHolder {
        val binding = ItemCourseFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CourseFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CourseFileViewHolder, position: Int) {
        val pdf = pdfs[position]
        holder.binding.apply {
            tvTitle.text = pdf.title

            // Visuals (icon, color background, tint)
            val visuals = FileVisuals.getVisuals(pdf)
            iconContainer.background = ContextCompat.getDrawable(root.context, visuals.backgroundRes)
            ivFileIcon.setImageResource(visuals.iconRes)
            ivFileIcon.setColorFilter(visuals.tint)

            // Category & Lab Tag
            val detectedTag = detectMaterialTag(pdf)
            if (detectedTag != null) {
                tvMaterialTag.isVisible = true
                tvMaterialTag.text = detectedTag
            } else {
                tvMaterialTag.isVisible = false
            }

            tvLabTag.isVisible = FileVisuals.isLabResource(pdf)

            // Metadata: Size & Date
            val metaParts = mutableListOf<String>()
            val formattedBytes = formatBytes(pdf.sizeBytes)
            if (formattedBytes.isNotBlank()) metaParts.add(formattedBytes)
            metaParts.add("uploaded ${formatTime(pdf)}")
            tvFileMeta.text = metaParts.joinToString(" • ")

            // Uploader
            tvUploadedBy.text = if (pdf.uploadedBy.isBlank()) "ClassMate Library" else "By ${pdf.uploadedBy}"

            // Star favorite toggle
            val isFavorite = favoritePdfIds.contains(pdf.id)
            ivFavorite.setImageResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            ivFavorite.setColorFilter(
                ContextCompat.getColor(
                    root.context,
                    if (isFavorite) R.color.cm_warning else R.color.cm_text_disabled
                )
            )

            btnFavorite.applyClickAnimation {
                onFavoriteClick?.invoke(pdf)
            }

            btnOptions.applyClickAnimation {
                onOptionsClick?.invoke(pdf)
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

    private fun detectMaterialTag(pdf: PdfFile): String? {
        val text = "${pdf.materialType} ${pdf.title} ${pdf.fileType} ${pdf.mimeType}".lowercase()
        return when {
            text.contains("slide") || text.contains("ppt") || text.contains("presentation") -> "SLIDES"
            text.contains("question") || text.contains("cq") || text.contains("mid") || text.contains("final") || text.contains("ct ") -> "QUESTION"
            text.contains("assignment") || text.contains("task") || text.contains("hw") -> "ASSIGNMENT"
            text.contains("note") || text.contains("handout") || text.contains("lecture") || text.contains("doc") -> "NOTES"
            pdf.materialType.isNotBlank() -> pdf.materialType.uppercase()
            else -> null
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb)
    }

    private fun formatTime(pdf: PdfFile): String {
        val timestamp = pdf.timestamp ?: pdf.createdAt ?: return "recently"
        return DateUtils.getRelativeTimeSpanString(
            timestamp.toDate().time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
}
