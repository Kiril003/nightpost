// Рушій краудправди: факт, його згасання, вага голосу, фільтр на вході,
// ненавʼязливе питання, видима користь. Чиста JVM без Android — той самий код
// має жити і в телефоні (планувальник питань рахує на пристрої), і на сервері
// поруч зі сховком. Контракт сервера — у `TruthWire.kt`.
//
// Плагін без версії з тієї ж причини, що в :tools:obsidian: ворота йдуть
// `--offline`, а маркер kotlin.jvm уже лежить на класпасі кореня.
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
