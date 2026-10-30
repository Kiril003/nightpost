// PHANTOM Companion v2 — `:core-context` module.
//
// ContextEngine mirror, DecisionTree v2, StateMachine
//
// Phase 0 (companion-v2-phase-0): only the marker `Module.kt` lives here.
// Real implementation lands in Phase 1+ per docs/MOBILE_PHANTOM_AMBITIOUS.md §11.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.core.context"
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
        )
    }
}

dependencies {
    // Phase 0 modules deliberately ship with zero deps so the build graph
    // stays minimal until Phase 1 wires real implementations.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
