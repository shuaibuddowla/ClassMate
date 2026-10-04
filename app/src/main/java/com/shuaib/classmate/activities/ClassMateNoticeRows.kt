package com.shuaib.classmate.activities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

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
    override fun onBindViewHolder(holder: Holder, position: Int) = getItem(position).bind(holder.root)
    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.root.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(com.shuaib.classmate.R.id.shimmerOlderNotices)?.stopShimmer()
        super.onViewDetachedFromWindow(holder)
    }
    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        holder.root.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(com.shuaib.classmate.R.id.shimmerOlderNotices)
            ?.takeIf { it.visibility==View.VISIBLE }?.startShimmer()
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
