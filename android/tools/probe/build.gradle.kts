// Standalone test harness: a minimal app that exercises system audio (Shizuku capture ->
// NativeDsp passthrough -> AudioTrack) and the in-app player in isolation, so a capture
// failure is never confused with a DSP failure. Not shipped; docs/device-tests.md uses it.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.theiceji.viper4shizuku.probe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.theiceji.viper4shizuku.probe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":capture-shizuku"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}
