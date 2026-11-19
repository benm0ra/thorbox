// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.thorbox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thorbox.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Release signing comes from keystore.properties (git-ignored), or from environment variables in CI.
    // Without either, release builds are left unsigned rather than silently using the public debug key.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun ks(key: String, env: String): String? = ksProps.getProperty(key) ?: System.getenv(env)
    val releaseStore = ks("storeFile", "THORBOX_KEYSTORE")
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = ks("storePassword", "THORBOX_KEYSTORE_PASSWORD")
            keyAlias = ks("keyAlias", "THORBOX_KEY_ALIAS")
            keyPassword = ks("keyPassword", "THORBOX_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = false } }   // page-aligned, uncompressed .so (faster load)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core-model"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Installs the baseline profiles that Compose and AndroidX ship, so sideloaded builds start fast
    // and scroll without first-run jank (Play does this automatically; sideloads need this).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
