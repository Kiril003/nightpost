// PHANTOM Companion v2 — `:feature-stream` module.
//
// Єдиний нескінченний потік (Stream UI) замість табів — ШІ генерує
// widget-картки на льоту в контексті діалогу.
//
// Per docs/MOBILE_ECOSYSTEM_VISION.md §4 (The Stream / Generative UI):
// оператор відкриває єдину прокручувану поверхню; ШІ аналізує наміри
// і будує інтерактивні widget-картки inline (фінанси, карта, код-ранер,
// транскрипція, тощо) — без статичних вкладок Pulse / Voice / Map /
// Comms / Vault.
//
// Phase 0 — empty scaffold so `:app` can already declare the dependency.
// Phase 1+ brings:
//   • [domain.StreamItem]    — sealed surface (text, widget, action, divider)
//   • [ui.StreamSurface]     — LazyColumn host з per-item theming
//   • [ui.WidgetRenderer]    — JSON-schema → Compose widget renderer
//   • [StreamViewModel]      — зв'язує intent ↔ AI router ↔ rendered items
//   • [ShareSheet]           — системний share-target → вставка в Stream
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.feature.stream"
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Кадри мапи (MapLookShotsTest) малюють справжні шрифти й рядки.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-net"))
    // HIGHWAY DOMINANCE — the headless navigator the map now merely observes.
    implementation(project(":core-nav"))
    implementation(project(":core-geo"))
    implementation(project(":core-design"))
    implementation(project(":core-ai"))
    implementation(project(":core-voice"))
    implementation(project(":core-vision"))
    implementation(project(":core-sensor"))
    implementation(project(":core-context"))
    implementation(project(":feature-godmode"))
    implementation(project(":core-symbiote"))
    implementation(project(":core-truth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    // MapGroundLayers instantiates OverpassClient's default HttpClient
    // directly (core-net's own ktor deps are `implementation`, not `api`,
    // so they don't leak here transitively) — same two lines core-symbiote
    // already needed for the identical reason.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Phase 1.5-B — Vault surface biometric ceremony. BiometricPrompt requires
    // a FragmentActivity host (MainActivity already extends FragmentActivity).
    implementation(libs.androidx.biometric)

    // QR: the invite has to be able to travel by camera, not by paste.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    // ARCore знято: жоден файл не імпортує com.google.ar, жоден маніфест його не
    // оголошує, а `ArMapWidget` — картка ПРО доповнену реальність, не її вікно.
    // У APK він доїжджав лише тому, що -keep у proguard-rules.pro не давав R8
    // прибрати 233 мертві типи.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-symbiote"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
