plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cesia.rime"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 只有在 librime 头文件存在时才启用 NDK 编译
    val librimeHeadersDir = file("src/main/cpp/include")
    if (librimeHeadersDir.exists() && librimeHeadersDir.listFiles()?.isNotEmpty() == true) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        buildFeatures {
            prefab = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}