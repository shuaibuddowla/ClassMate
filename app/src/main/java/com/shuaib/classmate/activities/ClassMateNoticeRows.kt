package com.shuaib.classmate.activities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.shuaib.classmate.R

/** Immutable comparison keys let RecyclerView retain unchanged cards and image requests. */
internal data class ClassMateNoticeRow(
    val key: String,
    val layout: Int,
    val signature: String,
    val bind: (View) -> Unit,
)

internal class ClassMateNoticeRows : ListAdapter<ClassMateNoticeRow, ClassMateNoticeRows.Holder>(DIFF) {
    class Holder(val root: View) : RecyclerView.ViewHolder(root)
    init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    override fun getItemViewType(position: Int) = getItem(position).layout
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        getItem(position).bind(holder.root)
    }
    override fun onViewDetachedFromWindow(holder: Holder) {
        runCatching {
            holder.root.findViewById<ShimmerFrameLayout>(R.id.shimmerOlderNotices)?.stopShimmer()
        }
        super.onViewDetachedFromWindow(holder)
    }
    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        runCatching {
            val shimmer = holder.root.findViewById<ShimmerFrameLayout>(R.id.shimmerOlderNotices)
            if (shimmer != null && shimmer.visibility == View.VISIBLE && ViewCompat.isAttachedToWindow(shimmer)) {
                shimmer.startShimmer()
            }
        }
    }
    fun noticeId(position: Int): String? = currentList.getOrNull(position)?.key
        ?.takeIf { it.startsWith("notice:") }?.removePrefix("notice:")
    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ClassMateNoticeRow>() {
            override fun areItemsTheSame(old: ClassMateNoticeRow, new: ClassMateNoticeRow) =
                old.key == new.key && old.layout == new.layout
            override fun areContentsTheSame(old: ClassMateNoticeRow, new: ClassMateNoticeRow) =
                old.signature == new.signature
        }
    }
}
