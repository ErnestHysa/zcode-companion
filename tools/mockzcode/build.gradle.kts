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

/** Host-side launcher for manual/device E2E (`gradle :tools:mockzcode:mockRelayServer`). */
tasks.register("mockRelayServer", JavaExec::class) {
    group = "verification"
    description = "Runs MockZCode on a local port for on-device pairing E2E (INV-15: synthetic only)."
    mainClass = "com.zcode.android.tools.mockzcode.MockRelayMainKt"
    classpath = sourceSets["main"].runtimeClasspath
}

/**
 * RIG-P4: the `zcode --web` DIRECT surface (server-info + /ws, zcode_lite_token contract)
 * on MOCK_PORT (default 18200) with MOCK_DIRECT_TOKEN (default `rig-direct-token`);
 * every other MOCK_* arm applies. docs/runbooks/direct-server.md §6 has the three shapes.
 */
tasks.register("mockDirectServer", JavaExec::class) {
    group = "verification"
    description = "Runs MockZCode's DIRECT (zcode --web) surface for on-device direct-endpoint E2E (INV-15)."
    mainClass = "com.zcode.android.tools.mockzcode.MockRelayMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    environment("MOCK_MODE", "direct")
}
