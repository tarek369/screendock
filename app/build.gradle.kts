import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing credentials live in keystore.properties (repo root,
// gitignored). Absent file → release build stays unsigned (CI-friendly).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.cressu.screendock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cressu.screendock"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NFR-5: ABIs — arm64-v8a + armeabi-v7a for devices; x86_64 (emulator) is
        // added for debug builds only, keeping the release bundle small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Force the NDK + CMake the SDK has installed (see local.properties).
        ndkVersion = "27.0.12077973"

        externalNativeBuild {
            cmake {
                // Match jqssun/UxPlay Android convention.
                arguments += "-DANDROID_STL=c++_static" // single .so → no libc++_shared.so in the APK
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Pin the platform to minSdk so the NDK toolchain AND the
                // OpenSSL sub-build both target API 24 (NFR-3). Without this the
                // NDK defaults to the highest installed platform (android-35),
                // and OpenSSL emits fortified calls (__sendto_chk etc.) that
                // don't exist in the API-24 libc at link time.
                arguments += "-DANDROID_PLATFORM=android-24"
            }
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Native symbols are NOT embedded (they'd push the AAB past browser upload
            // limits); upload app/build/outputs/native-debug-symbols/release/*.zip to
            // Play Console → App bundle explorer for symbolication instead.
            ndk { debugSymbolLevel = "NONE" }
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen) // Launch splash (system splash on 31+, compat below)
    implementation(libs.billing.ktx) // Google Play Billing (Pro unlock IAP)
    implementation(libs.play.review.ktx) // Play In-App Review
    // Play Services pulls fragment 1.1.0 transitively; Play Console flags it as outdated.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.media3.exoplayer) // AirPlay video (HLS) playback
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended) // Rounded icon set for the redesigned UI
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
