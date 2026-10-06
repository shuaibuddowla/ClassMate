import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
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

val firebaseRoleBridgeUrl = getLocalProperty("FIREBASE_ROLE_BRIDGE_URL")
    .ifBlank { "https://classmate-auth-bridge.vercel.app/api/firebase-role" }
val classmateTarget = providers.gradleProperty("classmateTarget").orNull
    ?: if (providers.gradleProperty("classmateStaging").orNull == "true") "staging" else "production"
require(classmateTarget in setOf("legacy", "staging", "production")) {
    "classmateTarget must be legacy, staging, or production"
}
val classmateUrl = if (classmateTarget == "staging")
    getLocalProperty("CLASSMATE_STAGING_URL") else getLocalProperty("SUPABASE_URL")
val classmateKey = if (classmateTarget == "staging")
    getLocalProperty("CLASSMATE_STAGING_PUBLISHABLE_KEY") else getLocalProperty("SUPABASE_PUBLISHABLE_KEY")
val updateBaseUrl = getLocalProperty("UPDATE_BASE_URL")
    .ifBlank { "https://github.com/shuaibuddowla/ClassMate/releases/latest/download" }
    .removeSuffix("/")
val releaseKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStorePassword = System.getenv("CLASSMATE_STORE_PASSWORD")
    ?.takeIf { it.isNotBlank() } ?: releaseKeystore.getProperty("storePassword")
val releaseKeyPassword = System.getenv("CLASSMATE_KEY_PASSWORD")
    ?.takeIf { it.isNotBlank() } ?: releaseKeystore.getProperty("keyPassword")
val releaseSigningReady = !releaseKeystore.getProperty("storeFile").isNullOrBlank() &&
    !releaseKeystore.getProperty("keyAlias").isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.shuaib.classmate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shuaib.classmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "1.1.27"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_BASE_URL", "\"${getLocalProperty("BACKEND_BASE_URL")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${getLocalProperty("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${getLocalProperty("SUPABASE_PUBLISHABLE_KEY")}\"")
        buildConfigField("boolean", "CLASSMATE_AUTH_ENABLED", (classmateTarget != "legacy").toString())
        buildConfigField("String", "CLASSMATE_ENV", "\"$classmateTarget\"")
        buildConfigField("String", "CLASSMATE_URL", "\"$classmateUrl\"")
        buildConfigField("String", "CLASSMATE_PUBLISHABLE_KEY", "\"$classmateKey\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        buildConfigField("String", "FIREBASE_ROLE_BRIDGE_URL", "\"$firebaseRoleBridgeUrl\"")
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"${getLocalProperty("ONESIGNAL_APP_ID")}\"")
        buildConfigField("String", "TELEGRAM_CHANNEL_ID", "\"${getLocalProperty("TELEGRAM_CHANNEL_ID")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"gemini-2.5-flash\"")
        buildConfigField("String", "GROQ_MODEL", "\"llama-3.3-70b-versatile\"")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("classmateRelease") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseStorePassword
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("classmateRelease")
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
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.android)
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
