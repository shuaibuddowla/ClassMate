package com.shuaib.classmate.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemRecentPdfBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.SubjectVisuals
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Locale

class RecentPdfAdapter(
    private var pdfs: List<PdfFile>,
    private var isAdmin: Boolean = false,
    private var favoritePdfIds: Set<String> = emptySet(),
    private val onItemClick: (PdfFile) -> Unit
) : RecyclerView.Adapter<RecentPdfAdapter.ViewHolder>() {

    var onDeleteClick: ((PdfFile) -> Unit)? = null
    var onFavoriteClick: ((PdfFile) -> Unit)? = null

    inner class ViewHolder(val binding: ItemRecentPdfBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentPdfBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pdf = pdfs[position]
        holder.binding.apply {
            tvTitle.text = pdf.title
            SubjectVisuals.applyToSubjectText(tvSubject, pdf.subject, pdf.title)
            tvFileMeta.text = listOfNotNull(
                formatTime(pdf),
                formatBytes(pdf.sizeBytes).takeIf { it.isNotBlank() }
            ).joinToString(" - ")

            val visuals = FileVisuals.getVisuals(pdf)
            tvFileBadge.text = visuals.label
            tvFileBadge.background = rounded(visuals.tint)
            
            iconContainer.background = ContextCompat.getDrawable(root.context, visuals.backgroundRes)
            ivFileIcon.setImageResource(visuals.iconRes)
            ivFileIcon.setColorFilter(visuals.tint)

            // LAB secondary tag
            tvLabTag.isVisible = FileVisuals.isLabResource(pdf)

            progressDownload.isVisible = false
            btnDownloadView.setOnClickListener { onItemClick(pdf) }

            val isFavorite = favoritePdfIds.contains(pdf.id)
            btnFavorite.setImageResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            btnFavorite.setColorFilter(if (isFavorite) ContextCompat.getColor(root.context, R.color.cm_accent) else ContextCompat.getColor(root.context, R.color.cm_text_secondary))

            btnFavorite.setOnClickListener {
                onFavoriteClick?.invoke(pdf)
            }

            root.setOnLongClickListener {
                if (isAdmin) {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onDeleteClick?.invoke(pdf)
                    true
                } else {
                    false
                }
            }

            root.applyClickAnimation {
                onItemClick(pdf)
            }
        }
    }

    override fun getItemCount(): Int = pdfs.size

    fun updateList(newList: List<PdfFile>, adminStatus: Boolean, newFavorites: Set<String>? = null) {
        val old = pdfs
        val oldFavs = favoritePdfIds
        pdfs = newList
        isAdmin = adminStatus
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
        val timestamp = pdf.timestamp ?: pdf.createdAt ?: return "Just now"
        return DateUtils.getRelativeTimeSpanString(
            timestamp.toDate().time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    private fun rounded(color: Int): GradientDrawable {
        val radius = 9f * android.content.res.Resources.getSystem().displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private object ToastCompat {
        fun show(view: android.view.View, text: String) {
            android.widget.Toast.makeText(view.context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
