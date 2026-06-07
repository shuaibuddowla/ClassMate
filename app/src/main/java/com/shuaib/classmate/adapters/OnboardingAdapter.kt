/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/adapters/OnboardingAdapter.kt
 */
package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemOnboardingSlideBinding
import com.shuaib.classmate.models.OnboardingSlide

class OnboardingAdapter(
    private val slides: List<OnboardingSlide>,
    private val rootView: ViewGroup? = null
) : RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder>() {

    inner class OnboardingViewHolder(val binding: ItemOnboardingSlideBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
        val binding = ItemOnboardingSlideBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return OnboardingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
        val slide = slides[position]
        holder.binding.apply {
            ivSlideLogo.setImageResource(slide.iconRes)
            tvTitle.text = slide.title
            tvSubtitle.text = slide.subtitle
        }
    }

    override fun getItemCount(): Int = slides.size
}
