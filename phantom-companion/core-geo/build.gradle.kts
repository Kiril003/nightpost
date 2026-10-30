// PHANTOM Companion — `:core-geo` module.
//
// ГЕОПРОСТОРОВІ ШАРИ, ФОРМАТИ ДОСТАВКИ ТА ЛІЦЕНЗІЙНИЙ КОНТУР.
//
// Три речі, які має вирішувати карта комерційного продукту і які не вирішує
// жоден «просто тайловий клієнт»:
//
// 1. ДОСТАВКА БЕЗ СЕРВЕРА. PMTiles — це вся піраміда тайлів в одному файлі з
//    внутрішнім індексом на початку. Клієнт робить HTTP range-запит рівно на ті
//    байти, що відповідають конкретному z/x/y — або читає той самий файл з диска,
//    і тоді карта повністю працює офлайн. Тайловий сервер зникає як клас: немає
//    що глушити, немає за що платити, немає чому впасти під навантаженням.
//
// 2. ШАРИ ПОНАД ДОРОГАМИ. Метро і indoor (вестибюлі, рівні, переходи, ліфти,
//    ескалатори), залізниця, транзит із розкладом, камери контролю швидкості
//    разом із зонами середньої швидкості, екологічні та подієві реєстри. Кожен
//    шар описаний декларативно: джерело, теги, метод оновлення.
//
// 3. ЛІЦЕНЗІЙНИЙ ФАЄРВОЛ. Це не бюрократія, а умова існування продукту. ODbL
//    вимагає відкривати похідну базу даних — але не «створений твір» і не
//    незалежний горизонтальний шар. Отже межа між тим, що можна продавати, і
//    тим, що доведеться відкрити, проходить не по файлах, а по тому, чи
//    відбувається топологічне/аналітичне об'єднання з даними OSM. Тому статус
//    ліцензії тут — властивість шару в коді, а не примітка в README: власні дані
//    зв'язуються з картою лише за стабільним ідентифікатором на рівні рендерингу,
//    і жоден шар не може мовчки «заразити» комерційний контур.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.core.geo"
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
            // Robolectric дає справжній SQLite: основа з `tiles` як VIEW
            // (planetiler compact) читається тут так само, як на апараті.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-net"))

    // `api`, а не `implementation`: шар метро віддається назовні як
    // `SpatialLayerSpec` із `:core-data` — це справжній публічний тип цього
    // модуля, і споживач мусить його бачити, щоб узагалі скомпілювати виклик.
    api(project(":core-data"))

    // Єдиний споживач — `MetroStationTool`, адаптер до реєстру інструментів.
    // Тримається `implementation` навмисно: залежність не протікає далі, а
    // видалення того одного файла знімає її повністю.
    implementation(project(":core-symbiote"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
