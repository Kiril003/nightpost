// PHANTOM Companion v2 — `:core-sensor` module.
//
// MobileSensorAdapter (GPS+IMU+mic_rms+BLE+WiFi+PPG)
//
// Phase 0 (companion-v2-phase-0): only the marker `Module.kt` lives here.
// Real implementation lands in Phase 1+ per docs/MOBILE_PHANTOM_AMBITIOUS.md §11.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.sensor"
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

// `-Dphantom.tripsDir=…` на командному рядку живе у ДЕМОНІ Gradle, а тести
// біжать в окремій JVM і його не бачать — без цього містка прилад
// `TurnCostFieldMeasureTest` мовчки пропускався, скільки б його не запускали.
tasks.withType<Test>().configureEach {
    providers.systemProperty("phantom.tripsDir").orNull?.let {
        systemProperty("phantom.tripsDir", it)
    }
    // Той самий місток для приладу графа поворотів (TurnGraphMeasureTest).
    providers.systemProperty("phantom.routeMeasure").orNull?.let {
        systemProperty("phantom.routeMeasure", it)
    }
    providers.systemProperty("phantom.cityTsv").orNull?.let {
        systemProperty("phantom.cityTsv", it)
    }
    // Синтетичне місто (~0,6 млн вершин) і його індекс не влазять у типові
    // 512 МБ тестової JVM; 3 ГБ — з запасом на VM з 16 ГБ.
    maxHeapSize = "3g"
}

dependencies {
    implementation(project(":core-indoor"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Дорожнє сховище — SQLiteOpenHelper, тобто без Android його не збудувати,
    // а шов «пакет прийшов і мережа змінилась» треба стерегти саме на ньому.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
