import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM camera logic (discovery, ONVIF, RTSP checks, brand URL formats),
// so it can be unit-tested without an Android device.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Integration tests use the simulated camera from the repo's Python tests.
    systemProperty("wanicam.repoRoot", (findProperty("wanicam.repoRoot") as String?) ?: rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
