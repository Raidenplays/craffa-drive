// AGP 9 has Kotlin built in — no org.jetbrains.kotlin.android plugin.
// The Compose compiler plugin (same version as Kotlin) also puts that Kotlin
// Gradle plugin version on the classpath, which is what built-in Kotlin uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
