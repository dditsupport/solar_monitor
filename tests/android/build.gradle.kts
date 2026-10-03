// JVM tests for the Android app's BLE-relay sync (DeviceSyncer). The code under
// test is the app's own source, compiled for the JVM next to small fakes of the
// BLE peripheral, the cloud client and the session store it talks to — so no
// Android SDK or device is needed. Kotlin / library versions match the app.
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("../../android/solar-monitor-app/app/src/main/java")
            kotlin.srcDir("fakes")
            kotlin.include(
                "com/dangeedums/solar/sync/DeviceSyncer.kt",
                "com/dangeedums/solar/cloud/CloudModels.kt",
                "com/dangeedums/solar/ble/BleModels.kt",
                "*.kt",   // the fakes
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
