// PHANTOM Companion — `:core-mobility` module.
//
// ВИЗНАЧЕННЯ РЕЖИМУ ПЕРЕСУВАННЯ (TMD).
//
// Симбіонт, який однаково говорить із водієм, пасажиром метро і пішоходом —
// це симбіонт, який не розуміє жодного з них. Перш ніж вирішувати, ЩО сказати,
// система має знати, ЯК людина зараз рухається.
//
// Штатний Android Activity Recognition для цього замалий: він плутає велосипед
// із автомобілем у міському трафіку і має завелику латентність на межах поїздки.
// Тому тут власний конвеєр:
//
//   • вікна 18 с при стабільних 10 Гц (інтерполяція сирого змінного потоку
//     акселерометра) — свідомо низька частота, бо тримати GPS чи 100 Гц заради
//     класифікації означає з'їсти батарею за півдня;
//   • ознаки вікна → класифікатор режиму (інтерфейс: евристика зараз, нейромодель
//     потім — контракт один і той самий);
//   • часова сегментація поверх послідовності вікон: межа поїздки визначається
//     не одним вікном, а сегментом, тому короткий світлофор не породжує фантомну
//     «зупинку», а вихід з авто не лишається непоміченим.
//
// Окремо — режими, які неможливо визначити з самого акселерометра:
//   • літак: інтеграл прискорення на розгоні + барометричний профіль
//     герметичної кабіни (штучна «висота» 1800–2400 м) — працює без GPS і зв'язку;
//   • потяг: зіставлення траєкторії з графом залізниці фільтром частинок,
//     бо рейка має жорсткі кінематичні обмеження, яких немає в автодороги;
//   • судно: водний полігон + характерний діапазон швидкості.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.mobility"
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
