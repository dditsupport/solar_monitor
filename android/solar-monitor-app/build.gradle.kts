buildscript {
    dependencies {
        // AGP 9 brings its own (older) Kotlin Gradle plugin for built-in
        // Kotlin. Declaring it here raises it to the catalog's Kotlin version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
