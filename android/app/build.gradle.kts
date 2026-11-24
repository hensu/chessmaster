plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.hensu.chessmaster"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.hensu.chessmaster"
        minSdk = 26
        targetSdk = 36
        // Kept in step with the iOS app (project.yml MARKETING_VERSION); versionCode is Android's own.
        versionCode = 3
        versionName = "1.0.8"

        // Same Supabase project and GA4 property as the iOS app (project.yml). The anon key is
        // the public client key; row-level security protects the data.
        buildConfigField("String", "SUPABASE_URL", "\"https://ucpaqfjmicxfygzhquqo.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVjcGFxZmptaWN4ZnlnemhxdXFvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM4MzM4MTgsImV4cCI6MjA5OTQwOTgxOH0.2NYMHRdeqZk_vVAQX2DFb1CUxBL9JS1rpFZYDlR-G58\"")
        buildConfigField("String", "GA_MEASUREMENT_ID", "\"G-8JG1S0FTK3\"")
        buildConfigField("String", "GA_API_SECRET", "\"0IYnQrAISbyVEGewkpAxqg\"")

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // 16 KB memory pages (required by Google Play; NDK r27 needs the flag, r28+ does it by default).
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }

    // Stockfish: the same sources the iOS EngineKit package compiles (never copied).
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // The upload key (Play App Signing holds the real app key). Its path and passwords live in
    // ~/.gradle/gradle.properties, never in the repo; without them release builds are unsigned.
    val uploadStore = providers.gradleProperty("CHESSAI_UPLOAD_STORE_FILE").orNull
    signingConfigs {
        if (uploadStore != null) create("upload") {
            storeFile = file(uploadStore)
            storePassword = providers.gradleProperty("CHESSAI_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("CHESSAI_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("CHESSAI_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        // The engine is built optimized even in debug app builds (see CMakeLists.txt).
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    // One copy of the content for both apps: the iOS project's Resources/ folder is the Android
    // assets root (Learn/puzzles.json, Learn/lessons.json, NNUE/*.nnue, Onboarding/landing-demo.mp4).
    sourceSets["main"].assets.srcDirs("../../Chessmaster/Resources")

    androidResources {
        // The NNUE nets are copied out to a file once (Stockfish opens them by path); storing
        // them uncompressed keeps that copy fast. The video plays straight from the APK.
        noCompress += listOf("nnue", "mp4", "m4a")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Chess rules (ChessDomain/ChessKit.kt wraps it in chesskit-swift's shapes).
    implementation("com.github.bhlangonijr:chesslib:1.3.4")

    // Landing demo video and the music loop.
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")

    // Supabase (REST + auth) and GA4 Measurement Protocol, SDK-free like the iOS app.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Google / Apple sign-in through Supabase's web OAuth flow.
    implementation("androidx.browser:browser:1.8.0")

    // Plans (Google Play Billing) and the in-app review prompt.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("com.google.android.play:review-ktx:2.0.2")

    testImplementation("junit:junit:4.13.2")
}
