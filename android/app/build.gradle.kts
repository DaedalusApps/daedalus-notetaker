import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

val versionPropsFile = file("../version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        load(FileInputStream(versionPropsFile))
    }
}

// Get version code from git commit count, fallback to property file
val gitCommitCount = try {
    Runtime.getRuntime().exec("git rev-list --count HEAD").inputStream.bufferedReader().readText().trim().toInt()
} catch (e: Exception) {
    versionProps.getProperty("BUILD_NUMBER", "1").toInt()
}

val vName = versionProps.getProperty("VERSION_NAME", "1.0.0")

android {
    namespace   = "com.daedalus.notes"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.daedalus.notes"
        minSdk          = 26
        targetSdk       = 35
        versionCode     = gitCommitCount
        versionName     = vName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.ext)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.coroutines.android)

    // Room (KSP instead of kapt — faster, no daemon issues)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // HTTP (model download only)
    implementation(libs.okhttp)

    // On-device AI — MediaPipe LLM Inference (tasks-genai .bin format models)
    implementation(libs.mediapipe.genai)

    // Audio playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.documentfile)

    debugImplementation(libs.compose.ui.tooling)
}
