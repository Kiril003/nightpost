// PHANTOM Companion — `:feature-car` module.
//
// HIGHWAY, mandate 3 — the car head.
//
// Android Auto does not run our UI. The host process draws the screen from
// *templates* we hand it, in a car-certified layout we do not control, with a
// hard cap on how much a driver may be shown at once. So this module contains
// no Compose, no Activity and no ViewModel — it cannot, and the absence of
// those dependencies is the enforcement.
//
// It also owns no state. Guidance belongs to the headless navigator; the car
// is a *head*, and a head that disagrees with the phone about where the driver
// is going is worse than no head at all. Everything this module needs from the
// app arrives through [CarPorts] — a seam the `:app` module fills in.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.feature.car"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests { isReturnDefaultValues = true }
    }
}

dependencies {
    api(libs.androidx.car.app)
    // The projected (phone-screen-in-the-car) host. Automotive OS head units
    // would additionally need `app-automotive`; that is a separate product
    // surface and is deliberately NOT pulled in here.
    implementation(libs.androidx.car.app.projected)

    // Guidance state for the turn-by-turn screen; GuidanceHub is the only
    // door, since the car head has no Activity to reach a ViewModel through.
    implementation(project(":core-nav"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
