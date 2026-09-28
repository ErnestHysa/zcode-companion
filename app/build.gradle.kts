import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// §40.1 app — MainActivity, AppContainer (manual DI), theme wiring. Phase 3 ships the
// pairing flow as the sole destination; task/conversation surfaces arrive in Phase 6.
android {
    namespace = "com.zcode.android.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zcode.android.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-phase3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Emulator/device E2E against the in-repo MockZCode (§35.4) — cleartext is
            // allowed ONLY for loopback endpoints in debug builds (INV-15: mock-only).
            manifestPlaceholders["usesCleartextBackup"] = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:transport"))
    implementation(project(":features:pairing"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // MockZCode is TEST-ONLY: it must never enter a shipped artifact (INV-15).
    androidTestImplementation(project(":tools:mockzcode"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestDebugImplementation(libs.compose.ui.test.manifest)
}
