plugins {
    kotlin("jvm") version "2.1.20"
}

group = "com.clipcascade.plus"
version = "1.0"

kotlin { jvmToolchain(17) }

dependencies {
    // Both are already part of the app (React Native brings OkHttp; app/build.gradle has serialization).
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
    // Integration and interop inputs come from the environment; tests that need them are skipped otherwise.
    listOf("CC_TEST_SERVER_URL", "CC_TEST_USERNAME", "CC_TEST_PASSWORD", "CC_DESKTOP_PEER_CMD", "CC_KOTLIN_FIXTURE_OUT")
        .forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    outputs.upToDateWhen { false }
}
