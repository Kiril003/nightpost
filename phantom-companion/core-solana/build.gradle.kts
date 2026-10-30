import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key, "")

android {
    namespace = "local.phantom.companion.core.solana"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        // Через кому, у порядку спроби (Helius, Alchemy, Chainstack, Syndica…); публічний вузол — завжди останнім. Ключі лише в local.properties.
        buildConfigField("String", "RPC_DEVNET", "\"${prop("phantom.solanaRpcDevnet")}\"")
        buildConfigField("String", "RPC_MAINNET", "\"${prop("phantom.solanaRpcMainnet")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Кадри гаманця (WalletLookShotsTest) малюють справжні шрифти, рядки й знак.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-design"))
    // SignedDocument: знак Seeker підписано тим самим ключем і тим самим канонічним JSON, що й сертифікат ліцензії.
    implementation(project(":core-license"))
    // MWA 2.0.5 оголошує androidx.test.ext:junit-ktx як runtime-залежність: без виключення
    // в реліз їдуть junit, androidx.test і три тестові активності (одна з LAUNCHER).
    implementation(libs.solana.mwa.clientlib.ktx) {
        exclude(group = "androidx.test.ext")
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test>().configureEach {
    // Опт-ін: -Dphantom.devnet=1 вмикає DevnetCashProofTest (справжній devnet). Без нього звичайні ворота в мережу не ходять.
    listOf("phantom.devnet", "phantom.devnetHoldSec", "phantom.devnetAirdropTries", "phantom.devnetMint", "phantom.sponsorUrl").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
