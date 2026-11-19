// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thorbox.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
        ndk { abiFilters += ((findProperty("thorbox.abis") as String?)?.split(",") ?: listOf("arm64-v8a")) } // Thor is arm64 only. One ABI also keeps builds fast.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        // Debug builds of the native engine are -O0 and can glitch at small buffer sizes.
        // Build the native code optimized even in debug, so latency tests mean something.
        debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } } }
        release { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures { prefab = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
}
