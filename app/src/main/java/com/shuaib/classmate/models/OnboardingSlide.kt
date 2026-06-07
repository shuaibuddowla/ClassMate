/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/models/OnboardingSlide.kt
 * Data class for onboarding slides.
 */
package com.shuaib.classmate.models

import androidx.annotation.DrawableRes

data class OnboardingSlide(
    @DrawableRes val iconRes: Int,
    val title: String,
    val subtitle: String
)
