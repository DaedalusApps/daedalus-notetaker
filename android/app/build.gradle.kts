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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
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

    // Local speech-to-text (on-device, no internet)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Whisper STT via sherpa-onnx (better accuracy, falls back to Vosk if not downloaded)
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    implementation(libs.documentfile)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
}

// Reinstall the app after instrumented tests (test runner uninstalls it as cleanup)
tasks.whenTaskAdded {
    if (name == "connectedDebugAndroidTest") {
        finalizedBy("installDebug")
    }
}
