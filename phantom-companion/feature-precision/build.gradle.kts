// PHANTOM Companion — `:feature-precision` module.
//
// ПОВЕРХНЯ, ЯКА ПОКАЗУЄ ПРАВДУ ПРО ПОЗИЦІЮ.
//
// Уся робота `:core-gnss`, `:core-indoor`, `:core-mobility` і `:core-geo`
// невидима за визначенням: вона виражається в тому, що точка НЕ стрибнула і що
// маршрут НЕ повів у поле. Користувач такого не помічає — аж поки не станеться
// збій, і тоді він не має жодного способу зрозуміти, чому додаток збрехав.
//
// Тому тут — єдиний екран, де стан навігації показано чесно і повністю:
// констеляція з C/N0 по кожному супутнику, які частоти реально прийнято,
// які супутники виключено і за що, звідки взято поточний розв'язок
// (супутники / числення / RTT / зіставлення з дорогою), і окремо — індикатор
// глушіння та підміни.
//
// Це не «діагностика для розробника». Для продукту, який продається як стійкий
// до РЕБ, видимий доказ стійкості — частина самого продукту: у момент, коли
// сусідній навігатор показує Перу, тут видно, що саме сталося і чому PHANTOM
// лишився в Києві.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "local.phantom.companion.feature.precision"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // Дорожній пакет міряється лише на апараті: bulkImport — це SQLite на
        // реальному сховищі, а клас швидкості видно тільки після справжнього
        // імпорту справжнього файлу.
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-design"))
    // `api`, а не `implementation`: PrecisionUiState/SatelliteRow виставляють у
    // ПУБЛІЧНОМУ API типи звідси (FixSource, GnssIntegrity, Band, Constellation
    // з :core-gnss; TransportMode з :core-mobility), тож споживач (:app) має їх
    // бачити транзитивно.
    api(project(":core-gnss"))
    api(project(":core-mobility"))
    // Так само `api`: PrecisionUiState виставляє PedestrianSnapshot і
    // RttAvailability звідси, тож споживач (:app) має бачити ці типи транзитивно.
    api(project(":core-indoor"))
    implementation(project(":core-geo"))
    // ОФЛАЙН-ДОРОГИ: імпорт дорожнього пакета йде через ПУБЛІЧНИЙ шов
    // RoadMeshStore.bulkImport(List<RoadWay>) з `:core-sensor`. Ми лише
    // ВИКЛИКАЄМО цей API — жоден файл `:core-sensor`/AppContainer не змінюється.
    implementation(project(":core-sensor"))
    // Phase 2 (map-plan) — дорожній пакет тепер можна попросити в ПК, не
    // лише внести вручну. `RoadPackLink`/`RoadPackLinkClient` живуть у
    // `:core-net` (той самий "process-wide holder" шов, що й GeoEndpoints),
    // бо `:feature-precision` не може залежати від `:app` (граф модулів іде
    // в інший бік) — а саме там живуть PairedDeviceStore/PhantomApi/PhantomLink.
    implementation(project(":core-net"))
    // ХМАРНІ ПАКЕТИ (05.09) — CloudPacksCard constructs `PackFetcher`/
    // `WorldPackCatalogClient` directly, and their public constructors name
    // `HttpClientEngine` in a defaulted param; Kotlin must resolve that type
    // to type-check the call even when the default is used, so it must be
    // on this module's compile classpath, not just `:core-net`'s internal one.
    implementation(libs.ktor.client.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // RoadPackImporter відкриває справжній SQLite-файл (PRAGMA user_version,
    // ways/stops/rails/routes, а тепер і транспортний шар) через
    // android.database — стуб android.jar цього не вміє, тож приймальні тести
    // читають СПРАВЖНІЙ файл тієї ж форми, яку пече mesh_bake.py, і для цього
    // потрібен Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Дорожній пакет міряється лише на апараті (RoadPackReachesTheRouterTest):
    // bulkImport — це SQLite на реальному сховищі телефона, а стеля A* видно
    // лише на справжній густині міста.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
