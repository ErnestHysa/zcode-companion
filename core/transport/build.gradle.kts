plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// core:transport — endpoint-agnostic transport layer (§11). JVM-pure for now: the
// production WS client (OkHttp) runs identically on Android; no android.* imports so
// INV-2/INV-6 hold and the module graph stays testable. DirectTransport arrives as a
// skeleton interface only in this phase (§43.2 Phase 2 non-scope).
dependencies {
    implementation(project(":core:common"))
    api(project(":core:protocol"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    testImplementation(project(":tools:mockzcode"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
