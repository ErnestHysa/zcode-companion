import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// core/ui — shared Compose theme + presentational components (ADR-016, Phase 6):
// the designed M3 token system (ui-ux package) must have ONE source of truth
// reachable by every feature module; features never depend on :app (§40.2).
// This module carries NO logic: no transport, no protocol, no domain state —
// INV-2/INV-6 hold (it is pure presentation, like the UI it serves).
android {
    namespace = "com.zcode.android.core.ui"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // B3-chrome (gaps-and-polish 2C): M3 TopAppBar scroll behaviors are still
        // @ExperimentalMaterial3Api — opted in module-wide, as every M3 app does.
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    // E6-lint (gaps-and-polish 2A): Compose lint discipline runs over this module.
    lintChecks(libs.compose.lint.checks)
    implementation(project(":core:common"))
    // UI-facing domain vocabulary only (ConnectionPhase, TaskStatus — pure Kotlin
    // types created as the UI mirror of the §14 machines; ADR-016). No reducers,
    // no repos: core:ui stays presentation-only.
    api(project(":domain"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.compose.ui.test.manifest)
}
