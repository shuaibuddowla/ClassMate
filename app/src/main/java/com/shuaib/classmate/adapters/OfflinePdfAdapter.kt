package com.shuaib.classmate.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemOfflinePdfBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.FileVisuals
import com.shuaib.classmate.utils.applyClickAnimation
import java.util.Locale

class OfflinePdfAdapter(
    private var pdfs: List<PdfFile>,
    private val onItemClick: (PdfFile) -> Unit,
    private val onDeleteClick: (PdfFile) -> Unit
) : RecyclerView.Adapter<OfflinePdfAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemOfflinePdfBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfflinePdfBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pdf = pdfs[position]
        holder.binding.apply {
            tvTitle.text = pdf.title
            tvSubject.text = pdf.subject.ifBlank { "ClassMate Library" }
            
            val kb = pdf.sizeBytes / 1024.0
            val mb = kb / 1024.0
            tvOfflineSize.text = if (mb >= 1) {
                String.format(Locale.US, "%.1f MB (Offline)", mb)
            } else {
                String.format(Locale.US, "%.0f KB (Offline)", kb)
            }

            val timestamp = pdf.timestamp ?: pdf.createdAt
            if (timestamp != null) {
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    timestamp.toDate().time,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
                tvFileMeta.text = "• Saved $relativeTime"
            } else {
                tvFileMeta.text = ""
            }

            val visuals = FileVisuals.getVisuals(pdf)
            tvFileBadge.text = visuals.label
            tvFileBadge.background = rounded(visuals.tint)
            tvFileBadge.setTextColor(Color.WHITE)
            
            iconContainer.background = ContextCompat.getDrawable(root.context, visuals.backgroundRes)
            ivFileIcon.setImageResource(visuals.iconRes)
            ivFileIcon.setColorFilter(visuals.tint)

            btnDeleteCache.applyClickAnimation {
                onDeleteClick(pdf)
            }

            root.applyClickAnimation {
                onItemClick(pdf)
            }
        }
    }

    override fun getItemCount(): Int = pdfs.size

    fun updateList(newList: List<PdfFile>) {
        val old = pdfs
        pdfs = newList
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return old[oldItemPosition].id == newList[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return old[oldItemPosition] == newList[newItemPosition]
            }
        }).dispatchUpdatesTo(this)
    }

    private fun rounded(color: Int): GradientDrawable {
        val radius = 9f * android.content.res.Resources.getSystem().displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }
}
