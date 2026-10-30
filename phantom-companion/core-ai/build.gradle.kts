// PHANTOM Companion v2 — `:core-ai` module.
//
// Owners:
//   • AIIntent / AIChunk / AIRouter / RoutingStrategy — public contract
//   • route/GeminiCloudRoute   — Gemini 2.5 Flash SSE streaming (Phase 1-D-1)
//   • route/GemmaLocalRoute    — MediaPipe LLM Inference (Phase 1-D-2)
//   • route/RoutedAIRouter     — composite router with automatic fallback
//
// No Compose, no Room, no Context — pure Ktor + coroutines + serialization.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.core.ai"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor — SSE streaming to Gemini REST API. OkHttp engine matches
    // :core-net so the dep tree stays consistent across modules.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // MediaPipe GenAI (Phase 1-D-2)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
