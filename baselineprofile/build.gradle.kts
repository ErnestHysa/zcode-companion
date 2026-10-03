import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// baselineprofile — §43.2-11.1 / §28.2: the Macrobenchmark gate (cold start +
// conversation scroll frame metrics) against the app's `benchmark` variant,
// with MockZCode running in the BENCHMARK process (INV-15: loopback mock only;
// never the production relay). Measurement-only — baseline-profile GENERATION
// itself is the Phase 12 release-pipeline item (§43.2-12.1).
android {
    namespace = "com.zcode.android.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ENVIRONMENT (§35.6): this machine has NO physical reference device — the
        // §28.1 budgets are mid-range-2023-hardware targets, so the gate runs
        // emulator-class with the library's EMULATOR error suppressed and every
        // number reported as EMULATOR-CLASS in docs/perf-phase11.md. The
        // reference-device measurement is a tracked ticket, not a pass.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    // Self-instrumenting: the module runs as its own instrumentation process.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Runs on the local instrumented fleet (the ATD API-30 emulator used by every
    // phase); Gradle managed devices are deliberately not introduced (solo-dev
    // proportionality, §2.3 — the CI workflow drives the emulator directly).
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    // The §35.4 protocol-faithful mock — runs in THIS process on loopback only.
    implementation(project(":tools:mockzcode"))
    implementation(libs.kotlin.test.junit)
    implementation(libs.kotlinx.coroutines.test)
}
