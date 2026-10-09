plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// §40.1 domain — "PURE Kotlin: models (§16), reducers/state machines (§14), repository
// interfaces, idempotency classifier, recovery journal model. No Android, no OkHttp, no
// Compose (enforced by module deps)." Dependency edges per §40.2: domain → core:common
// ONLY (never core:protocol/transport — protocol→domain mapping lives in :data).
dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
