// PHANTOM Companion v2 — `:core-data` module.
//
// Owners: Room (non-secret operational state) + SQLCipher Vault wrapper +
// the multi-profile store described in docs/MOBILE_PHANTOM_AMBITIOUS.md §4.
//
// Phase 1 (Identity) wires:
//   • Profile / PairedHost domain models (kotlinx.serialization-friendly).
//   • Room schema for `profiles` + `paired_hosts` (1:N, cascade delete).
//   • PhantomDatabase opens the SQLite file via SQLCipher's
//     `SupportOpenHelperFactory` so the on-disk DB is encrypted with a
//     per-install 32-byte random key sealed in EncryptedSharedPreferences
//     (Keystore-wrapped).
//   • ProfileStore facade owns the Entity ↔ Domain mapping and the
//     "exactly one active profile" invariant.
//
// Tests live in `src/test/` (pure JVM, no Robolectric required for the
// scope of the data classes / mappers shipped here). Schema-level Room
// tests will land as `src/androidTest/` when we have the on-device CI;
// for now KSP exports the schema to `schemas/` so migration diffs stay
// visible in code review.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "local.phantom.companion.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // The Room `androidTest` source set reads the JSON schema files
        // KSP exports below — making the version-pinned schema available
        // to migration tests when those land.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
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
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    // Export the Room schema to a checked-in directory so migration
    // changes are reviewable in PRs (see docs/MOBILE_PHANTOM_AMBITIOUS.md
    // §4 "Anti-pattern guardrails" — the database is per-profile and
    // schema drift between Phase 1 and later phases must be tracked).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(project(":core-net"))
    implementation(project(":core-ai"))
    // Підпис пака країни звіряється тим самим SignedDocument, що й ліцензійний
    // сертифікат: одна канонізація, один Ed25519, жодної другої криптографії.
    implementation(project(":core-license"))

    // BouncyCastle stays a runtime dep here because Argon2id (Phase 1
    // C.2 PinHasher) lives in the same module — it operates on Profile
    // mutations and the salt persists alongside the entity.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Ворота їдуть на ARM, де Robolectric не має рідного SQLite; пакет кузні
    // читається тим самим кодом через JDBC-двійник PackDb.
    testImplementation(libs.sqlite.jdbc)
}
