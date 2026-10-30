// PHANTOM Companion v2 — `:feature-godmode` module.
//
// Глибока системна інтеграція Android — "God Mode" per
// docs/MOBILE_ECOSYSTEM_VISION.md §4.2 (System Depth / God Mode):
//
//   • AccessibilityService — Screen Awareness: читає UI дерево будь-якого
//     додатка і передає контекст до ШІ-маршрутизатора.
//   • NotificationListenerService — агрегація месенджерів: WhatsApp,
//     Telegram, Signal, Gmail → єдина черга вхідних у Stream.
//   • TelecomManager / InCallService — AI-секретар вхідних дзвінків
//     (Tier 2.5): транскрипція + автовідповідь + підказки оператору.
//   • Tasker-style тригери — geo-fence / time / sensor / event → дія.
//
// Phase 0 — empty scaffold so `:app` can already declare the dependency.
// Phase 1+ brings:
//   • [service.PhantomAccessibilityService] — window change → ScreenContext
//   • [service.PhantomNotificationListener] — notification → StreamItem
//   • [service.PhantomInCallService]        — call → AI secretary loop
//   • [engine.ScreenContextEngine]          — парсить accessibility tree
//   • [engine.TaskerEngine]                 — тригери + виконання дій
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "local.phantom.companion.feature.godmode"
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
    implementation(project(":core-design"))
    implementation(project(":core-ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)

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
