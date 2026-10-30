// Root build script — composite build glue.
//
// All AGP / Kotlin plugins are loaded onto the classpath here with `apply
// false`; per-module `build.gradle.kts` files then `alias(...)`-apply the
// ones they actually need. This is the canonical multi-module pattern
// recommended by AGP 8.x and avoids "plugin already on classpath with an
// unknown version" failures when sub-modules try to resolve them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
