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
        // CI/release set these from git (commit count = always-increasing versionCode, tag = name),
        // so every new build installs over the previous one.
        versionCode = (System.getenv("THORBOX_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("THORBOX_VERSION_NAME") ?: "0.0.0-dev"
        ndk { abiFilters += ((findProperty("thorbox.abis") as String?)?.split(",") ?: listOf("arm64-v8a")) }
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
        // Debug builds use the release key when it's available (CI with secrets), so a debug APK
        // and a release APK can update each other. Otherwise: the local, per-machine debug key.
        debug {
            // No key → unsigned (can't be installed), unless CI explicitly allows a temporary debug signature.
            signingConfig = signingConfigs.findByName("release")
                ?: if (System.getenv("THORBOX_ALLOW_DEBUG_SIGNING") == "1") signingConfigs.getByName("debug") else null ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        textReport = true
        textOutput = file("stdout")          // print every finding in the CI log, not just the first
        abortOnError = true
        checkDependencies = true             // also lint :engine
    }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
