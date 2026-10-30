// Компілятор стилю «Обсидіан» — єдиний модуль без Android.
//
// Плагін береться БЕЗ версії: kotlin-gradle-plugin уже лежить на класпасі
// кореня (`alias(libs.plugins.kotlin.android) apply false`), і той самий jar
// оголошує `org.jetbrains.kotlin.jvm`. Свій alias у каталозі означав би
// резолв маркера з gradlePluginPortal, а ворота ходять з `--offline`.
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
}

val stylesDir = rootDir.resolve("feature-stream/src/main/assets/styles")
val tokensDoc = rootDir.resolve("docs/atlas/OBSIDIAN_TOKENS_V0.md")
val designTokens = rootDir.resolve("../docs/design/system/tokens.json")

// Вихід свідомо не оголошений: тека лежить усередині src іншого модуля, і
// declared output зробив би з неї неоголошену залежність збірки Android.
tasks.register<JavaExec>("bake") {
    group = "phantom"
    description = "Пече obsidian-night.json та obsidian-day.json в асети feature-stream."
    mainClass.set("local.phantom.obsidian.BakeKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(stylesDir.absolutePath)
}

tasks.withType<Test>().configureEach {
    // Порядок не випадковий: `BakeDeterminismTest` звіряє асет на диску з
    // компілятором, тож випічка мусить статись першою — інакше правка
    // компілятора валила б ворота ще до того, як асет встиг оновитись.
    mustRunAfter(tasks.named("bake"))
    systemProperty("obsidian.assets", stylesDir.absolutePath)
    systemProperty("obsidian.src", projectDir.resolve("src/main/kotlin").absolutePath)
    systemProperty("obsidian.tokens.doc", tokensDoc.absolutePath)
    systemProperty("phantom.design.tokens", designTokens.absolutePath)
}
