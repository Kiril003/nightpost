// PHANTOM Companion v2 — `:core-vision` module.
//
// MediaPipe Hands+FaceMesh, ML Kit OCR, ARCore overlay
//
// Phase 0 (companion-v2-phase-0): only the marker `Module.kt` lives here.
// Real implementation lands in Phase 1+ per docs/MOBILE_PHANTOM_AMBITIOUS.md §11.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "local.phantom.companion.core.vision"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // mediapipe-tasks-vision знято разом із MediaPipeVisionEngine: у нього не було
    // жодного виклику, а моделі face_landmarker.task / hand_landmarker.task, яких
    // він вимагає, не лежать ніде в репозиторії — тобто він не запрацював би й тоді,
    // якби його хтось покликав. Ціна була 14,03 МБ libmediapipe_tasks_vision_jni.so
    // у кожного, хто ставить застосунок. Повертати РАЗОМ із фазою, що його вмикає.
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)

    // Читання кирилиці. ML Kit її не читає НЕ погано, а ніяк — його
    // text-recognition знає латиницю, китайську, деванагарі, японську й
    // корейську, і жодного кириличного гліфа. Тому тут ORT і PP-OCRv5.
    //
    // Версія та сама, що в :core-ai (1.31.0), і це не косметика: ворота ходять
    // із --offline, а в кеші лежить рівно той .aar, що вже їздив у збірці.
    // Інша версія = червоні ворота на свіжому клоні.
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // mediapipe-tasks-vision був єдиним, хто підтягував цю п'ятірку вгору. Знявши
    // його, ми віддали вибір ML Kit — і він просить старіші. Тобто зняття мертвої
    // залежності МОВЧКИ відкотило б п'ять бібліотек Google на попередні версії,
    // і жоден рядок коду цього б не показав.
    //
    // Друге, важливіше: `.aar` для старих версій ніколи не качався — їх щоразу
    // перебивали новіші, тож у кеші лежав тільки `.pom`. А scripts/verify.sh
    // ходить із --offline. Тому без цих обмежень ворота падають на СВІЖОМУ клоні
    // й на CI — з повідомленням про архів бібліотеки, якою модуль не користується.
    // Тут стоїть те, що вже їздило в збірці; кеш, який хтось прогрів руками, —
    // не властивість репозиторію.
    // Сторож межі VisionRead: модуль був у збірці без жодного тесту, тобто
    // ворота не могли за нього почервоніти за побудовою (вимір 04.09: 22 модулі
    // з тестами, core-vision — єдиний без).
    testImplementation(libs.junit)

    constraints {
        implementation("com.google.android.datatransport:transport-api:3.0.0")
        implementation("com.google.android.datatransport:transport-backend-cct:3.1.0")
        implementation("com.google.android.datatransport:transport-runtime:3.1.0")
        implementation("com.google.firebase:firebase-encoders:17.0.0")
        implementation("com.google.firebase:firebase-encoders-json:18.0.0")
    }
}
