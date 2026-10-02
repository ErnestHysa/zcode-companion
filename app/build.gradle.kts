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
        versionCode = 25
        versionName = "0.7.5-phase7"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":features:pairing"))
    implementation(project(":features:tasks"))
    implementation(project(":features:conversation"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // MockZCode is TEST-ONLY: it must never enter a shipped artifact (INV-15).
    testImplementation(project(":tools:mockzcode"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(project(":tools:mockzcode"))
    androidTestImplementation(project(":core:security"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestDebugImplementation(libs.compose.ui.test.manifest)
}
