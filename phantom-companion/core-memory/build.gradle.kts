// Памʼять PHANTOM на телефоні — репліка ledger-а ПК (phantom-os/src/backend/memory/ledger)
// з тими самими правилами: запис, надгробки, дійсність, бюджет промпту 1500 токенів,
// журнал змін для синку (docs/memory/SYNC.md). Чиста JVM без Android: правила
// перевіряються спільними векторами, які пише Python (`MemoryVectorsTest`).
//
// Плагін без версії з тієї ж причини, що в :core-truth: ворота йдуть `--offline`,
// а маркер kotlin.jvm уже лежить на класпасі кореня.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
