// PHANTOM Companion v2 — `:core-voice` module.
//
// Phase 2-B-1: Android System TTS (uk-UA) as immediate working path.
// Phase 2-B-2: Supertonic 3 ONNX inference (Supertone/supertonic-3), streaming playback.
//
// Non-UI core module — zero Compose deps by design.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.voice"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    androidResources {
        noCompress += "ogg"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // STT
    implementation(libs.vosk.android)
    implementation(libs.onnxruntime.android)

    // Supertonic asset parsing (tts.json / unicode_indexer.json / voice
    // styles) — JsonElement API only, no @Serializable, so the runtime
    // library suffices without the compiler plugin.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Device proof for MoonshineSttEngine (real ONNX on real ARM) — see
    // MoonshineSttEngineDeviceTest. Skips itself when the model files it
    // needs are not on the device; never runs in ./scripts/verify.sh.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
