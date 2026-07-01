import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun getLocalProperty(name: String): String {
    return localProperties.getProperty(name)?.trim() ?: ""
}

android {
    namespace = "com.shuaib.classmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shuaib.classmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_LIBRARY_TOKEN", "\"${getLocalProperty("GITHUB_LIBRARY_TOKEN")}\"")
        buildConfigField("String", "GITHUB_OWNER", "\"${getLocalProperty("GITHUB_OWNER")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${getLocalProperty("GITHUB_REPO")}\"")
        buildConfigField("String", "GITHUB_RELEASE_TAG", "\"${getLocalProperty("GITHUB_RELEASE_TAG")}\"")
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"${getLocalProperty("ONESIGNAL_APP_ID")}\"")
        buildConfigField("String", "ONESIGNAL_REST_API_KEY", "\"${getLocalProperty("ONESIGNAL_REST_API_KEY")}\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${getLocalProperty("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("String", "TELEGRAM_CHANNEL_ID", "\"${getLocalProperty("TELEGRAM_CHANNEL_ID")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${getLocalProperty("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${getLocalProperty("GROQ_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"gemini-2.5-flash\"")
        buildConfigField("String", "GROQ_MODEL", "\"llama-3.3-70b-versatile\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // OneSignal — version range specified directly (not via catalog)
    implementation("com.onesignal:OneSignal:[5.6.1, 5.9.99]")

    // Firebase BOM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.storage)
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.itextpdf:itextpdf:5.5.13.3")

    // HTTP client for Cloudinary
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Room local cache
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // AI
    implementation(libs.google.generativeai)
    implementation(libs.gson)

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // UI essentials
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.activity)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.browser:browser:1.8.0")

    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Lottie
    implementation("com.airbnb.android:lottie:6.4.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // CircleImageView
    implementation("de.hdodenhof:circleimageview:3.1.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    // Dynamic Animation
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.0.0-alpha03")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Shimmer
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    // Markwon Markdown
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.html)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.linkify)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}