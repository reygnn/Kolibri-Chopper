plugins {
    alias(libs.plugins.android.application)
    // No Kotlin plugin: AGP 9 built-in Kotlin compiles the sources.
    // No Compose, no serialization, no KSP, no Hilt — by design.
}

android {
    namespace = "com.github.reygnn.kolibri_chopper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.reygnn.kolibri_chopper"
        minSdk = 36                 // Android 16 only — no compat shims
        targetSdk = 36
        versionCode = 16
        versionName = "0.2.12"
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8 (full mode is the AGP 9 default)
            isShrinkResources = true    // strip unreferenced resources too
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 21 end to end.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    // Every buildFeature stays OFF (BuildConfig, Compose, viewBinding, …).
    // The Chopper has no generated code and no resource-backed binding.
}

// No PRODUCTION dependencies — that is the feature; the release APK is Kotlin
// stdlib + platform APIs only. The single entry below is testImplementation, so
// it lives on the unit-test classpath and never enters the shipped APK.
dependencies {
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath for the ConfigJson round-trip
    // (the android.jar org.json is a throwing stub). Test-only — not in the APK.
    testImplementation(libs.json)
}
