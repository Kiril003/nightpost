// PHANTOM Companion — `:core-indoor` module.
//
// ПОЗИЦІОНУВАННЯ ТАМ, ДЕ СУПУТНИКА НЕМАЄ ВЗАГАЛІ.
//
// `:core-gnss` бореться за супутниковий розв'язок, поки той хоч якось існує. Цей
// модуль починається там, де той бій програно остаточно: підземний паркінг,
// вестибюль метро, перегін між станціями, тунель, глухий двір-колодязь — або
// повне придушення діапазону.
//
// Три незалежні джерела, жодне з яких не використовує радіодіапазон супутників:
//
//   • PDR — числення шляху пішохода. Свідомо двовимірна модель похибки (довжина
//     кроку + курс) замість 15-вимірного вектора стану EKF: на шумних MEMS
//     телефона повний фільтр накопичує похибку швидше, ніж встигає її оцінити.
//   • Wi-Fi RTT (802.11mc FTM) — вимірює час проходження пакета, а не потужність,
//     тому дає фізичну відстань до точки доступу з точністю 10–50 см навіть при
//     перевідбиттях, де RSSI-фінгерпринтинг безнадійний.
//   • Барометр — вертикальна складова: поверх, рівень, глибина закладення.
//
// Ключова частина — суміщення. Дискретні вимірювання RTT і безперервна, але
// схильна до дрейфу траєкторія PDR зводяться генетичним алгоритмом: траєкторія
// PDR розглядається як цілісна геометрична крива, і шукається перетворення
// подібності (зсув, поворот, масштаб), що найкраще садить її на RTT-якорі. Це
// знімає залежність від точного знання координати на вході під землю.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "local.phantom.companion.core.indoor"
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
