plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// Test tooling only (§35.4): JVM protocol-faithful ZCode/relay mock — NEVER shipped in
// the app, never connected to production systems. Depends on the real codec so its
// self-test proves encode/decode round-trips against the shipped protocol layer.
dependencies {
    api(project(":core:protocol"))
    api(libs.kotlinx.coroutines.core)
    api(libs.mockwebserver3)
    api(libs.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
