// PHANTOM Companion v2 — `:feature-ambient` module.
//
// "Always-present UI" — Ambient layer per
// docs/MOBILE_ECOSYSTEM_VISION.md §4.3 (Ambient / Always-Present):
//
//   • Floating Orb overlay (SYSTEM_ALERT_WINDOW) — постійно присутній
//     на екрані незалежно від активного додатка; анімується у відповідь
//     на стан ШІ (думає / слухає / говорить / тривога).
//   • AOD (Always-On Display) lock-screen widget — PHANTOM-статус і
//     ключові сповіщення навіть коли телефон заблокований.
//   • Whisper Line — смуга присутності поверх будь-якого застосунку.
//   • Живі шпалери, що дихають у кольорі поточного SystemState.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "local.phantom.companion.feature.ambient"
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-net"))
    implementation(project(":core-context"))
    implementation(project(":core-design"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
