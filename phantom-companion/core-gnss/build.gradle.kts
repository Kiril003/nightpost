// PHANTOM Companion — `:core-gnss` module.
//
// СИРІ СУПУТНИКОВІ ВИМІРЮВАННЯ ТА ЦІЛІСНІСТЬ СИГНАЛУ.
//
// Штатний Android `Location` — це вже пережований чіпсетом розв'язок: згладжений,
// притягнутий до доріг і без жодного сліду того, ЯК він отримався. Під РЕБ це
// найгірший з можливих входів — підмінений сигнал приходить сюди як звичайний
// «точний» fix, і жоден фільтр поверх нього вже не відрізнить правду від брехні.
//
// Тому цей модуль працює на рівень нижче: `GnssMeasurementsEvent` — псевдовідстані,
// фази несучої (ADR), доплер, C/N0 по кожному супутнику і кожній частоті. З цього
// будується те, чого немає у `Location`:
//
//   • TDCP — різниця фаз між епохами дає зміщення з міліметровою роздільністю,
//     тобто плавний трек без стрибків координати;
//   • FDE — супутник, чий внесок суперечить інерціальному вектору руху, просто
//     викидається з розв'язку. Саме це ламає спуфінг: підміна дає різкий зріст
//     нев'язки відносно IMU;
//   • адаптивна коваріація за C/N0 — вага супутникових факторів плавно падає при
//     наближенні до джерела завад, замість стрибка розрахованої позиції;
//   • метрики глушіння/підміни — обвал C/N0 по всій констеляції, стрибок зсуву
//     годинника, аномалія розподілу — окремий сигнал для верхніх шарів.
//
// Модуль навмисно не залежить від інших модулів проєкту: уся математика — чистий
// Kotlin над простими типами, тож кожен поріг і кожен виняток перевіряється
// JVM-тестом без пристрою в контурі.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.gnss"
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
        )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
