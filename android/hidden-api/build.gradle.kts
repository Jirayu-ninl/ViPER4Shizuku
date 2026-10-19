// Compile-only stubs for @SystemApi classes that the public android.jar leaves out.
// Consumers depend on this with compileOnly, so nothing here ends up in an APK: at runtime the
// real framework classes are used (the capture service runs as the shell uid, which may call them).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.theiceji.viper4shizuku.hiddenapi"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
