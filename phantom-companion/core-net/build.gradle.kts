// PHANTOM Companion v2 — `:core-net` module.
//
// Owners (Phase 1+):
//   • crypto/  — Argon2id PIN hasher, Ed25519 + X25519 keypairs, HKDF-SHA256,
//                AES-256-GCM. Mirrors `phantom-companion/src/services/cryptoUtils.ts`
//                so the desktop pair-protocol contract stays a one-liner port
//                away from the WebCrypto reference. BouncyCastle backs every
//                primitive — no native libs, no Keystore prerequisites at the
//                primitive layer (callers integrate Keystore/StrongBox above).
//   • pair/    — Phase 1-C-4-a pair-flow client. PairingQr decoder,
//                PairProofs (HKDF + HMAC mirror of pair_crypto.py),
//                PairFlowClient (Ktor → POST /pair/claim). The QR's
//                `server_cert_sha256 == "dev-no-pin"` toggles cleartext;
//                production builds get HTTPS via the system trust store.
//
// Future (Phase 2+):
//   • WS link + cert pinning
//   • JWT manager + refresh
//
// Tests are pure JVM — every primitive in `crypto/` runs on BouncyCastle's
// lightweight API; the Ktor surface is exercised via `ktor-client-mock` so
// no real network is required for green CI.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.core.net"
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
    // Підпис світових пакiв звіряє той самий SignedDocument, що й ліцензійний
    // сертифікат: друга своя канонізація розійшлася б із серверною мовчки.
    // :core-license не має жодного власного project-ребра, тож циклу немає.
    implementation(project(":core-license"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // BouncyCastle backs every primitive in `crypto/`. The lightweight API
    // (org.bouncycastle.crypto.*) keeps the dep footprint small and the JVM
    // tests deterministic — no JCA-provider registration dance required.
    implementation(libs.bouncycastle)

    // Ktor — pair-flow client (Phase 1-C-4-a). OkHttp engine matches the
    // PWA's runtime behaviour; the alternative CIO engine adds non-trivial
    // SSL surface area we don't need on the phone-side. Content
    // negotiation + kotlinx-json deserializes /pair/claim responses.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.junit)
    // Запрошення мусить проходити крізь камеру — це доводиться
    // замкнутим колом кодування→декодування, а не оком.
    testImplementation(libs.zxing.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
