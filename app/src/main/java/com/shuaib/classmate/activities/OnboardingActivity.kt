package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.shuaib.classmate.activities.LoginActivity
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.OnboardingAdapter
import com.shuaib.classmate.databinding.ActivityOnboardingBinding
import com.shuaib.classmate.models.OnboardingSlide
import com.shuaib.classmate.utils.AppPreferences
import com.shuaib.classmate.utils.applyClickAnimation

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val slides = listOf(
            OnboardingSlide(
                R.drawable.ic_classmate_logo,
                "Academic Command",
                "Timetable, notices, resources, and attendance in one polished class workspace."
            ),
            OnboardingSlide(
                R.drawable.ic_classmate_logo_mark,
                "Trusted Updates",
                "Important class changes and department notices arrive with clarity and speed."
            ),
            OnboardingSlide(
                R.drawable.ic_classmate_logo_mark,
                "Study Library",
                "Lecture files, polls, and academic records stay organized for everyday use."
            )
        )

        val adapter = OnboardingAdapter(slides)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { _, _ -> }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.btnNext.text = if (position == slides.size - 1) "Enter Academy" else "Next"
            }
        })

        binding.btnNext.applyClickAnimation {
            if (binding.viewPager.currentItem < slides.size - 1) {
                binding.viewPager.currentItem += 1
            } else {
                completeOnboarding()
            }
        }
    }

    private fun completeOnboarding() {
        AppPreferences(this).setOnboardingComplete()
        startActivity(Intent(this, com.shuaib.classmate.activities.LoginActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }
}
