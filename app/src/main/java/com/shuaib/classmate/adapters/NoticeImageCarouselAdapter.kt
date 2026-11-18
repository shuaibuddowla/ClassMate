package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemNoticeCarouselImageBinding

class NoticeImageCarouselAdapter(
    private val imageUrls: List<String>,
    private val onImageClick: (Int) -> Unit
) : RecyclerView.Adapter<NoticeImageCarouselAdapter.CarouselViewHolder>() {

    inner class CarouselViewHolder(val binding: ItemNoticeCarouselImageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
        val binding = ItemNoticeCarouselImageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CarouselViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
        val url = imageUrls[position]
        Glide.with(holder.itemView.context)
            .load(url)
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(R.drawable.bg_notice_modern_card)
            .centerCrop()
            .into(holder.binding.ivCarouselItem)

        holder.itemView.setOnClickListener {
            onImageClick(position)
        }
    }

    override fun getItemCount(): Int = imageUrls.size
}
