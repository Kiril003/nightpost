// PHANTOM Companion — `:core-nav` module.
//
// HIGHWAY DOMINANCE, mandate 1 — the headless navigator.
//
// Guidance used to live inside `MapViewModel`, on `viewModelScope`. That was
// survivable while the only consumer was a Compose screen, and fatal the moment
// a second head appeared: on Android Auto the phone's Activity is usually not
// running at all, so a ViewModel-scoped route would mean the car projects a
// navigator that does not exist.
//
// So the route, the map-matching, the maneuver cues and the off-route replan
// all live here — in a module with no Compose, no Activity, no ViewModel and
// no Android UI of any kind. `NavigationEngine` is a plain object with an
// injected scope; `:app` holds exactly one, for the life of the process, and
// both heads (the Continuum UI and the car's NavigationTemplate) *observe* it.
// Neither owns it. Neither can kill it by being backgrounded.
//
// Everything here is pure Kotlin over `:core-net`'s OSRM types, so every rule
// — every cue distance, every off-route hysteresis threshold — is a JVM unit
// test with no device in the loop.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.nav"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // `api`, not `implementation`: RouteVariant / RouteProfile / RouteStep are
    // all over this module's *public* surface (GuidanceSession consumes a
    // variant; NavState publishes one), so every consumer needs to see them.
    api(project(":core-net"))
    implementation(project(":core-data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
