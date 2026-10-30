import java.io.File
import org.gradle.api.tasks.PathSensitivity

// PHANTOM Companion v2 — `:core-design` module.
//
// Design tokens, glass surfaces, Familiar avatar, animations
//
// Shared design-system: PhantomTheme, StateAccent, GlassCard, OrbView,
// StatePill, VitalsRow. All feature modules depend on this;
// no feature code lives here.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "local.phantom.companion.core.design"
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

    buildFeatures {
        compose = true
    }

    testOptions {
        // Композиція на JVM падає на єдиному місці — `android.os.Trace`, який
        // `ComposerImpl.doCompose` смикає на кожен прохід. Без цього прапорця
        // жоден тест не може ПОБАЧИТИ композицію, а лише перевірити прапорець
        // біля неї. Нових залежностей не додає: ворота ходять з `--offline`.
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Вартові в цьому модулі читають вихідники ІНШИХ модулів звичайним файловим
 * вводом — кирилицю в композаблах, парність перекладів, розміри шрифтів,
 * радіуси, тач-таргети. Двадцять чотири файли, і жоден із них Gradle не бачить
 * як вхід задачі.
 *
 * Наслідок був такий: після правки в `feature-stream` задача лишалась
 * UP-TO-DATE і НЕ ЗАПУСКАЛАСЬ, а її старе зелене подавалось як результат.
 * 30.08.2026 так проґавились 23 ключі без перекладу — вартий стояв, логіка
 * його була правильна, і він просто не біг. Лікувалось це рядком «ганяти з
 * `--rerun`» у KDoc кожного тесту, тобто **покладалось на памʼять людини**.
 *
 * Тут це сказано Gradle. Глобальний `--rerun` не годиться: він знищує
 * інкрементальність усієї збірки на машині, де збірки й так відстрілює сторож
 * памʼяті. Оголошуємо рівно те, що вартові справді читають — і задача
 * перезапускається сама, коли ці файли змінились, і лишається UP-TO-DATE,
 * коли ні.
 */
tasks.withType<Test>().configureEach {
    val foreign = rootProject.projectDir.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "src/main").isDirectory }
        .map { File(it, "src/main") }
    inputs.files(files(foreign))
        .withPropertyName("sourcesReadByFirewalls")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core-context"))
    // api, не implementation: LicenseFeature стоїть у сигнатурі Entitlements,
    // і потік екранів має бачити його, не тягнучи core-license окремо.
    api(project(":core-license"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    
    // Phase 4-E
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)
    implementation(libs.lottie.compose)

    testImplementation(libs.junit)
}
