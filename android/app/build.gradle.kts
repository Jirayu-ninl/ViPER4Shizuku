import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

android {
    namespace = "dev.theiceji.viper4shizuku"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.theiceji.viper4shizuku"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
    }

    val keystoreFile = localProps.getProperty("KEYSTORE_FILE", "")
    if (keystoreFile.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProps.getProperty("KEY_ALIAS", "")
                keyPassword = localProps.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreFile.isNotEmpty()) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Every assemble<Variant> also copies its APK out of android/ to <repo>/builds/<versionName>/,
// e.g. builds/0.1.0/ViPER4Shizuku-0.1.0-release.apk, so each built version is kept outside the
// project.
val exportDir = rootProject.layout.projectDirectory.dir("../builds")
androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val version = android.defaultConfig.versionName ?: "unversioned"
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val export =
            tasks.register<Copy>("export${capitalized}Apk") {
                from(variant.artifacts.get(SingleArtifact.APK)) { include("*.apk") }
                into(exportDir.dir(version))
                rename { "ViPER4Shizuku-$version-$variantName.apk" }
            }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach { finalizedBy(export) }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.android)

    implementation(project(":engine"))
    // System audio: the Shizuku capture service runs from this APK as the shell uid.
    implementation(project(":capture-shizuku"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
